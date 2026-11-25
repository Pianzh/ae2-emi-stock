package local.stock.client;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEItemKey;
import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.stack.*;
import dev.emi.emi.runtime.EmiFavorite;
import dev.emi.emi.screen.RecipeScreen;
import dev.emi.emi.screen.EmiScreenManager.SidebarEmiStackInteraction;
import dev.emi.emi.config.SidebarType;
import dev.emi.emi.screen.EmiScreenManager.ScreenSpace;
import local.stock.StockMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.world.item.Item;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import java.util.*;

@EventBusSubscriber(modid="ae2emistock", value=Dist.CLIENT)
public final class StockOverlay {
    private record Badge(int tick, String text, int color) {}
    private static final Map<AEKey,Badge> badges = new HashMap<>();
    private static final Set<AEKey> visible = new LinkedHashSet<>();
    private static final Map<EmiStack,AEKey> keyCache = new IdentityHashMap<>();
    private static ClientPacketListener connection;
    private static Object level;
    private static List<AEKey> pending = List.of();
    private static int clock, requestId, nextQuery, sent, epoch;
    private static boolean supported;
    private static boolean fullSync, watching;
    private static int generation, lastWatch, closedAt;
    private static final Map<Long, AEKey> serials = new HashMap<>();

    @SubscribeEvent public static void handlers(RegisterClientPayloadHandlersEvent event) {
        event.register(StockMod.Answer.TYPE, (answer, context) -> receive(answer));
        event.register(StockMod.Inventory.TYPE, (message, context) -> {
            var update = message.update();
            if (!fullSync || update.containerId() != generation || connection != Minecraft.getInstance().getConnection()) return;
            if (update.fullUpdate()) { serials.clear(); badges.clear(); }
            for (var entry : update.getActualEntries()) {
                long serial = entry.getSerial();
                if (entry.getWhat() != null) serials.put(serial, entry.getWhat());
                var key = serials.get(serial);
                if (key == null) continue;
                if (entry.getStoredAmount() > 0 || entry.isCraftable()) {
                    badges.put(key, badge(entry.getStoredAmount(), entry.isCraftable()));
                } else {
                    serials.remove(serial);
                    badges.remove(key);
                }
            }
        });
    }

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        clock++;
        if (connection != mc.getConnection() || level != mc.level) {
            connection = mc.getConnection(); level = mc.level;
            badges.clear(); visible.clear(); keyCache.clear(); pending = List.of(); epoch = 0; nextQuery = clock;
            supported = connection != null && level != null && NetworkRegistry.hasChannel(connection, StockMod.Query.TYPE.id());
            fullSync = supported && NetworkRegistry.hasChannel(connection, StockMod.Watch.TYPE.id());
            watching = false; serials.clear(); generation++; closedAt = clock;
        }
        if (fullSync) {
            boolean open = mc.player != null && EmiApi.getHandledScreen() != null;
            if (open != watching || (open && clock - lastWatch >= 40)) {
                if (!open) closedAt = clock;
                if (open && !watching && clock - closedAt >= 600) {
                    generation++; badges.clear(); serials.clear();
                }
                ClientPacketDistributor.sendToServer(new StockMod.Watch(generation, open));
                watching = open; lastWatch = clock;
            }
            if (!open && clock - closedAt == 600) { badges.clear(); serials.clear(); }
            return;
        }
        if (supported && mc.gui.screen() != null && clock >= nextQuery && (pending.isEmpty() || clock - sent >= 60)) {
            var batch = visible.stream().filter(k -> !badges.containsKey(k) || clock - badges.get(k).tick >= 20)
                    .sorted(Comparator.comparingInt(k -> badges.containsKey(k) ? badges.get(k).tick : Integer.MIN_VALUE))
                    .limit(StockMod.LIMIT).toList();
            if (!batch.isEmpty()) {
                pending = batch; sent = clock; nextQuery = clock + 5;
                ClientPacketDistributor.sendToServer(new StockMod.Query(++requestId, batch));
            }
        }
        visible.clear();
        if (clock % 40 == 0) badges.values().removeIf(b -> clock - b.tick > 60);
    }

    private static void receive(StockMod.Answer answer) {
        if (pending.isEmpty() || answer.id() != requestId || Minecraft.getInstance().getConnection() != connection) return;
        var keys = pending; pending = List.of();
        if (epoch != answer.epoch()) badges.clear();
        epoch = answer.epoch();
        if (epoch == 0) { nextQuery = clock + 30; return; }
        if (answer.values().size() != keys.size()) return;
        for (int i = 0; i < keys.size(); i++) {
            long encoded = answer.values().get(i);
            long amount = encoded < 0 ? ~encoded : encoded;
            badges.put(keys.get(i), badge(amount, encoded < 0));
        }
    }

    private static Badge badge(long amount, boolean craftable) {
        String label = amount > 0 ? compact(amount) : craftable ? "+" : "";
        return new Badge(clock, label, amount > 0 ? 0xFF8BF5B1 : 0xFF84D6FF);
    }

    private static String compact(long amount) {
        if (amount < 1000) return Long.toString(amount);
        String units = "KMGTPE";
        long divisor = 1000;
        int unit = 0;
        while (amount / divisor >= 1000 && unit < units.length()-1) { divisor *= 1000; unit++; }
        long whole = amount / divisor, tenth = amount % divisor / (divisor / 10);
        return whole + (whole < 10 && tenth > 0 ? "." + tenth : "") + units.charAt(unit);
    }

    private static AEKey displayedItem(EmiIngredient ingredient) {
        if (ingredient instanceof EmiFavorite favorite) return displayedItem(favorite.getStack());
        if (ingredient instanceof ListEmiIngredient list) {
            var entries = list.getIngredients();
            if (entries.isEmpty()) return null;
            return displayedItem(entries.get((int)((System.currentTimeMillis() / 1000) % entries.size())));
        }
        var options = ingredient.getEmiStacks();
        if (options.isEmpty() || options.size() > 1 && !(ingredient instanceof TagEmiIngredient)) return null;
        var stack = options.getFirst();
        if (!(stack.getKey() instanceof Item) || stack.isEmpty()) return null;
        return keyCache.computeIfAbsent(stack, s -> AEItemKey.of(s.getItemStack()));
    }

    public static boolean tryOrder(Minecraft mc, int x, int y) {
        if (mc.player == null || mc.getConnection() == null) return false;
        var parent = EmiApi.getHandledScreen();
        if (parent == null) return false;
        // The creative item picker replaces containerMenu locally. The server still has inventoryMenu.
        if (!(parent instanceof CreativeModeInventoryScreen) && parent.getMenu() != mc.player.inventoryMenu) return false;
        if (!NetworkRegistry.hasChannel(mc.getConnection(), StockMod.Order.TYPE.id())) return false;
        var interaction = EmiApi.getHoveredStack(x, y, false);
        AEKey key;
        if (interaction instanceof SidebarEmiStackInteraction) key = displayedItem(interaction.getStack());
        else if (mc.gui.screen() instanceof RecipeScreen recipe) key = displayedItem(recipe.getHoveredStack());
        else return false;
        if (key == null) return false;
        ClientPacketDistributor.sendToServer(new StockMod.Order(key));
        return true;
    }

    public static void draw(ScreenSpace space, GuiGraphicsExtractor graphics, int offset) {
        var type = space.getType();
        if (!supported || space.pageSize <= 0 || offset < 0) return;
        if (type != SidebarType.INDEX && type != SidebarType.FAVORITES && type != SidebarType.LOOKUP_HISTORY && type != SidebarType.CRAFT_HISTORY) return;
        var stacks = space.getStacks();
        var font = Minecraft.getInstance().font;
        if (keyCache.size() > 8192) keyCache.clear();
        for (int i = 0; i < space.pageSize && offset+i < stacks.size(); i++) {
            var key = displayedItem(stacks.get(offset+i));
            if (key == null) continue;
            if (!fullSync) visible.add(key);
            var badge = badges.get(key);
            if (badge == null || badge.text.isEmpty() || (!fullSync && clock - badge.tick > 60)) continue;
            var pose = graphics.pose();
            pose.pushMatrix();
            pose.translate(space.getRawX(i) + 17, space.getRawY(i) + 1);
            pose.scale(0.65f);
            graphics.text(font, badge.text, -font.width(badge.text), 0, badge.color, true);
            pose.popMatrix();
        }
    }
}
