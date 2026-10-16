import * as vscode from "vscode";
import logger, { ccLogger } from "@/logging/logger";
import { ComputerManager } from "@/classes/ComputerManager";
import { TreeNode, TreeProvider } from "./items/TreeNode";

export class ComputerTreeProvider implements TreeProvider {
  private computerManager: ComputerManager;
  private logger: ccLogger;

  private _onDidChangeTreeData: vscode.EventEmitter<TreeNode | undefined>;
  readonly onDidChangeTreeData: vscode.Event<TreeNode | undefined>;

  constructor(computerManager: ComputerManager) {
    this.computerManager = computerManager;
    this.logger = logger.child({ label: "ComputerTree" });

    this._onDidChangeTreeData = new vscode.EventEmitter();
    this.onDidChangeTreeData = this._onDidChangeTreeData.event;
  }

  update(treeItem?: TreeNode) {
    this._onDidChangeTreeData.fire(treeItem);

    if (treeItem) {
      this.logger.debug(
        `Refreshed TreeItem: ${treeItem.label}(${treeItem.id})`
      );
    } else {
      this.logger.debug("Refreshed entire computer tree");
    }
  }

  getTreeItem(element: TreeNode): TreeNode {
    return element;
  }

  getChildren(element?: TreeNode): TreeNode[] {
    if (element === undefined) {
      const computers = this.computerManager.getComputers();
      return computers.map((computer) => computer.treeItem);
    }
    if (element.getChildren) {
      return element.getChildren();
    } else {
      return Array.from(element.children.values());
    }
  }

  getParent(element: TreeNode): vscode.ProviderResult<TreeNode> {
    if (element.parent instanceof TreeNode) {
      return element.parent;
    }
    return null;
  }
}
