import * as vscode from "vscode";

export type TreeProvider = vscode.TreeDataProvider<TreeNode> & {
  update: (node?: TreeNode) => void;
};

export type TreeNodeOptions = Omit<vscode.TreeItem, "label">;

const getTreeProvider = (treeNode: TreeNode): TreeProvider => {
  if (treeNode.parent === null) {
    throw new Error("tree node parent is null");
  } else if (treeNode.parent instanceof TreeNode) {
    return getTreeProvider(treeNode.parent);
  }

  return treeNode.parent;
};

export class TreeNode extends vscode.TreeItem {
  computerUUID: string;
  parent: TreeNode | TreeProvider | null;
  children: Map<string, TreeNode>;

  constructor(
    title: string,
    computerUUID: string,
    parent: TreeNode | TreeProvider | null,
    options?: TreeNodeOptions
  ) {
    super(
      title,
      options?.collapsibleState ?? vscode.TreeItemCollapsibleState.None
    );

    this.computerUUID = computerUUID;
    this.parent = parent;
    this.children = new Map();

    Object.assign(this, options);
  }

  update(treeNode?: TreeNode) {
    const treeProvider = getTreeProvider(this);
    treeProvider.update(treeNode ?? this);
  }

  /** Add a child node to this tree node */
  addChild(node: TreeNode) {
    node.parent = this;
    this.children.set(node.label as string, node);
  }

  /** Remove a child node from this tree node */
  removeChild(node: TreeNode) {
    const removedNode = this.children.get(node.label as string);
    this.children.delete(node.label as string);
    return removedNode ?? false;
  }

  /** Move a child node under another node */
  moveChild(node: TreeNode, destination: TreeNode) {
    const child = this.removeChild(node);
    if (!child) {
      return false;
    }
    child.parent = destination;
    destination.addChild(child);
  }

  /** Optional custom method for getting an array of child nodes for displaying in the tree. This allows custom handling, for example, sorting of children. */
  getChildren?(): TreeNode[];
}
