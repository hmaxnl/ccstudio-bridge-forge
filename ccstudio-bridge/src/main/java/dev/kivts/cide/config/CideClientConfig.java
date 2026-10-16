package dev.kivts.cide.config;

import net.minecraftforge.common.ForgeConfigSpec;

public final class CideClientConfig {
    public static final ForgeConfigSpec SPEC;

    // opacity of the editor. Default: 0.533
    public static final ForgeConfigSpec.DoubleValue OPACITY;

    // ccLink bridge (spec section 6). Pairing is per-computer and derived at
    // runtime from the open computer's identity (block position), so there is
    // no per-client UUID/name to persist here.
    public static final ForgeConfigSpec.IntValue DEFAULT_PORT;
    public static final ForgeConfigSpec.BooleanValue AUTO_START_SERVER;
    public static final ForgeConfigSpec.BooleanValue SHOW_SYNC_NOTIFICATIONS;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        OPACITY = b
            .comment("Opacity of the editor. Default: 0.533 - Good balance between both (well, its in the middle) ")
            .defineInRange("opacity", 0.533, 0.1, 1.0);

        b.comment("ccLink WebSocket bridge settings").push("general");
        DEFAULT_PORT = b
            .comment("Default port of the ccLink extension's WebSocket server")
            .defineInRange("default_port", 56560, 1, 65535);
        AUTO_START_SERVER = b
            .comment("Automatically connect to the ccLink extension when a computer's IDE is opened (otherwise use the Bridge Panel's Connect button)")
            .define("auto_start_server", true);
        SHOW_SYNC_NOTIFICATIONS = b
            .comment("Show notification popups in-game when a file is synced from VS Code")
            .define("show_sync_notifications", true);
        b.pop();

        SPEC = b.build();
    }

    private CideClientConfig() {}
}
