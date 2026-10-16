import * as vscode from "vscode";
import * as path from "path";
import { TreeNode, TreeProvider } from "./TreeNode";
import { Computer, COMPUTER_STATES } from "@/classes/Computer";

const iconsDir = path.join(__filename, "..", "..", "assets", "icons");

const stateIconPaths: Record<COMPUTER_STATES, string> = {
  [COMPUTER_STATES.CONNECTED]: path.join(iconsDir, "connected.png"),
  [COMPUTER_STATES.DISCONNECTED]: path.join(iconsDir, "disconnected.png"),
  [COMPUTER_STATES.RUNNING]: path.join(iconsDir, "running.png"),
  [COMPUTER_STATES.ERROR]: path.join(iconsDir, "error.png"),
  [COMPUTER_STATES.WARNING]: path.join(iconsDir, "warning.png"),
  [COMPUTER_STATES.ACTION_REQUIRED]: path.join(iconsDir, "action_required.png"),
};

const remoteFilesIconPath = path.join(iconsDir, "disk.png");

const appendRemoteFileNode = (
  uri: vscode.Uri,
  computerID: string,
  parent: TreeNode
) => {
  const basename = path.basename(uri.path);

  const node = new TreeNode(basename, computerID, parent);
  node.collapsibleState = vscode.TreeItemCollapsibleState.None;
  node.resourceUri = uri;
  node.contextValue = "remote_file";
  node.command = {
    command: "vscode.open",
    title: "Open File",
    arguments: [uri],
    tooltip: uri.path,
  };

  parent.addChild(node);
};

const appendRemoteFolderNode = (
  uri: vscode.Uri,
  computerID: string,
  parent: TreeNode
) => {
  const basename = path.basename(uri.path);

  const node = new TreeNode(basename, computerID, parent);
  node.collapsibleState = vscode.TreeItemCollapsibleState.Collapsed;
  node.resourceUri = uri;
  node.contextValue = "remote_folder";
  parent.addChild(node);
};

export class ComputerTreeNode extends TreeNode {
  id: string;
  computer: Computer;
  disposables: vscode.Disposable[];

  constructor(treeProvider: TreeProvider, computer: Computer, name: string) {
    super(name, computer.uuid, treeProvider);

    this.computer = computer;
    this.id = computer.uuid;
    this.description = computer.uuid;
    this.collapsibleState = vscode.TreeItemCollapsibleState.Collapsed;
    this.contextValue = "computer";
    this.disposables = [];

    this.setStatus(COMPUTER_STATES.DISCONNECTED);

    computer.onGlobChange(
      () => {
        this.updateWatchGlobTreeNode(computer.watchGlobPatterns);
        this.update(this);
      },
      this,
      this.disposables
    );
    computer.onConnected(
      () => {
        this.setStatus(COMPUTER_STATES.CONNECTED);
        this.contextValue = "connected_computer";
      },
      this,
      this.disposables
    );
    computer.onDisconnected(
      () => {
        this.setStatus(COMPUTER_STATES.DISCONNECTED);
        this.contextValue = "computer";
      },
      this,
      this.disposables
    );

    vscode.workspace.onDidChangeConfiguration(
      (e) => {
        if (!e.affectsConfiguration("ccLink.workspace.globalGlobs")) return;
        this.updateWatchGlobTreeNode(this.computer.watchGlobPatterns);
        this.update(this);
      },
      this,
      this.disposables
    );

    this.updateWatchGlobTreeNode(computer.watchGlobPatterns);
    this.updateRemoteFileTreeNode(computer.remoteFiles);
  }

  dispose() {
    while (this.disposables.length > 0) {
      this.disposables.pop()?.dispose();
    }
  }

  update(treeNode?: TreeNode): void {
    const globTreeNode = this.children.get("watch_glob_tree");
    if (globTreeNode) {
      globTreeNode.contextValue = this.computer.watching
        ? "watching_glob_tree"
        : "paused_glob_tree";
    }
    super.update(treeNode);
  }

  private updateWatchGlobTreeNode(patterns: string[]) {
    this.children.set("watch_glob_tree", this.getGlobPatternsNode(patterns));
  }

  updateRemoteFileTreeNode(remoteFiles: vscode.Uri[]) {
    this.children.set("remote_tree", this.getRemoteFileTreeNode(remoteFiles));
  }

  private getGlobPatternsNode(patterns: string[]) {
    const globalPatterns = vscode.workspace
      .getConfiguration("ccLink.workspace")
      .get<string[]>("globalGlobs", []);

    const tree = new TreeNode("Watch Glob Patterns", this.id as string, this, {
      iconPath: new vscode.ThemeIcon("eye"),
      contextValue: this.computer.watching
        ? "watching_glob_tree"
        : "paused_glob_tree",
      collapsibleState:
        patterns.length > 0 || globalPatterns.length > 0
          ? vscode.TreeItemCollapsibleState.Collapsed
          : vscode.TreeItemCollapsibleState.None,
    });

    for (const p of globalPatterns) {
      const node = new TreeNode(p, this.computer.uuid, tree);
      node.contextValue = "globalGlobPattern";
      node.iconPath = new vscode.ThemeIcon("globe");
      tree.addChild(node);
    }

    for (const p of patterns) {
      const node = new TreeNode(p, this.computer.uuid, tree);
      node.contextValue = "globPattern";
      tree.addChild(node);
    }

    return tree;
  }

  private getRemoteFileTreeNode(files: vscode.Uri[]) {
    const createRemoteURI = (path: string) => {
      return vscode.Uri.from({
        scheme: "ccLink",
        authority: this.id as string,
        path: `/${path}`,
      });
    };

    const tree = new TreeNode("Files", this.id as string, this, {
      iconPath: remoteFilesIconPath,
      contextValue: this.computer.isConnected
        ? "connected_remote_file_tree"
        : "remote_file_tree",
      collapsibleState:
        files.length > 0
          ? vscode.TreeItemCollapsibleState.Collapsed
          : vscode.TreeItemCollapsibleState.None,
      resourceUri: createRemoteURI("/"),
    });

    // Function for sorting remote files
    tree.getChildren = () => {
      const arr = Array.from(tree.children.values());

      arr.sort((a, b) => {
        let sum = 0;

        const aLabel = a.label as string;
        const bLabel = b.label as string;

        sum += a.children.size > 0 ? -2 : 2;
        sum += aLabel.localeCompare(bLabel);

        return sum;
      });

      return arr;
    };

    for (const file of files) {
      /** Path of the file without leading root slash */
      const rootPath = file.path.substring(1);
      const filepath = path.parse(rootPath);
      const segments = rootPath.split("/");

      if (filepath.dir === "") {
        appendRemoteFileNode(file, this.id, tree);
        continue;
      }

      // Traverse tree to add in parent folders and file
      segments.reduce((parentNode, folder, index) => {
        const pathToHere = segments.slice(0, index + 1).join("/");
        const uri = createRemoteURI(pathToHere);

        if (index === segments.length - 1) {
          appendRemoteFileNode(uri, this.id, parentNode);
        } else {
          if (!parentNode.children.has(folder)) {
            appendRemoteFolderNode(uri, this.id, parentNode);
          }
        }

        return parentNode.children.get(folder) as TreeNode;
      }, tree);
    }

    return tree;
  }

  setStatus(status: COMPUTER_STATES) {
    this.iconPath = stateIconPaths[status];
    this.update();
  }
}
