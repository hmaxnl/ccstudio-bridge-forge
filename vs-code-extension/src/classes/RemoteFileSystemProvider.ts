import * as vscode from "vscode";
import { ComputerManager } from "./ComputerManager";

export class RemoteFileSystemProvider implements vscode.FileSystemProvider {
  private readonly computerManager: ComputerManager;

  readonly onDidChangeFile: vscode.Event<vscode.FileChangeEvent[]>;

  constructor(computerManager: ComputerManager) {
    this.computerManager = computerManager;

    this.onDidChangeFile = computerManager.onFileChange;
  }

  copy(
    source: vscode.Uri,
    destination: vscode.Uri,
    options: { overwrite: boolean }
  ): Promise<void> {
    const uuid = destination.authority;
    const computer = this.computerManager.requireComputer(uuid);

    return computer.fs.copy(source, destination, options);
  }

  createDirectory(uri: vscode.Uri): Promise<void> {
    const uuid = uri.authority;
    const computer = this.computerManager.requireComputer(uuid);

    return computer.fs.createDirectory(uri);
  }

  delete(
    uri: vscode.Uri,
    options: { readonly recursive: boolean }
  ): Promise<void> {
    const uuid = uri.authority;
    const computer = this.computerManager.requireComputer(uuid);

    return computer.fs.delete(uri, options);
  }

  readDirectory(uri: vscode.Uri): Promise<[string, vscode.FileType][]> {
    const uuid = uri.authority;
    const computer = this.computerManager.requireComputer(uuid);

    return computer.fs.readDirectory(uri);
  }

  readFile(uri: vscode.Uri): Promise<Uint8Array> {
    const uuid = uri.authority;
    const computer = this.computerManager.requireComputer(uuid);

    return computer.fs.readFile(uri);
  }

  rename(
    source: vscode.Uri,
    target: vscode.Uri,
    options: { readonly overwrite: boolean }
  ): Promise<void> {
    const uuid = source.authority;
    const computer = this.computerManager.requireComputer(uuid);

    return computer.fs.rename(source, target, options);
  }

  stat(uri: vscode.Uri): Promise<vscode.FileStat> {
    const uuid = uri.authority;
    const computer = this.computerManager.requireComputer(uuid);

    return computer.fs.stat(uri);
  }

  watch(
    uri: vscode.Uri,
    options: {
      readonly recursive: boolean;
      readonly excludes: readonly string[];
    }
  ): vscode.Disposable {
    const uuid = uri.authority;
    const computer = this.computerManager.requireComputer(uuid);

    return computer.fs.watch(uri, options);
  }

  async writeFile(
    uri: vscode.Uri,
    content: Uint8Array,
    options: { readonly create: boolean; readonly overwrite: boolean }
  ): Promise<void> {
    const uuid = uri.authority;
    const computer = this.computerManager.requireComputer(uuid);

    return computer.fs.writeFile(uri, content, options);
  }
}
