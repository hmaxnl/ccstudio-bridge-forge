package dev.kivts.cide.net.payload;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;

public record DeleteFilePayload(BlockPos pos, int computerId, String path) {

    public static final ResourceLocation TYPE = new ResourceLocation("ccstudio", "delete_file");

    public static void encode(DeleteFilePayload p, FriendlyByteBuf buf) {
        buf.writeBlockPos(p.pos());
        buf.writeVarInt(p.computerId());
        buf.writeUtf(p.path());
    }

    public static DeleteFilePayload decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        int computerId = buf.readVarInt();
        String path = buf.readUtf();
        return new DeleteFilePayload(pos, computerId, path);
    }
}
