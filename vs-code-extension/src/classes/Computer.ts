import { v4 as uuid } from "uuid";
import * as vscode from "vscode";
import { ComputerTreeNode } from "../views/ComputerTree/items/ComputerNode";
import logger, { ccLogger } from "@/logging/logger";
import { getRandomBlockName } from "../data/names";
import { Connection } from "./Connection";
import ClientCommands from "../commands/client/handlers";
import { RemoteShell } from "./RemoteShell";
import path from "path";
import glob from "fast-glob";
import { FileWatcher } from "./FileWatcher";
import * as paths from "@/helpers/paths";
import { TreeProvider } from "@/views/ComputerTree/items/TreeNode";
import { IncomingMessage } from "http";
import { WebSocket } from "ws";
import * as ccLinkProtocol from "@/schema/protocol";
import { RPCCommandError } from "./RPCCommandError";
import { ComputerFileSystem } from "./ComputerFileSystem";
import config from "@/helpers/config";

/** A `Computer` in its serialized state  */
export type SerializedComputer = {
  name: string;
  watchGlobPatterns: string[];
};

export enum COMPUTER_STATES {
  CONNECTED = 0,
  DISCONNECTED = 1,
  RUNNING = 2,
  ERROR = 3,
  WARNING = 4,
  ACTION_REQUIRED = 5,
}

const installScriptTemplateUri = vscode.Uri.file(
  path.join(__filename, "..", "..", "assets", "installClient.lua")
);

type GlobChange = {
  type: "add" | "remove";
  patterns: string[];
};

export class Computer {
  /** The label of the computer */
  name: string;
  /** The UUID used to uniquely identify and authenticate this computer */
  readonly uuid: string;
  /** The `ComputerTreeItem` that represents this computer in the TreeView */
  readonly treeItem: ComputerTreeNode;
  /** The client used by this computer to manage the websocket connection */
  readonly connection: Connection;
  /** Remote shells open on this computer */
  private shells: Map<number, RemoteShell>;

  // TODO: Find better method for getting remote file list updates
  private getFileListInterval?: NodeJS.Timeout;

  logger: ccLogger;

  /** Glob patterns for watching local files */
  watchGlobPatterns: string[];
  /** Whether local files are being watched or not */
  private _watching: boolean;
  /** Remote files on the Minecraft computer */
  remoteFiles: vscode.Uri[];

  fs: ComputerFileSystem;

  /** Items that should be destroyed when the computer is removed to free up resources */
  disposables: vscode.Disposable[];

  // Events
  private _onConnected: vscode.EventEmitter<void>;
  private _onDisconnected: vscode.EventEmitter<void>;
  private _onSave: vscode.EventEmitter<Computer>;
  private _onGlobChange: vscode.EventEmitter<GlobChange>;
  private _onFileChange: vscode.EventEmitter<vscode.FileChangeEvent[]>;
  readonly onConnected: vscode.Event<void>;
  readonly onDisconnected: vscode.Event<void>;
  readonly onSave: vscode.Event<Computer>;
  readonly onGlobChange: vscode.Event<GlobChange>;
  readonly onFileChange: vscode.Event<vscode.FileChangeEvent[]>;

  /**
   * @param name The name of this computer. If omitted, a random name will be selected.
   * @param id The UUID of this computer. If omitted, a new UUID will be generated.
   */
  constructor(
    fileWatcher: FileWatcher,
    treeProvider: TreeProvider,
    name?: string,
    id?: string
  ) {
    this.uuid = id ?? uuid();
    this.name = name ?? getRandomBlockName();
    this.logger = logger.child({
      label: "Computer",
      computer: { uuid: this.uuid, name: this.name },
    });
    this.watchGlobPatterns = [];
    this.remoteFiles = [];
    this._watching = true;
    this.shells = new Map();

    this._onConnected = new vscode.EventEmitter<undefined>();
    this._onDisconnected = new vscode.EventEmitter<undefined>();
    this._onSave = new vscode.EventEmitter<Computer>();
    this._onGlobChange = new vscode.EventEmitter<GlobChange>();
    this._onFileChange = new vscode.EventEmitter<vscode.FileChangeEvent[]>();

    this.onConnected = this._onConnected.event;
    this.onDisconnected = this._onDisconnected.event;
    this.onSave = this._onSave.event;
    this.onGlobChange = this._onGlobChange.event;
    this.onFileChange = this._onFileChange.event;

    this.connection = new Connection(this);
    this.treeItem = new ComputerTreeNode(treeProvider, this, this.name);
    this.fs = new ComputerFileSystem(this);

    const disconnect = () => {
      clearInterval(this.getFileListInterval);
      this._onDisconnected.fire();
    };

    this.connection.on("close", disconnect.bind(this));
    this.connection.on("error", disconnect.bind(this));
    this.connection.on("message", (data) => this.handleMessage(data));

    this.disposables = [
      this._onConnected,
      this._onDisconnected,
      this._onSave,
      this._onGlobChange,
      this.connection,
      this.treeItem,
    ];

    this.onConnected(
      () => {
        this.treeItem.update();
        this.updateRemoteFileList().catch((e) =>
          this.logger.error(`Failed to update remote file list: ${e}`)
        );
      },
      this,
      this.disposables
    );
    this.onDisconnected(
      () => {
        this.treeItem.update();
      },
      this,
      this.disposables
    );

    fileWatcher.onFileCreate(this.sendFileIfWatching, this, this.disposables);
    fileWatcher.onFileChange(this.sendFileIfWatching, this, this.disposables);
  }

  /** Create a new {@link Computer} from its serialized JSON form. */
  static fromJSON(
    fileWatcher: FileWatcher,
    treeProvider: TreeProvider,
    uuid: string,
    computer: SerializedComputer
  ): Computer {
    const instance = new Computer(
      fileWatcher,
      treeProvider,
      computer.name,
      uuid
    );
    instance.watchGlobPatterns = computer.watchGlobPatterns;
    instance._onGlobChange.fire({
      type: "add",
      patterns: instance.watchGlobPatterns,
    });
    return instance;
  }

  get isConnected(): boolean {
    return this.connection.isConnected;
  }

  get watching() {
    return this._watching;
  }

  set watching(watching: boolean) {
    this._watching = watching;
    this.logger.info(
      watching
        ? "Started watching glob patterns"
        : "Paused watching glob patterns"
    );
    this.treeItem.update(this.treeItem);
  }

  toString(): string {
    return `${this.name} (${this.uuid})`;
  }

  /** Get a JSON serializable version of this computer */
  toJSON(): SerializedComputer {
    return {
      name: this.name,
      watchGlobPatterns: this.watchGlobPatterns,
    };
  }

  attachWebsocket(ws: WebSocket, request: IncomingMessage) {
    this.removeInstallScript();

    this.name = request.headers["x-cclink-computer-name"] as string;
    this.treeItem.label = this.name;
    logger.child({
      label: "Computer",
      computer: { uuid: this.uuid, name: this.name },
    });

    this.connection.attachWebsocket(ws, request);

    this.sendWatchedFilesToClient();

    this._onConnected.fire();
  }

  /** Handle a message received from the client */
  private handleMessage(data: unknown): Promise<unknown> {
    const rpc = ccLinkProtocol.RPC.parse(data);

    try {
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      return (ClientCommands as any)[rpc.command](this, rpc.payload);
    } catch (e) {
      this.logger.error(`Command handler failed: ${e}`);
      return Promise.reject(e);
    }
  }

  dispose() {
    while (this.disposables.length > 0) {
      this.disposables.pop()?.dispose();
    }
  }

  async createRemoteShell() {
    const response = await this.sendCommand("createShell", undefined);
    const { shellID, backgroundColor } = response;

    const shell = new RemoteShell(this, shellID);
    this.shells.set(shellID, shell);
    this.disposables.push({ dispose: shell.close });
    shell.show();

    shell.onClose(() => {
      this.shells.delete(shell.shellID);
    });

    await shell.whenReady(5000);

    const { r, g, b } = backgroundColor;
    await shell.write(`\x1b[48;2;${r ?? 17};${g ?? 17};${b ?? 17}m\x1b[2J`);

    this.logger.debug("Created remote shell session with ID " + shellID);

    this.sendCommand("readyShell", { shellID: shellID }).catch((e) =>
      this.logger.error(e)
    );

    return shell;
  }

  getShell(ID: number) {
    return this.shells.get(ID);
  }

  /** Get a {@link vscode.QuickPickItem} for this computer */
  asQuickPickItem(): vscode.QuickPickItem {
    return { label: this.name, description: this.uuid };
  }

  /** Get the location where the install file for this computer should be
   * generated and stored. Returns undefined when a workspace is not open */
  getInstallFileLocation() {
    const workspace = vscode.workspace.workspaceFolders?.[0];

    if (!workspace) {
      return undefined;
    }

    const folder = vscode.Uri.joinPath(workspace.uri, ".ccLink/install");
    return vscode.Uri.joinPath(folder, this.uuid + ".lua");
  }

  /** Wait for this computer to connect */
  async waitUntilConnected(timeout: number): Promise<Computer> {
    if (this.isConnected) {
      return Promise.resolve(this);
    }

    return new Promise((resolve, reject) => {
      const t = setTimeout(() => reject("Timed out"), timeout);

      this.onConnected(() => {
        clearTimeout(t);
        resolve(this);
      });
    });
  }

  addWatchGlobPattern(...patterns: string[]) {
    this.watchGlobPatterns.push(...patterns);

    this.logger.info("Added glob patterns to watch list", {
      patterns: patterns,
    });

    this.sendWatchedFilesToClient();
    this._onGlobChange.fire({ type: "add", patterns: patterns });
    this._onSave.fire(this);
  }

  /** Remove a glob pattern from being watched */
  removeWatchGlobPattern(...patterns: string[]) {
    this.watchGlobPatterns = this.watchGlobPatterns.filter(
      (p) => !patterns.includes(p)
    );
    this.logger.info("Removed glob pattern(s) from watcher", {
      patterns: patterns,
    });

    this._onGlobChange.fire({ type: "remove", patterns: patterns });
    this._onSave.fire(this);
  }

  /** Sends a RPC command to the computer and returns a promise that resolves
   * with the response, when/if received. Automatically parses responses using
   * the schemas from {@link ccLinkProtocol}.
   *
   * Should the command fail, a {@link RPCCommandError} will be returned in the
   * rejected promise. */
  async sendCommand<Command extends ccLinkProtocol.Command>(
    command: Command,
    payload: ccLinkProtocol.RpcPayload<Command> extends void
      ? undefined
      : ccLinkProtocol.RpcPayload<Command>
  ): Promise<ccLinkProtocol.RpcResponse<Command>> {
    const result = await this.connection.send({
      command: command,
      payload: payload,
    });

    const response = ccLinkProtocol.response.parse(result);

    if (!response.success) {
      return Promise.reject(new RPCCommandError(response.data));
    }

    return ccLinkProtocol.responses[command].parse(response.data);
  }

  async sendFileIfWatching(file: vscode.Uri) {
    if (!this.isConnected || !this.watching) {
      return;
    }

    const folders = vscode.workspace.workspaceFolders ?? [];
    if (folders.length < 1) {
      this.logger.warn("No workspace folder open");
      return;
    }

    const workspaceRoot = vscode.workspace
      .getConfiguration("ccLink")
      .get("workspace.root", "");

    const streams: NodeJS.ReadableStream[] = [];
    const cancelStreams = () => {
      streams.forEach((stream) => {
        stream.removeAllListeners();
        stream.unpipe();
      });
    };

    try {
      const promises = folders.map((folder) => {
        return new Promise<vscode.Uri | null>((resolve, reject) => {
          const stream = glob.stream(
            config.prependGlobalGlobs(this.watchGlobPatterns),
            {
              cwd: vscode.Uri.joinPath(folder.uri, workspaceRoot).fsPath,
              absolute: true,
              dot: true,
            }
          );

          streams.push(stream);

          stream.on("data", (path) => {
            const uri = vscode.Uri.file(path);

            if (uri.path === file.path) {
              cancelStreams();
              resolve(uri);
            }
          });

          stream.on("error", reject);

          stream.on("end", () => resolve(null));
        });
      });

      const uris = await Promise.all(promises);
      const uri = uris.find((uri) => uri !== null);
      if (uri) {
        return this.sendFileToClient(uri);
      }
    } finally {
      cancelStreams();
    }
  }

  async sendWatchedFilesToClient() {
    const folders = vscode.workspace.workspaceFolders ?? [];
    if (folders.length < 1) {
      this.logger.warn("No workspace folder open");
      return;
    }

    if (!this._watching) {
      return;
    }

    const workspaceRoot = vscode.workspace
      .getConfiguration("ccLink")
      .get<string>("workspace.root", "");

    return Promise.all(
      folders.map((folder) => {
        glob(config.prependGlobalGlobs(this.watchGlobPatterns), {
          cwd: vscode.Uri.joinPath(folder.uri, workspaceRoot).fsPath,
          absolute: true,
          dot: true,
          unique: true,
        }).then((matches) =>
          matches.map(vscode.Uri.file).map(this.sendFileToClient, this)
        );
      })
    );
  }

  /** Send a file from this machine to the client */
  async sendFileToClient(file: vscode.Uri) {
    let content: Uint8Array;
    try {
      content = await vscode.workspace.fs.readFile(file);
    } catch (e) {
      this.logger.error("Failed to read file", e);
      return;
    }

    const transformFilepath = (
      file: vscode.Uri,
      computerRoot?: string,
      workspaceRoot?: string
    ): string => {
      let targetPath = "/" + vscode.workspace.asRelativePath(file, false);

      // Remove workspace root from path
      // root = "src"
      // path = "/src/lib/init.lua"
      // => lib/init.lua
      if (workspaceRoot) {
        targetPath = paths.removeRoot(
          targetPath,
          paths.normalizeRootPath(workspaceRoot)
        );
      }

      // Prepend computer root to path
      // root = "myProject/"
      // path = "main.lua"
      // => myProject/main.lua
      if (computerRoot) {
        return vscode.Uri.joinPath(vscode.Uri.file(computerRoot), targetPath)
          .path;
      } else {
        const workspaceFolder = vscode.workspace.getWorkspaceFolder(file);
        if (workspaceFolder) {
          return `/${workspaceFolder.name}${targetPath}`;
        }
      }

      return targetPath;
    };

    const config = vscode.workspace.getConfiguration("ccLink");
    const targetPath = transformFilepath(
      file,
      config.get("computer.root"),
      config.get("workspace.root")
    );

    this.logger.debug(`Sending ${file.path} => ${targetPath}`);
    return this.sendCommand("writeFile", {
      path: targetPath,
      content: content.toString(),
      create: true,
      overwrite: true,
    });
  }

  /** Disconnect this computer from the websocket server */
  disconnect(code?: number, reason?: string) {
    if (!this.isConnected) return;
    this.connection.disconnect(code, reason);
  }

  async updateRemoteFileList(): Promise<vscode.Uri[]> {
    if (!this.connection) {
      throw new Error(
        `Failed to update computer ${this} remote file tree: not connected`
      );
    }

    let filePaths;
    try {
      filePaths = await this.sendCommand("listFiles", undefined);
    } catch (e) {
      this.logger.error(`Failed to update remote file list: ${e}`);
      throw e;
    }

    const URIs = filePaths.map((path) =>
      vscode.Uri.from({
        scheme: "ccLink",
        authority: this.uuid,
        path: `/${path}`,
      })
    );

    this.remoteFiles = URIs;
    this.treeItem.updateRemoteFileTreeNode(URIs);
    this.treeItem.update();

    return URIs;
  }

  async generateInstallScript(): Promise<vscode.Uri> {
    const uri = this.getInstallFileLocation();
    if (!uri) return Promise.reject("Workspace is not open");

    const config = vscode.workspace.getConfiguration("ccLink");
    const url = new URL(
      `ws://${config.get("installScript.host", "127.0.0.1")}`
    );
    if (url.port === "") {
      url.port = config.get("websocket.port", "56560");
    }

    let content;
    try {
      content = await vscode.workspace.fs.readFile(installScriptTemplateUri);
    } catch (e) {
      this.logger.error(`Failed to read install script template: ${e}`);
      return uri;
    }

    content = content
      .toString()
      .replace("$HOST", url.host)
      .replace("$UUID", this.uuid);

    await vscode.workspace.fs.writeFile(uri, Buffer.from(content));

    return uri;
  }

  /** Remove the install script generated for this computer */
  async removeInstallScript() {
    const installFileURI = this.getInstallFileLocation();
    if (!installFileURI) return false;

    try {
      await vscode.workspace.fs.delete(installFileURI, { useTrash: false });
    } catch (e) {
      if (e instanceof vscode.FileSystemError) {
        if (e.code !== "FileNotFound") {
          this.logger.error(e);
        }
      } else {
        this.logger.error(e);
      }
    }

    const folder = vscode.Uri.joinPath(installFileURI, "../");

    try {
      // NOTE: vscode.workspace.fs.readDirectory throws an uncatch-able error if
      // the dir does not exist. Stat-ing first makes sure it exists before
      // reading the directory
      await vscode.workspace.fs.stat(folder);
      const files = await vscode.workspace.fs.readDirectory(folder);
      if (files.length > 0) return;
    } catch (e) {
      if (e instanceof vscode.FileSystemError) {
        if (e.code !== "FileNotFound") {
          this.logger.error(e);
        }
      } else {
        this.logger.error(e);
      }
      return;
    }

    try {
      vscode.workspace.fs.delete(folder, {
        recursive: true,
        useTrash: false,
      });
    } catch (e) {
      this.logger.error(`Failed to delete script install folder: ${e}`);
    }
  }

  triggerFileChange(changes: vscode.FileChangeEvent[]) {
    this._onFileChange.fire(changes);
  }
}
