import * as vscode from "vscode";
import logger from "./logging/logger";
import * as ComputerTree from "./views/ComputerTree/ComputerTreeProvider";
import * as commands from "./commands/server/vscode";
import { ComputerManager } from "./classes/ComputerManager";
import { RemoteFileSystemProvider } from "./classes/RemoteFileSystemProvider";
import { FileWatcher } from "./classes/FileWatcher";
import { HttpFileServer } from "./classes/HttpFileServer";
import { WebsocketManager } from "./classes/WebsocketManager";

export async function activate(context: vscode.ExtensionContext) {
  try {
    logger.info(
      `=================== ccLink ${context.extension.packageJSON.version} ===================`
    );

    const fileWatcher = new FileWatcher("**");
    context.subscriptions.push({ dispose: fileWatcher.dispose });

    const computerManager = new ComputerManager(fileWatcher);
    const computerTreeProvider = new ComputerTree.ComputerTreeProvider(
      computerManager
    );

    const computerTreeView = vscode.window.createTreeView("ccLinkComputers", {
      treeDataProvider: computerTreeProvider,
      showCollapseAll: true,
      canSelectMany: true,
    });
    context.subscriptions.push(computerTreeView);
    await computerManager.initialize(computerTreeProvider);

    const remoteFileSystemProvider = new RemoteFileSystemProvider(
      computerManager
    );
    context.subscriptions.push(
      vscode.workspace.registerFileSystemProvider(
        "ccLink",
        remoteFileSystemProvider
      )
    );

    const config = vscode.workspace.getConfiguration("ccLink");
    const port = config.get("websocket.port", 56560);

    const fileServer = new HttpFileServer(port);
    const websocketManager = new WebsocketManager(
      fileServer.server,
      computerManager
    );

    context.subscriptions.push(fileServer, websocketManager);

    commands.register(
      context,
      computerTreeProvider,
      computerManager,
      fileServer
    );

    logger.info("Started ccLink!");
  } catch (e) {
    logger.error("Failed to initialize ccLink", e);
  }
}
