import * as vscode from "vscode";
import { Computer } from "./Computer";
import * as ccLinkProtocol from "@/schema/protocol";
import { RPCCommandError } from "./RPCCommandError";
import { ccLogger } from "@/logging/logger";
import { ConnectionDisconnectedError } from "./Connection";

export const FileSystemErrors = [
  vscode.FileSystemError.FileNotFound,
  vscode.FileSystemError.FileExists,
  vscode.FileSystemError.FileNotADirectory,
  vscode.FileSystemError.FileIsADirectory,
  vscode.FileSystemError.NoPermissions,
  vscode.FileSystemError.Unavailable,
];

export const newFilesystemError = (uuid: string, err: unknown) => {
  const { data, success } =
    ccLinkProtocol.filesystemErrorResponse.safeParse(err);

  if (success) {
    const uri = vscode.Uri.from({
      scheme: "ccLink",
      authority: uuid,
      path: data.path,
    });

    const fsError = FileSystemErrors[data.error];
    if (fsError) {
      return fsError(uri);
    }
  }

  // The client reported the failure as a plain message (e.g. an
  // RPCCommandError carrying the server's error string). Map it onto a
  // FileSystemError so VS Code renders the real reason instead of an
  // opaque schema error.
  const message =
    err instanceof Error && err.message
      ? err.message
      : typeof err === "string" && err
        ? err
        : "operation failed";

  let factory: (messageOrUri?: string | vscode.Uri) => vscode.FileSystemError =
    vscode.FileSystemError.Unavailable;
  if (/does not exist|not found/i.test(message)) {
    factory = vscode.FileSystemError.FileNotFound;
  } else if (/prohibited|permission|whitelist|disabled|reserved/i.test(message)) {
    factory = vscode.FileSystemError.NoPermissions;
  } else if (/already exists/i.test(message)) {
    factory = vscode.FileSystemError.FileExists;
  } else if (/cannot read a directory|is a directory/i.test(message)) {
    factory = vscode.FileSystemError.FileIsADirectory;
  } else if (/not a directory/i.test(message)) {
    factory = vscode.FileSystemError.FileNotADirectory;
  }

  return factory(message);
};

export class ComputerFileSystem implements vscode.FileSystem {
  private computer: Computer;
  private timeout: number;

  private logger: ccLogger;

  constructor(computer: Computer) {
    this.computer = computer;

    const config = vscode.workspace.getConfiguration("ccLink");
    this.timeout = config.get("computer.timeout", 10) * 1000;

    this.logger = this.computer.logger.child({ label: "ComputerFS" });
  }

  async copy(
    source: vscode.Uri,
    target: vscode.Uri,
    options?: { overwrite?: boolean }
  ): Promise<void> {
    await this.computer.waitUntilConnected(this.timeout);

    try {
      return await this.computer.sendCommand("copyFile", {
        source: source.path,
        destination: target.path,
        overwrite: options?.overwrite ?? false,
      });
    } catch (e) {
      this.logger.error(
        `failed to copy file ${source.path} to ${target.path}: ${e}`
      );
      if (e instanceof RPCCommandError) {
        throw newFilesystemError(this.computer.uuid, e);
      }
      throw e;
    }
  }

  async createDirectory(uri: vscode.Uri): Promise<void> {
    await this.computer.waitUntilConnected(this.timeout);

    try {
      return await this.computer.sendCommand("createDirectory", {
        path: uri.path,
      });
    } catch (e) {
      this.logger.error(`failed to create directory ${uri.path}: ${e}`);
      if (e instanceof RPCCommandError) {
        throw newFilesystemError(this.computer.uuid, e);
      }
      throw e;
    }
  }

  async delete(
    uri: vscode.Uri,
    options?: { recursive?: boolean; useTrash?: boolean }
  ): Promise<void> {
    await this.computer.waitUntilConnected(this.timeout);

    try {
      return await this.computer.sendCommand("delete", {
        path: uri.path,
        recursive: options?.recursive,
      });
    } catch (e) {
      this.logger.error(`failed to delete file ${uri.path}: ${e}`);
      if (e instanceof RPCCommandError) {
        throw newFilesystemError(this.computer.uuid, e);
      }
      throw e;
    }
  }

  isWritableFileSystem(scheme: string): boolean | undefined {
    if (scheme === "ccLink") {
      return true;
    }
  }

  async readDirectory(uri: vscode.Uri): Promise<[string, vscode.FileType][]> {
    await this.computer.waitUntilConnected(this.timeout);

    try {
      return await this.computer.sendCommand("readDirectory", {
        path: uri.path,
      });
    } catch (e) {
      this.logger.error(`failed to read directory ${uri.path}: ${e}`);
      if (e instanceof RPCCommandError) {
        throw newFilesystemError(this.computer.uuid, e);
      }
      throw e;
    }
  }

  async readFile(uri: vscode.Uri): Promise<Uint8Array> {
    await this.computer.waitUntilConnected(this.timeout);

    try {
      return Buffer.from(
        await this.computer.sendCommand("readFile", { path: uri.path })
      );
    } catch (e) {
      this.logger.error(`failed to read file ${uri.path}: ${e}`);
      if (e instanceof RPCCommandError) {
        throw newFilesystemError(this.computer.uuid, e);
      }
      throw e;
    }
  }

  async rename(
    source: vscode.Uri,
    target: vscode.Uri,
    options?: { overwrite?: boolean }
  ): Promise<void> {
    await this.computer.waitUntilConnected(this.timeout);

    try {
      return await this.computer.sendCommand("rename", {
        oldPath: source.path,
        newPath: target.path,
        overwrite: options?.overwrite,
      });
    } catch (e) {
      this.logger.error(
        `failed to rename ${source.path} to ${target.path}: ${e}`
      );
      if (e instanceof RPCCommandError) {
        throw newFilesystemError(this.computer.uuid, e);
      }
      throw e;
    }
  }

  async stat(uri: vscode.Uri): Promise<vscode.FileStat> {
    await this.computer.waitUntilConnected(this.timeout);

    try {
      return await this.computer.sendCommand("stat", { path: uri.path });
    } catch (e) {
      this.logger.error(`failed to stat ${uri.path}: ${e}`);
      if (e instanceof RPCCommandError) {
        throw newFilesystemError(this.computer.uuid, e);
      }
      throw e;
    }
  }

  watch(
    uri: vscode.Uri,
    options: {
      readonly recursive: boolean;
      readonly excludes: readonly string[];
    }
  ): vscode.Disposable {
    if (!this.computer.isConnected) {
      throw ConnectionDisconnectedError;
    }

    this.computer
      .sendCommand("watch", {
        path: uri.path,
        recursive: options.recursive,
        excludes: options.excludes,
      })
      .then(undefined, (e) => {
        this.logger.error(`failed to watch ${uri.path}: ${e}`);
        if (e instanceof RPCCommandError) {
          throw newFilesystemError(this.computer.uuid, e);
        }
        throw e;
      });

    return new vscode.Disposable(() => {
      this.computer.sendCommand("stopWatch", { path: uri.path }).catch((e) => {
        this.logger.error(`failed to stop watching ${uri.path}: ${e}`);
        if (e instanceof RPCCommandError) {
          throw newFilesystemError(this.computer.uuid, e);
        }
        throw e;
      });
    });
  }

  async writeFile(
    uri: vscode.Uri,
    content: Uint8Array,
    options?: { readonly create: boolean; readonly overwrite: boolean }
  ): Promise<void> {
    await this.computer.waitUntilConnected(this.timeout);

    try {
      return this.computer.sendCommand("writeFile", {
        path: uri.path,
        content: content.toString(),
        create: options?.create ?? true,
        overwrite: options?.overwrite,
      });
    } catch (e) {
      this.logger.error(`failed to write to file ${uri.path}`);
      if (e instanceof RPCCommandError) {
        throw newFilesystemError(this.computer.uuid, e);
      }
      throw e;
    }
  }
}
