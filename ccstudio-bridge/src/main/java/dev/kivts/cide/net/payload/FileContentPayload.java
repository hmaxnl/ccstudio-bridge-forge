package dev.kivts.cide.net.payload;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.nio.charset.StandardCharsets;

public record FileContentPayload(int computerId, String path, String content, boolean readOnly) {
    private static final int MAX_PATH_CHARS = 1024;
    private static final int MAX_CONTENT_BYTES = 16 * 1024 * 1024;

    public static final ResourceLocation TYPE = new ResourceLocation("ccstudio", "file_content");

    public static void encode(FileContentPayload payload, FriendlyByteBuf buf) {
        byte[] bytes = payload.content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_CONTENT_BYTES) {
            throw new IllegalArgumentException("file content is too large");
        }
        buf.writeVarInt(payload.computerId);
        buf.writeUtf(payload.path, MAX_PATH_CHARS);
        buf.writeVarInt(bytes.length);
        buf.writeBytes(bytes);
        buf.writeBoolean(payload.readOnly);
    }

    public static FileContentPayload decode(FriendlyByteBuf buf) {
        int computerId = buf.readVarInt();
        String path = buf.readUtf(MAX_PATH_CHARS);
        int length = buf.readVarInt();
        if (length < 0 || length > MAX_CONTENT_BYTES) {
            throw new IllegalArgumentException("file content is too large");
        }
        byte[] bytes = new byte[length];
        buf.readBytes(bytes);
        boolean readOnly = buf.readBoolean();
        return new FileContentPayload(computerId, path, new String(bytes, StandardCharsets.UTF_8), readOnly);
    }
}
