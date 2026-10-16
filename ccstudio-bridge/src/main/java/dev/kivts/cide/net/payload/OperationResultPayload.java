package dev.kivts.cide.net.payload;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

public record OperationResultPayload(boolean ok, String message, String path) {

    public static final ResourceLocation TYPE = new ResourceLocation("ccstudio", "operation_result");

    public static void encode(OperationResultPayload p, FriendlyByteBuf buf) {
        buf.writeBoolean(p.ok());
        buf.writeUtf(p.message());
        buf.writeUtf(p.path());
    }

    public static OperationResultPayload decode(FriendlyByteBuf buf) {
        boolean ok = buf.readBoolean();
        String message = buf.readUtf();
        String path = buf.readUtf();
        return new OperationResultPayload(ok, message, path);
    }
}
