package dev.kivts.cide.net.payload;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;

public record WriteFilePayload(BlockPos pos, int computerId, String path, String content) {

    public static final ResourceLocation TYPE = new ResourceLocation("ccstudio", "write_file");

    public static void encode(WriteFilePayload p, FriendlyByteBuf buf) {
        buf.writeBlockPos(p.pos());
        buf.writeVarInt(p.computerId());
        buf.writeUtf(p.path());
        buf.writeUtf(p.content());
    }

    public static WriteFilePayload decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        int computerId = buf.readVarInt();
        String path = buf.readUtf();
        String content = buf.readUtf();
        return new WriteFilePayload(pos, computerId, path, content);
    }
}
