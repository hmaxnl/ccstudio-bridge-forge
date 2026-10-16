package dev.kivts.cide.net.payload;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;

public record CreateFolderPayload(BlockPos pos, int computerId, String path) {

    public static final ResourceLocation TYPE = new ResourceLocation("ccstudio", "create_folder");

    public static void encode(CreateFolderPayload p, FriendlyByteBuf buf) {
        buf.writeBlockPos(p.pos());
        buf.writeVarInt(p.computerId());
        buf.writeUtf(p.path());
    }

    public static CreateFolderPayload decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        int computerId = buf.readVarInt();
        String path = buf.readUtf();
        return new CreateFolderPayload(pos, computerId, path);
    }
}
