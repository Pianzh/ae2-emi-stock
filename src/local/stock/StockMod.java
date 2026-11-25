package local.stock;

import appeng.api.stacks.AEKey;
import appeng.core.network.clientbound.MEInventoryUpdatePacket;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import java.util.ArrayList;
import java.util.List;

@Mod("ae2emistock")
public final class StockMod {
    public static final int LIMIT = 96;

    public StockMod(IEventBus bus) { bus.addListener(StockMod::register); }

    private static void register(RegisterPayloadHandlersEvent event) {
        var channel = event.registrar("1").optional();
        channel.playToServer(Query.TYPE, Query.CODEC, StockServer::handle);
        channel.playToServer(Order.TYPE, Order.CODEC, StockServer::order);
        channel.playToClient(Answer.TYPE, Answer.CODEC);
        channel.playToServer(Watch.TYPE, Watch.CODEC, StockServer::watch);
        channel.playToClient(Inventory.TYPE, Inventory.CODEC);
    }

    public record Watch(int generation, boolean open) implements CustomPacketPayload {
        public static final Type<Watch> TYPE = new Type<>(Identifier.fromNamespaceAndPath("ae2emistock", "watch"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Watch> CODEC = StreamCodec.of((buf,w) -> {
            buf.writeVarInt(w.generation); buf.writeBoolean(w.open);
        }, buf -> new Watch(buf.readVarInt(), buf.readBoolean()));
        public Type<Watch> type() { return TYPE; }
    }

    public record Inventory(MEInventoryUpdatePacket update) implements CustomPacketPayload {
        public static final Type<Inventory> TYPE = new Type<>(Identifier.fromNamespaceAndPath("ae2emistock", "inventory"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Inventory> CODEC = MEInventoryUpdatePacket.STREAM_CODEC.map(Inventory::new, Inventory::update);
        public Type<Inventory> type() { return TYPE; }
    }

    private static int readSize(RegistryFriendlyByteBuf buf) {
        int size = buf.readVarInt();
        if (size < 0 || size > LIMIT) throw new IllegalArgumentException("Invalid stock batch size " + size);
        return size;
    }

    public record Query(int id, List<AEKey> keys) implements CustomPacketPayload {
        public static final Type<Query> TYPE = new Type<>(Identifier.fromNamespaceAndPath("ae2emistock", "query"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Query> CODEC = StreamCodec.of((buf, q) -> {
            buf.writeVarInt(q.id); buf.writeVarInt(q.keys.size());
            for (var key : q.keys) AEKey.STREAM_CODEC.encode(buf, key);
        }, buf -> {
            int id = buf.readVarInt(), size = readSize(buf);
            var keys = new ArrayList<AEKey>(size);
            for (int i = 0; i < size; i++) keys.add(AEKey.STREAM_CODEC.decode(buf));
            return new Query(id, keys);
        });
        public Type<Query> type() { return TYPE; }
    }

    public record Order(AEKey target) implements CustomPacketPayload {
        public static final Type<Order> TYPE = new Type<>(Identifier.fromNamespaceAndPath("ae2emistock", "order"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Order> CODEC = AEKey.STREAM_CODEC.map(Order::new, Order::target);
        public Type<Order> type() { return TYPE; }
    }

    // Epoch zero means offline. Each positive epoch identifies one live grid on this server.
    // Nonnegative values are stored counts; the ones' complement encodes a craftable entry.
    public record Answer(int id, int epoch, List<Long> values) implements CustomPacketPayload {
        public static final Type<Answer> TYPE = new Type<>(Identifier.fromNamespaceAndPath("ae2emistock", "answer"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Answer> CODEC = StreamCodec.of((buf, a) -> {
            buf.writeVarInt(a.id); buf.writeVarInt(a.epoch); buf.writeVarInt(a.values.size());
            for (long value : a.values) buf.writeLong(value);
        }, buf -> {
            int id = buf.readVarInt(), epoch = buf.readVarInt(), size = readSize(buf);
            var values = new ArrayList<Long>(size);
            for (int i = 0; i < size; i++) values.add(buf.readLong());
            return new Answer(id, epoch, values);
        });
        public Type<Answer> type() { return TYPE; }
    }
}
