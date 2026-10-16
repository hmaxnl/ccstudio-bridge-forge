import * as vscode from "vscode";
import logger from "@/logging/logger";
import { Computer, SerializedComputer } from "./Computer";
import { ComputerTreeProvider } from "@/views/ComputerTree/ComputerTreeProvider";
import { FileWatcher } from "./FileWatcher";
import { HttpFileServer } from "./HttpFileServer";
import config from "@/helpers/config";

export class ComputerManager {
  private computers: Map<string, Computer>;
  private fileWatcher: FileWatcher;
  /** Tree provider, stored during {@link initialize} so computers can be
   * auto-registered without being handed the provider each time. */
  private treeProvider!: ComputerTreeProvider;

  private _onFileChange: vscode.EventEmitter<vscode.FileChangeEvent[]>;
  readonly onFileChange: vscode.Event<vscode.FileChangeEvent[]>;

  constructor(fileWatcher: FileWatcher) {
    this.computers = new Map();
    this.fileWatcher = fileWatcher;

    this._onFileChange = new vscode.EventEmitter<vscode.FileChangeEvent[]>();
    this.onFileChange = this._onFileChange.event;
  }

  async initialize(tree: ComputerTreeProvider) {
    this.treeProvider = tree;
    const serializedComputers = await this.getSerializedComputers();
    for (const [uuid, serializedComputer] of Object.entries(
      serializedComputers
    )) {
      const computer = Computer.fromJSON(
        this.fileWatcher,
        tree,
        uuid,
        serializedComputer
      );

      computer.onSave(this.save, this);
      computer.onFileChange((e) => this._onFileChange.fire(e));

      this.computers.set(uuid, computer);
      logger.debug(`Deserialized ${computer.name}(${computer.uuid})`);
    }
    tree.update();
  }

  /** Get all computers that have been serialized and saved to storage */
  private async getSerializedComputers(): Promise<{
    [key: string]: SerializedComputer;
  }> {
    const folder = vscode.workspace.workspaceFolders?.[0];
    if (!folder) {
      return {};
    }

    const uri = vscode.Uri.joinPath(folder.uri, ".ccLink/config.json");

    try {
      const data = await vscode.workspace.fs.readFile(uri);
      return JSON.parse(data.toString());
    } catch (e) {
      if (e instanceof vscode.FileSystemError) {
        if (e.code !== "FileNotFound") {
          logger.error(e);
        }
      }
      return {};
    }
  }

  /** Save the computers to workspace storage */
  async save() {
    const folder = vscode.workspace.workspaceFolders?.[0];
    if (!folder) {
      return;
    }
    const file = vscode.Uri.joinPath(folder.uri, ".ccLink/config.json");
    const content = Buffer.from(
      JSON.stringify(Object.fromEntries(this.computers.entries()))
    );
    await vscode.workspace.fs.writeFile(file, content);
    logger.debug("Serialized and saved computers");
  }

  async addComputer(
    tree: ComputerTreeProvider,
    fileServer: HttpFileServer,
    name?: string,
    uuid?: string
  ) {
    const patterns: string[] = [];

    const computer = new Computer(this.fileWatcher, tree, name, uuid);
    computer.watchGlobPatterns = patterns;
    computer.onSave(this.save, this);
    this.computers.set(computer.uuid, computer);

    try {
      const uri = await computer.generateInstallScript();

      const path = `/install/${computer.uuid}.lua`;

      const stop = fileServer.serveFile(path, uri);
      computer.onConnected(() => stop());

      const host = config.getHostURI();
      const url = vscode.Uri.joinPath(host, path);
      vscode.env.clipboard.writeText("wget run " + url.toString());
      vscode.window.showInformationMessage(
        `Copied install command to clipboard. Paste in a computer and hit enter!`
      );
    } catch (e) {
      logger.error("Failed to generate install script", e);
    }

    logger.info(`Created new computer: ${computer.uuid}`);
    tree.update();
    this.save();
  }

  removeComputer(tree: ComputerTreeProvider, uuid: string | string[]) {
    if (!Array.isArray(uuid)) {
      uuid = [uuid];
    }

    for (const id of uuid) {
      const computer = this.computers.get(id);

      if (computer === undefined) {
        logger.warn(`Attempted to remove non-existent computer: ${id}`);
        continue;
      }

      computer.disconnect(1000, "DISCONNECT_AND_NOT_RECONNECT");
      computer.removeInstallScript();
      computer.dispose();
      this.computers.delete(id);
      tree.update();
      logger.info(`Removed computer: ${computer.name}(${computer.uuid})`);
    }

    this.save();
  }

  getComputer(uuid: string): Computer | undefined {
    return this.computers.get(uuid);
  }

  requireComputer(uuid: string): Computer {
    const computer = this.computers.get(uuid);

    if (!computer) {
      throw new Error(`computer ${uuid} does not exist`);
    }

    return computer;
  }

  /**
   * Return the computer with the given UUID, creating and persisting a new one
   * if it has not been seen before. Lets the game pair itself automatically on
   * connect (the game generates its own UUID) without a manual "Add Computer"
   * step.
   */
  getOrCreateComputer(uuid: string, name?: string): Computer {
    const existing = this.computers.get(uuid);
    if (existing) {
      return existing;
    }

    const computer = new Computer(this.fileWatcher, this.treeProvider, name, uuid);
    computer.onSave(this.save, this);
    computer.onFileChange((e) => this._onFileChange.fire(e));
    this.computers.set(uuid, computer);

    this.treeProvider.update();
    this.save();
    logger.info(`Auto-registered new computer: ${uuid}`);
    return computer;
  }

  getComputers() {
    return Array.from(this.computers.values());
  }
}
