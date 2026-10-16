package dev.kivts.cide.net.payload;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;

public record CreateFilePayload(BlockPos pos, int computerId, String directory) {

    public static final ResourceLocation TYPE = new ResourceLocation("ccstudio", "create_file");

    public static void encode(CreateFilePayload p, FriendlyByteBuf buf) {
        buf.writeBlockPos(p.pos());
        buf.writeVarInt(p.computerId());
        buf.writeUtf(p.directory());
    }

    public static CreateFilePayload decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        int computerId = buf.readVarInt();
        String directory = buf.readUtf();
        return new CreateFilePayload(pos, computerId, directory);
    }
}
