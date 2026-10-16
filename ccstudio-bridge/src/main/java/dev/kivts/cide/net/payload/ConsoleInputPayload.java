package dev.kivts.cide.net.payload;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

public record ConsoleInputPayload(
    BlockPos pos, int computerId, UUID sessionId,
    int action, int a, int b, int x, int y, String text
) {
    public static final int KEY_DOWN = 0;
    public static final int KEY_UP = 1;
    public static final int CHAR = 2;
    public static final int PASTE = 3;
    public static final int MOUSE_CLICK = 4;
    public static final int MOUSE_UP = 5;
    public static final int MOUSE_DRAG = 6;
    public static final int MOUSE_SCROLL = 7;

    public static final ResourceLocation TYPE = new ResourceLocation("ccstudio", "console_input");

    public static void encode(ConsoleInputPayload p, FriendlyByteBuf buf) {
        buf.writeBlockPos(p.pos);
        buf.writeVarInt(p.computerId);
        buf.writeUUID(p.sessionId);
        buf.writeVarInt(p.action);
        buf.writeVarInt(p.a);
        buf.writeVarInt(p.b);
        buf.writeVarInt(p.x);
        buf.writeVarInt(p.y);
        buf.writeUtf(p.text, 8192);
    }

    public static ConsoleInputPayload decode(FriendlyByteBuf buf) {
        return new ConsoleInputPayload(
            buf.readBlockPos(),
            buf.readVarInt(),
            buf.readUUID(),
            buf.readVarInt(),
            buf.readVarInt(),
            buf.readVarInt(),
            buf.readVarInt(),
            buf.readVarInt(),
            buf.readUtf(8192)
        );
    }
}
