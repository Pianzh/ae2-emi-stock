package local.stock;

import appeng.api.networking.IGrid;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.core.network.clientbound.MEInventoryUpdatePacket;
import appeng.menu.me.common.IncrementalUpdateHelper;
import appeng.integration.modules.curios.CuriosIntegration;
import appeng.items.tools.powered.WirelessTerminalItem;
import appeng.menu.locator.ItemMenuHostLocator;
import appeng.menu.locator.MenuLocators;
import appeng.menu.me.crafting.CraftAmountMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import java.util.*;

@EventBusSubscriber(modid="ae2emistock")
public final class StockServer {
    private static final Map<ServerPlayer,Integer> lastRequest = new WeakHashMap<>();
    private static final Map<ServerPlayer,Integer> lastOrder = new WeakHashMap<>();
    private static final Map<IGrid,Integer> epochs = new WeakHashMap<>();
    private static int nextEpoch;
    private static final Map<ServerPlayer,Subscription> subscriptions = new WeakHashMap<>();

    private static final class Subscription {
        int generation, lastWatch, nextUpdate;
        boolean active, initialized;
        IGrid grid;
        IncrementalUpdateHelper ids = new IncrementalUpdateHelper();
        KeyCounter previous = new KeyCounter();
        Set<AEKey> craftable = Set.of();
    }

    static void watch(StockMod.Watch watch, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer player)) return;
        var sub = subscriptions.computeIfAbsent(player, p -> new Subscription());
        if (sub.generation != watch.generation()) sub.initialized = false;
        sub.generation = watch.generation();
        sub.lastWatch = player.tickCount;
        sub.active = watch.open();
    }

    @SubscribeEvent public static void update(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        var sub = subscriptions.get(player);
        if (sub == null) return;
        int now = player.tickCount;
        if (now - sub.lastWatch > 600) { subscriptions.remove(player); return; }
        if (!sub.active || now - sub.lastWatch > 80 || now < sub.nextUpdate) return;
        sub.nextUpdate = now + 6;
        var terminal = findTerminal(player);
        var grid = terminal == null ? null : terminal.grid();
        boolean full = !sub.initialized || grid != sub.grid;
        if (grid == null && !full) return;
        if (full) sub.ids = new IncrementalUpdateHelper();
        var stored = grid == null ? new KeyCounter() : grid.getStorageService().getCachedInventory();
        Set<AEKey> craftable = grid == null ? Set.of() : grid.getCraftingService().getCraftables(key -> true);
        var empty = new KeyCounter();
        var builder = MEInventoryUpdatePacket.builder(sub.generation, full, player.registryAccess());
        if (full) {
            builder.addFull(sub.ids, stored, craftable, empty);
        } else {
            for (var entry : stored) if (sub.previous.get(entry.getKey()) != entry.getLongValue()) sub.ids.addChange(entry.getKey());
            for (var entry : sub.previous) if (stored.get(entry.getKey()) == 0) sub.ids.addChange(entry.getKey());
            for (var key : craftable) if (!sub.craftable.contains(key)) sub.ids.addChange(key);
            for (var key : sub.craftable) if (!craftable.contains(key)) sub.ids.addChange(key);
            if (!sub.ids.hasChanges()) return;
            builder.addChanges(sub.ids, stored, craftable, empty);
        }
        var packets = builder.build();
        if (full && packets.isEmpty()) {
            var reset = new MEInventoryUpdatePacket(true, sub.generation, List.of(), 0, null);
            PacketDistributor.sendToPlayer(player, new StockMod.Inventory(reset));
        }
        for (var packet : packets) PacketDistributor.sendToPlayer(player, new StockMod.Inventory(packet));
        sub.ids.commitChanges();
        sub.grid = grid;
        sub.initialized = true;
        sub.previous.reset();
        sub.previous.addAll(stored);
        sub.craftable = Set.copyOf(craftable);
    }

    static void handle(StockMod.Query request, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer player) || request.keys().isEmpty()) return;
        int now = player.tickCount;
        if (now - lastRequest.getOrDefault(player, now - 4) < 4) return;
        lastRequest.put(player, now);
        var terminal = findTerminal(player);
        if (terminal == null) {
            ctx.reply(new StockMod.Answer(request.id(), 0, List.of()));
            return;
        }
        var grid = terminal.grid();
        var inventory = grid.getStorageService().getCachedInventory();
        var crafting = grid.getCraftingService();
        var values = new ArrayList<Long>(request.keys().size());
        for (var key : request.keys()) {
            long stored = Math.max(0, inventory.get(key));
            values.add(crafting.isCraftable(key) ? ~stored : stored);
        }
        ctx.reply(new StockMod.Answer(request.id(), epochs.computeIfAbsent(grid, k -> ++nextEpoch), values));
    }

    static void order(StockMod.Order request, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer player) || player.containerMenu != player.inventoryMenu) return;
        int tick = player.tickCount;
        if (tick - lastOrder.getOrDefault(player, tick - 4) < 4) return;
        lastOrder.put(player, tick);
        var terminal = findTerminal(player);
        if (terminal == null) {
            player.sendSystemMessage(Component.literal("无线终端未连接到 ME 网络"));
            return;
        }
        var key = request.target();
        if (!terminal.grid().getCraftingService().isCraftable(key)) {
            player.sendSystemMessage(Component.literal("ME 网络没有此物品的合成样板"));
            return;
        }
        CraftAmountMenu.open(player, terminal.locator(), key, key.getAmountPerUnit());
    }

    private record ConnectedTerminal(IGrid grid, ItemMenuHostLocator locator) {}

    private static ConnectedTerminal findTerminal(ServerPlayer player) {
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (inventory.getItem(i).getItem() instanceof WirelessTerminalItem terminal) {
                var grid = connectedGrid(player, terminal, MenuLocators.forInventorySlot(i));
                if (grid != null) return grid;
            }
        }
        var worn = player.getCapability(CuriosIntegration.ITEM_HANDLER);
        if (worn != null) for (int i = 0; i < worn.size(); i++) {
            if (worn.getResource(i).getItem() instanceof WirelessTerminalItem terminal) {
                var grid = connectedGrid(player, terminal, MenuLocators.forCurioSlot(i));
                if (grid != null) return grid;
            }
        }
        return null;
    }

    private static ConnectedTerminal connectedGrid(ServerPlayer player, WirelessTerminalItem terminal, ItemMenuHostLocator locator) {
        var stack = locator.locateItem(player);
        if (stack.isEmpty() || terminal.getLinkedPosition(stack) == null) return null;
        var host = terminal.getMenuHost(player, locator, null);
        if (host == null || !host.getLinkStatus().connected()) return null;
        var node = host.getActionableNode();
        return node != null && node.isActive() ? new ConnectedTerminal(node.getGrid(), locator) : null;
    }
}
