import logger, { ccLogger } from "@/logging/logger";
import * as vscode from "vscode";

export class FileWatcher {
  private watcher: vscode.FileSystemWatcher;
  private disposables: vscode.Disposable[];

  private _onFileCreate: vscode.EventEmitter<vscode.Uri>;
  private _onFileChange: vscode.EventEmitter<vscode.Uri>;
  private _onFileDelete: vscode.EventEmitter<vscode.Uri>;
  readonly onFileCreate: vscode.Event<vscode.Uri>;
  readonly onFileChange: vscode.Event<vscode.Uri>;
  readonly onFileDelete: vscode.Event<vscode.Uri>;

  private logger: ccLogger;

  constructor(glob: string) {
    this.watcher = vscode.workspace.createFileSystemWatcher(glob);

    this._onFileCreate = new vscode.EventEmitter<vscode.Uri>();
    this._onFileChange = new vscode.EventEmitter<vscode.Uri>();
    this._onFileDelete = new vscode.EventEmitter<vscode.Uri>();

    this.onFileCreate = this._onFileCreate.event;
    this.onFileChange = this._onFileChange.event;
    this.onFileDelete = this._onFileDelete.event;

    this.disposables = [
      this.watcher,
      this._onFileCreate,
      this._onFileChange,
      this._onFileDelete,
    ];

    this.logger = logger.child({ label: "FileWatcher" });
    this.attachWatcherListeners(this.watcher);

    this.logger.debug(`Watching ${glob}`);
  }

  private attachWatcherListeners(watcher: vscode.FileSystemWatcher) {
    watcher.onDidCreate(
      (uri) => {
        this.logger.trace(`File created: ${uri}`);
        this._onFileCreate.fire(uri);
      },
      this,
      this.disposables
    );
    watcher.onDidChange(
      (uri) => {
        this.logger.trace(`File changed: ${uri}`);
        this._onFileChange.fire(uri);
      },
      this,
      this.disposables
    );
    watcher.onDidDelete(
      (uri) => {
        this.logger.trace(`File deleted: ${uri}`);
        this._onFileDelete.fire(uri);
      },
      this,
      this.disposables
    );
  }

  setGlob(glob: string) {
    this.watcher.dispose();
    this.watcher = vscode.workspace.createFileSystemWatcher(glob);
    this.attachWatcherListeners(this.watcher);
    this.logger.debug(`Watching ${glob}`);
  }

  dispose() {
    while (this.disposables.length > 0) {
      this.disposables.pop()?.dispose();
    }
  }
}
