import * as vscode from "vscode";
import logger from "@/logging/logger";
import { ComputerTreeProvider } from "../../views/ComputerTree/ComputerTreeProvider";
import { ComputerTreeNode } from "../../views/ComputerTree/items/ComputerNode";
import { ComputerManager } from "../../classes/ComputerManager";
import path from "path";
import { TreeNode } from "../../views/ComputerTree/items/TreeNode";
import { HttpFileServer } from "@/classes/HttpFileServer";

export const register = (
  _: vscode.ExtensionContext,
  tree: ComputerTreeProvider,
  computerManager: ComputerManager,
  fileServer: HttpFileServer
) => {
  vscode.commands.registerCommand("ccLink.addComputer", () =>
    computerManager.addComputer(tree, fileServer)
  );

  vscode.commands.registerCommand(
    "ccLink.removeComputer",
    async (treeItem: ComputerTreeNode) => {
      if (treeItem instanceof ComputerTreeNode) {
        const response = await vscode.window.showWarningMessage(
          `Are you sure you want to delete ${treeItem.label}?`,
          { modal: true },
          "Yes",
          "No"
        );
        if (response !== "Yes") return;
        computerManager.removeComputer(tree, treeItem.id as string);
      } else {
        const quickPickItems: vscode.QuickPickItem[] = computerManager
          .getComputers()
          .map((computer) => computer.asQuickPickItem());

        const result = await vscode.window.showQuickPick(quickPickItems, {
          canPickMany: true,
          matchOnDescription: true,
          title: "Remove computer(s)",
        });

        if (!result || result.length === 0) {
          logger.debug("No computers were selected for deletion");
          return;
        }

        for (const pick of result) {
          computerManager.removeComputer(tree, pick.description as string);
        }
      }
    }
  );

  vscode.commands.registerCommand(
    "ccLink.copyUUID",
    (treeItem: ComputerTreeNode) => {
      vscode.env.clipboard.writeText(treeItem.computerUUID);
      vscode.window.showInformationMessage("Copied UUID to clipboard");
    }
  );

  vscode.commands.registerCommand(
    "ccLink.reboot",
    (treeItem: ComputerTreeNode) => {
      const computer = computerManager.requireComputer(treeItem.computerUUID);
      computer.sendCommand("restart", undefined);
    }
  );

  vscode.commands.registerCommand(
    "ccLink.refreshFileList",
    async (treeItem?: TreeNode) => {
      const computers = treeItem
        ? [computerManager.requireComputer(treeItem.computerUUID)]
        : computerManager.getComputers();

      await Promise.all(
        computers.map((computer) =>
          computer.updateRemoteFileList().catch((e) =>
            logger.error(`Failed to refresh file list for ${computer.name}: ${e}`)
          )
        )
      );
    }
  );

  vscode.commands.registerCommand(
    "ccLink.disconnectComputer",
    async (treeItem: ComputerTreeNode) => {
      const computer = computerManager.requireComputer(treeItem.id as string);
      computer.disconnect(1000, "DISCONNECT_AND_NOT_RECONNECT");
    }
  );

  vscode.commands.registerCommand(
    "ccLink.addGlobPattern",
    async (treeItem: TreeNode) => {
      const pattern = await vscode.window.showInputBox({
        title: "Add glob pattern for watching",
      });

      if (!pattern) return;

      const computer = computerManager.requireComputer(treeItem.computerUUID);
      computer.addWatchGlobPattern(pattern);
    }
  );

  vscode.commands.registerCommand(
    "ccLink.removeGlobPattern",
    (treeItem: TreeNode) => {
      if (treeItem.contextValue === "globalGlobPattern") {
        const config = vscode.workspace.getConfiguration("ccLink.workspace");
        const globs = config.get<string[]>("globalGlobs", []);

        const index = globs.indexOf(treeItem.label as string);
        globs.splice(index, 1);
        config.update("globalGlobs", globs);
      } else {
        const computer = computerManager.requireComputer(treeItem.computerUUID);
        computer.removeWatchGlobPattern(treeItem.label as string);
      }
    }
  );

  vscode.commands.registerCommand(
    "ccLink.resumeWatching",
    async (treeItem: TreeNode) => {
      const computer = computerManager.requireComputer(treeItem.computerUUID);

      computer.watching = true;
    }
  );

  vscode.commands.registerCommand(
    "ccLink.pauseWatching",
    async (treeItem: TreeNode) => {
      const computer = computerManager.requireComputer(treeItem.computerUUID);

      computer.watching = false;
    }
  );

  vscode.commands.registerCommand(
    "ccLink.createFile",
    async (treeItem: TreeNode) => {
      const parentUri = treeItem.resourceUri as vscode.Uri;

      const filename = await vscode.window.showInputBox({
        title: "Enter the name of the file to create",
      });

      if (!filename) {
        vscode.window.showWarningMessage(
          "Aborting create file operation as no filename was provided"
        );
        return;
      }

      const computer = computerManager.requireComputer(treeItem.computerUUID);

      if (!computer.connection) {
        throw new Error("computer is not connected");
      }

      const targetURI = vscode.Uri.joinPath(parentUri, filename);
      await computer.sendCommand("writeFile", {
        path: targetURI.path,
        content: "",
        create: true,
      });
      vscode.commands.executeCommand("vscode.open", targetURI);
    }
  );

  vscode.commands.registerCommand(
    "ccLink.createFolder",
    async (treeItem: TreeNode) => {
      const uri = treeItem.resourceUri as vscode.Uri;

      const filename = await vscode.window.showInputBox({
        title: "Enter the name of the folder to create",
      });

      if (!filename) {
        vscode.window.showWarningMessage(
          "Aborting create folder operation as no name was provided"
        );
        return;
      }

      const folderURI = vscode.Uri.joinPath(uri, filename);
      const computer = computerManager.requireComputer(treeItem.computerUUID);

      if (!computer.connection) {
        throw new Error("computer is not connected");
      }

      await computer.sendCommand("createDirectory", {
        path: folderURI.path,
      });
    }
  );

  vscode.commands.registerCommand(
    "ccLink.deleteFile",
    async (treeItem: TreeNode, selected: TreeNode[]) => {
      const closeFileIfOpen = (file: vscode.Uri) => {
        const tabs = vscode.window.tabGroups.all.flatMap(
          (tabGroup) => tabGroup.tabs
        );
        const tab = tabs.find(
          (tab) =>
            tab.input instanceof vscode.TabInputText &&
            tab.input.uri.toString() === file.toString()
        );
        if (tab) {
          return vscode.window.tabGroups.close(tab);
        }
      };

      const uris: vscode.Uri[] = [];

      if (!Array.isArray(selected)) {
        const uri = treeItem.resourceUri as vscode.Uri;
        const result = await vscode.window.showInformationMessage(
          `Are you sure you want to delete ${uri.path}?`,
          { modal: true, detail: "This cannot be undone!" },
          "Yes"
        );
        if (result === "Yes") {
          uris.push(uri);
        }
      } else {
        const result = await vscode.window.showInformationMessage(
          "Are you sure you want to delete the selected files?",
          { modal: true, detail: "This cannot be undone!" },
          "Yes"
        );
        if (result === "Yes") {
          selected.forEach((item) => {
            if (item.resourceUri) {
              uris.push(item.resourceUri);
            }
          });
        }
      }

      await Promise.all(
        uris.map(async (uri) => {
          const computer = computerManager.requireComputer(uri.authority);

          try {
            await computer.sendCommand("delete", {
              path: uri.path,
              recursive: true,
            });
          } catch (e) {
            computer.logger.error(`failed to delete file ${e}`);
          }

          try {
            await closeFileIfOpen(uri);
          } catch (e) {
            logger.error(`failed to close file ${e}`);
          }
        })
      );
    }
  );

  vscode.commands.registerCommand(
    "ccLink.downloadFile",
    async (item: TreeNode | vscode.Uri) => {
      let uri: vscode.Uri | undefined;

      if (item instanceof TreeNode) {
        uri = item.resourceUri;
      } else {
        uri = item;
      }

      if (uri?.scheme !== "ccLink") {
        throw new Error(`unsupported file scheme: ${uri?.scheme}`);
      }

      let defaultUri = vscode.workspace.workspaceFolders?.[0].uri;
      if (defaultUri) {
        const filename = path.basename(uri.path);
        defaultUri = vscode.Uri.joinPath(defaultUri, filename);
      }

      const saveLocation = await vscode.window.showSaveDialog({
        defaultUri: defaultUri,
        saveLabel: "Download",
      });

      if (!saveLocation) return;

      return vscode.workspace.fs.writeFile(
        saveLocation,
        await vscode.workspace.fs.readFile(uri)
      );
    }
  );

  vscode.commands.registerCommand(
    "ccLink.renameFile",
    async (item: TreeNode) => {
      const uri = item.resourceUri;

      if (!uri) {
        throw new Error("file uri not present");
      }

      const currentFilename = path.basename(uri.path);

      let selectionEnd = currentFilename.lastIndexOf(".");
      if (selectionEnd === -1) {
        selectionEnd = currentFilename.length;
      }
      const target = await vscode.window.showInputBox({
        title: "Rename file",
        value: currentFilename,
        valueSelection: [0, selectionEnd],
      });

      if (!target) {
        vscode.window.showWarningMessage(
          "Aborting file rename as a new name was not provided"
        );
        return;
      }

      await vscode.workspace.fs.rename(
        uri,
        vscode.Uri.joinPath(uri, "..", target)
      );
    }
  );

  vscode.commands.registerCommand(
    "ccLink.createComputerShell",
    async (item: ComputerTreeNode) => {
      const computer = computerManager.requireComputer(item.computerUUID);
      await computer.createRemoteShell();
    }
  );
};
