package dev.kivts.cide.net.payload;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.Map;

public record PeripheralMapPayload(int computerId, Map<String, String> sideToType) {
    public static final ResourceLocation TYPE = new ResourceLocation("ccstudio", "peripheral_map");

    public static void encode(PeripheralMapPayload value, FriendlyByteBuf buf) {
        buf.writeVarInt(value.computerId());
        buf.writeVarInt(value.sideToType().size());
        for (Map.Entry<String, String> e : value.sideToType().entrySet()) {
            buf.writeUtf(e.getKey());
            buf.writeUtf(e.getValue());
        }
    }

    public static PeripheralMapPayload decode(FriendlyByteBuf buf) {
        int computerId = buf.readVarInt();
        int size = buf.readVarInt();
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < size; i++) map.put(buf.readUtf(), buf.readUtf());
        return new PeripheralMapPayload(computerId, map);
    }
}
