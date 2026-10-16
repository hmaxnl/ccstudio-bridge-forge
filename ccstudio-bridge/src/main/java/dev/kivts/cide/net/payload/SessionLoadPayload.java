package dev.kivts.cide.net.payload;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

public record SessionLoadPayload(int computerId, String json) {

    public static final ResourceLocation TYPE = new ResourceLocation("ccstudio", "session_load");

    public static void encode(SessionLoadPayload p, FriendlyByteBuf buf) {
        buf.writeVarInt(p.computerId());
        buf.writeUtf(p.json());
    }

    public static SessionLoadPayload decode(FriendlyByteBuf buf) {
        int computerId = buf.readVarInt();
        String json = buf.readUtf();
        return new SessionLoadPayload(computerId, json);
    }
}
