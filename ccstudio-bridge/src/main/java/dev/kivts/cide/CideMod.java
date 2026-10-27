package dev.kivts.cide;

import dev.kivts.cide.bridge.WebSocketBridge;
import dev.kivts.cide.config.CideClientConfig;
import dev.kivts.cide.config.CideServerConfig;
import dev.kivts.cide.net.CidePackets;
import dev.kivts.cide.net.payload.OpenIdePayload;
import dev.kivts.cide.server.CideAccess;
import dev.kivts.cide.server.CideComputerLock;
import dev.kivts.cide.server.CideConsoleService;
import dev.kivts.cide.server.CideFileService;
import dev.kivts.cide.server.CideLuaCatalog;
import dev.kivts.cide.server.CideRateLimiter;
import dev.kivts.cide.server.CideSessionService;
import dev.kivts.cide.server.CideWikiSync;
import dan200.computercraft.shared.computer.blocks.AbstractComputerBlockEntity;
import dan200.computercraft.shared.computer.core.ComputerFamily;
import dan200.computercraft.shared.computer.core.ServerComputer;
import dan200.computercraft.shared.pocket.core.PocketBrain;
import dan200.computercraft.shared.pocket.core.PocketHolder;
import dan200.computercraft.shared.pocket.items.PocketComputerItem;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;


//WELCOME TO TEND WOUNDS GENTLEMEN, UNFORTUNATELY YOUR CHANCES OF REVIVAL ARE LOW,
//PFCS MAY EVEN TANTRUM AGAINST YOUR MEDICS AS 400 BRUTE CORPSES, BUT YOU HAVE MY WORD THAT I
//WILL USE MY SURGERY 1 SKILLS TO ENSURE YOUR BODIES ARE SUB-200 DAMAGE. THIS IS THE GREATEST
//SURGERY, EVEN MORE THAN LARVA REMOVAL, FOR THE FATE OF YOUR DEFIB IS A 5 MINUTE CONCERN.
//NOW COME, RETURN TO CORPSE, STRIKE DOWN THE OB DAMAGE THAT BEAT AGAINST YOU, ALLOW US TO TREAT
//YOU TO 200 DAMAGE. I ASK NOT FOR MY OWN MEDIC EGOSTROKING, BUT FOR THE GOOD OF THE ROUND.

@Mod(CideMod.MOD_ID)
public final class CideMod {
    public static final String MOD_ID = "ccstudio";
    public static final Logger LOGGER = LoggerFactory.getLogger("CC-Studio Bridge");
    private static final int ADMIN_COMPUTER_ID = -1; //dont change this

    public CideMod() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModContainer modContainer = FMLJavaModLoadingContext.get().getContainer();
        // The mod runs on both sides: the CIDE services below are server-side, the editor UI
        // and the ccLink bridge are client-side. The network channel and the server handlers
        // MUST also register on a dedicated server - skipping them there left the channel
        // unregistered server-side, so the client's channel handshake rejected every join with
        // "the server is missing the following mods". Only the client config and the client
        // bridge lifecycle hook are guarded by dist below.

        modContainer.addConfig(new ModConfig(ModConfig.Type.SERVER, CideServerConfig.SPEC, modContainer));
        if (FMLEnvironment.dist == Dist.CLIENT) {
            modContainer.addConfig(new ModConfig(ModConfig.Type.CLIENT, CideClientConfig.SPEC, modContainer));
        }

        CidePackets.register();
        modBus.addListener((net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent e) -> CideLuaCatalog.prewarm());

        // Block computers (Turtles are enemy propaganda)
        MinecraftForge.EVENT_BUS.addListener((PlayerInteractEvent.RightClickBlock e) -> {
            if (e.getLevel().isClientSide()) return;
            // RightClickBlock fires once per hand — only handle the main hand so we don't
            // open the IDE (and resend the session) twice for a single empty-handed click and cause very spooky bugs with networking to occur
            if (e.getHand() != InteractionHand.MAIN_HAND) return;
            if (!CideServerConfig.ENABLED.get()) return;
            if (!e.getEntity().isShiftKeyDown()) return;
            ItemStack held = e.getEntity().getItemInHand(e.getHand());
            if (!held.isEmpty()) return;
            if (!(e.getLevel().getBlockEntity(e.getPos()) instanceof AbstractComputerBlockEntity computer)) return;

            ComputerFamily family = computer.getFamily();
            if (family == ComputerFamily.COMMAND && !CideServerConfig.ALLOW_COMMAND_COMPUTERS.get()) return;

            // This is such a great editor basic PCs dont deserve it, am I right?
            //anyway if you think otherwise its config for a reason
            if (family == ComputerFamily.NORMAL && !CideServerConfig.BASIC_EDITOR.get()) return;

            if (!(e.getEntity() instanceof ServerPlayer player)) return;
            if (!CidePackets.hasChannel(player)) return; // client without the mod: keep the vanilla computer GUI

            CideAccess.Target target = CideAccess.resolve(player, computer);
            if (!target.allowed()) {
                CidePackets.sendDenied(player, target.reason());
                e.setCanceled(true);
                return;
            }
            String lockDenied = CideComputerLock.validate(player, target.computerId());
            if (!lockDenied.isEmpty()) {
                CidePackets.sendDenied(player, lockDenied);
                e.setCanceled(true);
                return;
            }
            CidePackets.sendToPlayer(player, new OpenIdePayload(
                e.getPos(), target.computerId(),
                target.label() == null ? "" : target.label(),
                target.writesEnabled(),
                CideComputerLock.lockedToPlayer(player, target.computerId()),
                CideConsoleService.openSession(player, e.getPos(), target.computerId()),
                false,
                computerTypeName(family, false)
            ));
            CideLuaCatalog.sendTo(player);
            CideSessionService.send(player, target.computerId());
            e.setCanceled(true);
        });

        // Shift right click air to open a pocket pc
        MinecraftForge.EVENT_BUS.addListener((PlayerInteractEvent.RightClickItem e) -> {
            if (!(e.getLevel() instanceof ServerLevel serverLevel)) return;
            if (e.getHand() != InteractionHand.MAIN_HAND) return;
            if (!CideServerConfig.ENABLED.get()) return;
            if (!CideServerConfig.TABLET_EDITOR.get()) return;
            if (!e.getEntity().isShiftKeyDown()) return;
            ItemStack stack = e.getItemStack();
            if (!(stack.getItem() instanceof PocketComputerItem pocketItem)) return;
            if (!(e.getEntity() instanceof ServerPlayer player)) return;
            if (!CidePackets.hasChannel(player)) return; // client without the mod: keep vanilla behaviour

            
            if (pocketItem.getFamily() == ComputerFamily.NORMAL && !CideServerConfig.BASIC_EDITOR.get()) return;

            final ServerComputer computer;
            try {
                PocketHolder holder = new PocketHolder.PlayerHolder(player, player.getInventory().selected);
                PocketBrain brain = pocketItem.getOrCreateBrain(holder, stack);
                computer = brain.computer();
            } catch (RuntimeException | LinkageError ex) {
                CidePackets.sendDenied(player, "could not open pocket computer");
                e.setCanceled(true);
                return;
            }
            if (computer == null) {
                CidePackets.sendDenied(player, "could not open pocket computer");
                e.setCanceled(true);
                return;
            }

            String denied = CideAccess.resolvePocket(player);
            if (denied.isEmpty()) denied = CideComputerLock.validate(player, computer.getID());
            if (!denied.isEmpty()) {
                CidePackets.sendDenied(player, denied);
                e.setCanceled(true);
                return;
            }

            CidePackets.sendToPlayer(player, new OpenIdePayload(
                BlockPos.ZERO, // sentinel: no block position for pocket computers
                computer.getID(),
                computer.getLabel() == null ? "" : computer.getLabel(),
                CideServerConfig.ENABLE_WRITES.get(),
                CideComputerLock.lockedToPlayer(player, computer.getID()),
                CideConsoleService.openSession(player, BlockPos.ZERO, computer.getID()),
                false,
                computerTypeName(pocketItem.getFamily(), true)
            ));
            CideLuaCatalog.sendTo(player);
            CideSessionService.send(player, computer.getID());
            e.setCanceled(true);
            
        });

        MinecraftForge.EVENT_BUS.addListener((RegisterCommandsEvent e) -> {
            e.getDispatcher().register(Commands.literal("cide")
                .then(Commands.literal("admin")
                    .requires(source -> source.hasPermission(2))
                    .executes(ctx -> {
                        ServerPlayer player = ctx.getSource().getPlayerOrException();
                        if (!CidePackets.hasChannel(player)) return 0; // client without the mod cannot open the IDE
                        if (!CideServerConfig.ENABLED.get()) {
                            CidePackets.sendDenied(player, "CIDE is disabled");
                            return 0;
                        }
                        CidePackets.sendToPlayer(player, new OpenIdePayload(
                            BlockPos.ZERO,
                            ADMIN_COMPUTER_ID,
                            "Admin",
                            CideServerConfig.ENABLE_WRITES.get(),
                            false,
                            UUID.randomUUID(),
                            true,
                            "Admin"
                        ));
                        CideLuaCatalog.sendTo(player);
                        return 1;
                    })));
        });

        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> {
            UUID uuid = e.getEntity().getUUID();
            CideRateLimiter.evict(uuid);
            CideFileService.evictUpload(uuid);
            CideConsoleService.close(uuid);
        });

        // Collect datapack-contributed wiki/autocomplete entries on each datapack reload.
        MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.AddReloadListenerEvent e) -> {
            e.addListener(CideWikiSync.RELOAD_LISTENER);
            e.addListener(CideLuaCatalog.RELOAD_LISTENER);
        });

        // Sync the collected datapack wiki blob to each player when they join.
        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer player) CideWikiSync.send(player);
        });

        // ccLink WebSocket bridge lifecycle (spec phase 2): the bridge connects
        // when a computer's IDE is opened (CcLinkSync.onScreenActivated, when
        // [general] auto_start_server is enabled) and disconnects on logout and
        // client shutdown. It no longer connects on world join - an idle
        // connection without an open computer would just create a meaningless
        // "CC-Studio Bridge" entry in the ccLink tree. Client-only: the bridge never runs
        // on a dedicated server, and the event class itself is a client class.
        if (FMLEnvironment.dist == Dist.CLIENT) {
            MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingOut e) -> {
                WebSocketBridge.stop();
            });
            // No client-stopped hook in this Forge build: the dialer thread is a
            // daemon, so it dies with the JVM.
        }
    }

    /** Human-readable computer type for IDE titles and the ccLink tree entry name. */
    private static String computerTypeName(ComputerFamily family, boolean pocket) {
        if (pocket) {
            return family == ComputerFamily.ADVANCED ? "Advanced Pocket Computer" : "Pocket Computer";
        }
        return switch (family) {
            case ADVANCED -> "Advanced Computer";
            case COMMAND -> "Command Computer";
            default -> "Computer";
        };
    }

    public static ResourceLocation id(String path) {
        return new ResourceLocation(MOD_ID, path);
    }
}
