package dev.kivts.cide.net.payload;

import dan200.computercraft.shared.computer.terminal.TerminalState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

public record ConsoleStatePayload(int computerId, boolean on, TerminalState terminal) {
    public static final ResourceLocation TYPE = new ResourceLocation("ccstudio", "console_state");

    public static void encode(ConsoleStatePayload p, FriendlyByteBuf buf) {
        buf.writeVarInt(p.computerId);
        buf.writeBoolean(p.on);
        buf.writeBoolean(p.terminal != null);
        if (p.terminal != null) p.terminal.write(buf);
    }

    public static ConsoleStatePayload decode(FriendlyByteBuf buf) {
        int computerId = buf.readVarInt();
        boolean on = buf.readBoolean();
        TerminalState terminal = buf.readBoolean() ? new TerminalState(buf) : null;
        return new ConsoleStatePayload(computerId, on, terminal);
    }
}
