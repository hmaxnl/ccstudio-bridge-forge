# ComputerCraft Link

ComputerCraft Link (ccLink for short) is a Visual Studio Code extension that allows you to connect to [computers in Minecraft](https://tweaked.cc) through websockets. This extension allows you to do your development in VS Code and immediately have your changes uploaded to your computer in-game. It also features a remote terminal feature so you can interact with the shell from VS Code.

When paired with the [Lua Language Server](https://luals.github.io) and the [type definitions for CC:Tweaked](https://gitlab.com/carsakiller/cc-tweaked-documentation), ccLink helps greatly improve the development cycle.

![ccLink extension in VS Code](https://gitlab.com/computercraft-link/craftos-client/uploads/5fb6d2989bbb1ff0be9d410802843234/image.png)

## Features

🚧 This extension is currently under development!

- 🔍 Watch local files and send them to computers
- 📩 Download remote files to your machine
- 📝 Edit remote files
- 💾 Interact with remote file system
- 📡 Use the shell remotely

## Requirements

This extension requires the [client script][client] to be running on the target computer in order to function.

## Extension Settings

- `ccLink.websocket.port`: The TCP port to use for making websocket connections.

  - Type: `integer`
  - Default: `56560`
  - Maximum: `65535`
  - Minimum: `1`

- `ccLink.installScript.host`: The [host](https://developer.mozilla.org/en-US/docs/Web/API/URL/host) that the client script should try connecting to. If no port is given, the port from `ccLink.websocket.port` will be used.

  - Type: `string`
  - Default: `127.0.0.1:56560`

- `ccLink.workspace.root`: Defines the root of the workspace for file watching and processing. For example, if set to `src/`, only files inside `src/` will match your glob patterns. When files are sent to the client, the `src/` prefix is removed, transforming `workspaceFolder/src/main.lua` into `workspaceFolder/main.lua`. When used with `ccLink.computer.root`, paths can be fully transformed for the client.

  - Type: `string`
  - Default: `./`

- `ccLink.workspace.globalGlobs`: Glob patterns that all computers will use.

  - Type: `string[]`

- `ccLink.computer.root`: Defines the target root directory on the client where files will be sent. Defaults to the name of the current workspace. For example, if set to `myProject/libs/text/`, a file at `workspaceFolder/src/main.lua` will be placed at `myProject/libs/text/src/main.lua` on the client. When used with `ccLink.workspace.root`, paths can be fully transformed for the client.

  - Type: `string`

- `ccLink.computer.timeout`: Number of seconds before a message will timeout while waiting for a response from the client.

  - Type: `number`
  - Default: `5`

[client]: https://gitlab.com/computercraft-link/craftos-client
