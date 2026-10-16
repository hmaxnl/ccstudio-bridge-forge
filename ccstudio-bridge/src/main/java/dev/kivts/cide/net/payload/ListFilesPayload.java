package dev.kivts.cide.net.payload;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;

public record ListFilesPayload(BlockPos pos, int computerId, String path) {

    public static final ResourceLocation TYPE = new ResourceLocation("ccstudio", "list_files");

    public static void encode(ListFilesPayload p, FriendlyByteBuf buf) {
        buf.writeBlockPos(p.pos());
        buf.writeVarInt(p.computerId());
        buf.writeUtf(p.path());
    }

    public static ListFilesPayload decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        int computerId = buf.readVarInt();
        String path = buf.readUtf();
        return new ListFilesPayload(pos, computerId, path);
    }
}
