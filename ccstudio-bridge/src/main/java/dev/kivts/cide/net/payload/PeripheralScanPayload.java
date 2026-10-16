package dev.kivts.cide.net.payload;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;

/**
 * Client -> server: re-scan the computer's attached peripherals (local sides +
 * wired network) and (re)generate `.vscode/peripheral_stubs.lua` on its drive
 * for VS Code / LuaLS autocomplete (spec section 5, phase 5). Fire-and-forget;
 * the server sends no reply.
 */
public record PeripheralScanPayload(BlockPos pos, int computerId) {

    public static final ResourceLocation TYPE = new ResourceLocation("ccstudio", "peripheral_scan");

    public static void encode(PeripheralScanPayload p, FriendlyByteBuf buf) {
        buf.writeBlockPos(p.pos());
        buf.writeVarInt(p.computerId());
    }

    public static PeripheralScanPayload decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        int computerId = buf.readVarInt();
        return new PeripheralScanPayload(pos, computerId);
    }
}
