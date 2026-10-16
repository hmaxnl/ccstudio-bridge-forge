# CC-Studio Bridge

A Minecraft **Forge 1.20.1** mod that combines the [CIDE-CC](https://github.com/kiVts/CIDE) in-game editor for
[CC:Tweaked](https://github.com/CCTweaked/computercraft) with a local WebSocket bridge that plugs directly into the
[ccLink](https://gitlab.com/computercraft-link/vs-code-extension) **VS Code extension** — no HTTP server, no ports
forwarding, no client script to install on the computer.

Edit and run Lua on your in-game computers from the comfort of VS Code, or stay fully in-game with the modern editor UI.

## Features

**In-game editor (CIDE-CC, backported from NeoForge 1.21.1 to Forge 1.20.1)**

- Modern tabbed editor with Lua syntax highlighting, line numbers and a file/folder tree
- Context-aware autocomplete, jump-to-definition, built-in CC wiki
- Collapsible log drawer, status bar, opacity control
- Works on block computers (basic/advanced/command, config-gated), pocket computers
  (shift-click with the pocket in hand) and a server-wide admin view (`/cide`)

**VS Code bridge (ccLink protocol)**

- The mod dials `ws://127.0.0.1:56560` (configurable) and pairs with the ccLink extension automatically:
  each computer gets a **deterministic, stable identity**, so every placed computer keeps its own entry
  in the ccLink computer tree across restarts
- Edit, create, rename, delete, download and list files on the computer from VS Code
  (full remote file-system provider)
- Remote **terminal**: the computer shell is streamed into an xterm tab with truecolor ANSI rendering
- File watching with live change notifications, computer reboot, session locking
  (RPCs are rejected while the in-game IDE is closed)

## Architecture

```
 [VS CODE WORKSPACE]
   ccLink extension — WebSocket SERVER (ws://127.0.0.1:56560)
             │
             │  ccLink JSON-RPC (writeFile / readFile / readyShell / keyToShell / watch …)
             ▼
 ┌──────────────────────────────────────────────────────────────┐
 │ CC-Studio Bridge (client mod)                                │
 │  CIDE in-game editor UI  ◄──►  WebSocket client + ccLink RPC │
 │                      CcLinkSync (bridge core)                │
 └──────────────────────────┬───────────────────────────────────┘
                            │  Forge network channel (CidePackets, v14)
                            ▼
                  In-game CC:Tweaked server
                  (file/console/session/stub services, session lock,
                   path guards, rate limiting)
```

- **The extension is the WebSocket server; the mod is the client.** Opening a computer's IDE in-game
  starts the bridge (if `auto_start_server` is on); it stops on world logout or client shutdown.
- All ccLink RPCs are interpreted in `CcLinkSync` and executed through the in-game CIDE protocol
  (`CidePackets` + `dev.kivts.cide.server.*` services on the server side).
- Shells are polled every 25 ticks and rendered as ANSI diffs with per-cell truecolor SGR sequences,
  matching the in-game console colors.

## Requirements

| Component      | Version                          |
|----------------|----------------------------------|
| Minecraft      | 1.20.1 (Forge)                   |
| Forge          | 47.4.x (`[47,)`)                 |
| CC:Tweaked     | 1.120.x (`[1.116.0,)`)           |
| JDK (build)    | 21                               |
| Node.js (ext.) | 20+ (builds the extension)       |
| VS Code (ext.) | 1.96+                            |

## Building

### The mod

```bash
cd ccstudio-bridge
./gradlew build
```

Output: `ccstudio-bridge/build/libs/ccstudio-0.1.0.jar` — drop it into the `mods/` folder of a
1.20.1 Forge instance that has CC:Tweaked installed. (The first build downloads ForgeGradle caches and
takes a while; subsequent builds are fast.)

### The VS Code extension

```bash
cd vs-code-extension
npm install
npm run package        # type-check + esbuild bundle + vsce package
```

Output: a `cclink-0.1.0.vsix` in the extension folder. Install it with
`code --install-extension cclink-0.1.0.vsix` or via *Extensions → … → Install from VSIX…*.

> The extension ships preconfigured; the mod and extension pair automatically — no UUID copying.

## Configuration

Client (`ccstudio-client.toml`, in `config/`):

| Key | Default | Description |
|-----|---------|-------------|
| `opacity` | `0.533` | Editor screen background opacity |
| `general.default_port` | `56560` | TCP port the bridge dials (must match `ccLink.websocket.port`) |
| `general.auto_start_server` | `true` | Connect to VS Code automatically when a computer's IDE is opened |
| `general.show_sync_notifications` | `true` | Show notification popups in-game when a file is synced from VS Code |

Server (`ccstudio-server.toml`, in `serverconfig/`):

| Key | Default | Description |
|-----|---------|-------------|
| `access.enabled` | `true` | Master switch for the CIDE/bridge services |
| `access.enableWrites` | `true` | Allow file writes from the IDE/bridge |
| `access.requireOperator` | `false` | Only operators may use the editor/bridge |
| `access.allowCommandComputers` | `false` | Enable the editor on command computers |
| `access.maxUseDistance` | `8.0` | Max distance to open a computer's IDE |
| `computerTypes.basicEditor` | `true` | Editor on normal (basic) PCs; turtles count as normal PCs |
| `computerTypes.tabletEditor` | `true` | Editor on pocket computers (opened by shift-clicking air with the pocket in hand) |
| `limits.maxListedEntries` | `256` | Max files listed per directory |
| `limits.rateLimitRequestsPerSecond` | `200` | Ops/second limit for bridge operations |
| `paths.deniedPathPrefixes` | `["."]` | Disk path prefixes always denied |
| `paths.allowedPathPrefixes` | `[]` | If non-empty, only these prefixes are accessible |

## Using it

1. In VS Code, make sure the ccLink extension is active (its status bar item shows the server state).
2. In Minecraft, shift-right-click a computer (or shift-click the air with a pocket computer in hand)
   to open the IDE. The ccLink bridge connects and the computer appears in the **ccLink** computer tree.
3. Use VS Code like a normal Lua project: files are the computer's disk. Open a terminal
   (*ccLink: Create Computer Shell*) to use the computer shell.

## Project layout

```
ccstudio-bridge/     Forge 1.20.1 mod (Java 21)
  src/main/java/dev/kivts/cide/
    CideMod.java             entry point, right-click / pocket / /cide wiring
    CideIntegration.java     peripheral stub integration
    client/                  CIDE editor UI (screen, Lua parser/lexer/autocomplete, bridge panel)
    bridge/                  WebSocket client + ccLink RPC core (CcLinkSync), logging
    config/                  client & server config specs
    net/                     Forge packet channel + payload records
    server/                  server-side services: files, console, sessions, locks, stubs, wiki sync
vs-code-extension/ ccLink VS Code extension fork (TypeScript)
  src/                       websocket server, computer manager, remote FS provider, terminals
```

## References & credits

- **[CIDE-CC](https://github.com/kiVts/CIDE)** by kiVts — the
  in-game editor this mod is based on, backported from its original NeoForge 1.21.1 target to
  Forge 1.20.1. Also on [Modrinth](https://modrinth.com/mod/cide-cc). (MIT)
- **[ccLink VS Code extension](https://gitlab.com/computercraft-link/vs-code-extension)** by
  carsakiller — the original extension (upstream is archived; this project ships a maintained fork
  with auto-pairing, automatic file-tree refresh and improved error reporting). (MIT)
- **[CC:Tweaked](https://github.com/CCTweaked/computercraft)** — the ComputerCraft: Tweaked mod
  this builds against (v1.120.x for 1.20.1 Forge).
- Recommended companions: [Lua Language Server](https://luals.github.io) and the
  [CC:Tweaked type definitions](https://gitlab.com/carsakiller/cc-tweaked-documentation) for
  full Lua autocompletion in VS Code.

## License

- This project (mod + extension): **MIT**
- CIDE-CC editor code: MIT, © kiVts — see `ccstudio-bridge/src/main/resources/CIDE-CREDIT-LICENSE-MIT.txt`
- The ccLink extension code retains its original MIT license, © carsakiller (see `vs-code-extension/LICENSE`)

Not an official Minecraft service. Not approved by or associated with Mojang or Microsoft.
