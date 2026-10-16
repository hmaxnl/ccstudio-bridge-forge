package dev.kivts.cide.net.payload;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;

public record RenameFilePayload(BlockPos pos, int computerId, String sourcePath, String newName) {

    public static final ResourceLocation TYPE = new ResourceLocation("ccstudio", "rename_file");

    public static void encode(RenameFilePayload p, FriendlyByteBuf buf) {
        buf.writeBlockPos(p.pos());
        buf.writeVarInt(p.computerId());
        buf.writeUtf(p.sourcePath());
        buf.writeUtf(p.newName());
    }

    public static RenameFilePayload decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        int computerId = buf.readVarInt();
        String sourcePath = buf.readUtf();
        String newName = buf.readUtf();
        return new RenameFilePayload(pos, computerId, sourcePath, newName);
    }
}
