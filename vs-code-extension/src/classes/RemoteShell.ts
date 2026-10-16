import * as vscode from "vscode";
import { Computer } from "./Computer";

export class RemoteShell {
  private disposables: vscode.Disposable[];
  private computer: Computer;
  private terminal: vscode.Terminal;

  private _onOpen: vscode.EventEmitter<void>;
  private _onClose: vscode.EventEmitter<void>;
  private _onResize: vscode.EventEmitter<vscode.TerminalDimensions>;
  private _onCursorPos: vscode.EventEmitter<{ x: number; y: number }>;
  private _onWrite: vscode.EventEmitter<string>;
  private _onRename: vscode.EventEmitter<string>;

  readonly onOpen: vscode.Event<void>;
  readonly onClose: vscode.Event<void>;

  open = false;
  terminated = false;
  shellID: number;
  dimensions?: vscode.TerminalDimensions;

  constructor(computer: Computer, ID: number) {
    this.computer = computer;
    this.shellID = ID;
    this.disposables = [];

    this._onOpen = new vscode.EventEmitter<void>();
    this._onClose = new vscode.EventEmitter<void>();
    this._onResize = new vscode.EventEmitter<vscode.TerminalDimensions>();
    this._onCursorPos = new vscode.EventEmitter<{ x: number; y: number }>();
    this._onWrite = new vscode.EventEmitter<string>();
    this._onRename = new vscode.EventEmitter<string>();
    this.onOpen = this._onOpen.event;
    this.onClose = this._onClose.event;

    this.computer.onDisconnected(
      () => {
        if (this.terminated) {
          return;
        }

        this.terminated = true;
        this.write("\x1b[37m\x1b[41mComputer has disconnected");
      },
      this,
      this.disposables
    );

    this.terminal = vscode.window.createTerminal({
      name: computer.name,
      pty: {
        onDidWrite: this._onWrite.event,
        onDidClose: this._onClose.event,
        onDidChangeName: this._onRename.event,
        open: () => {
          this.open = true;
          this._onOpen.fire();
        },
        close: () => this.close(),
        setDimensions: (dimensions: vscode.TerminalDimensions) => {
          this.dimensions = dimensions;
          this._onResize.fire(dimensions);

          this.computer.sendCommand("resizeShell", {
            shellID: this.shellID,
            columns: dimensions.columns,
            rows: dimensions.rows,
          });
        },
        handleInput: (char: string) => {
          if (this.terminated) {
            return;
          }
          // Check if the input is the terminal responding with the current cursor position
          const match = char.match(/\x1b\[(\d+);(\d+)R/);
          if (match) {
            this._onCursorPos.fire({
              x: parseInt(match[2]),
              y: parseInt(match[1]),
            });
            return;
          }

          this.computer.sendCommand("keyToShell", {
            shellID: this.shellID,
            key: char,
          });
        },
      },
    });

    this.disposables = [
      this.terminal,
      this._onClose,
      this._onOpen,
      this._onResize,
      this._onCursorPos,
      this._onWrite,
      this._onRename,
    ];
  }

  dispose() {
    while (this.disposables.length > 0) {
      this.disposables.pop()?.dispose();
    }
  }

  show(focus = false) {
    this.terminal.show(!focus);
  }

  setTitle(title: string) {
    this._onRename.fire(title);
  }

  /** Close the terminal and dispose of in-use resources */
  async close() {
    try {
      await this.computer.sendCommand("killShell", {
        shellID: this.shellID,
      });
    } catch (e) {
      throw e;
    } finally {
      this.open = false;
      this.dispose();
      this._onClose.fire();
    }
  }

  async write(text: string) {
    this._onWrite.fire(text);
  }

  getCursorPosition(): Promise<{ x: number; y: number }> {
    return new Promise<{ x: number; y: number }>((resolve, reject) => {
      const timeout = setTimeout(() => reject("Timed out"), 2000);

      this._onCursorPos.event(
        (pos) => {
          clearTimeout(timeout);
          resolve(pos);
        },
        undefined,
        this.disposables
      );

      this._onWrite.fire("\x1b[6n");
    });
  }

  /** Get a promise that resolves when the terminal is ready for writing */
  async whenReady(timeout_ms = 2000) {
    if (this.open) {
      return Promise.resolve();
    }

    return new Promise<void>((resolve, reject) => {
      const timeout = setTimeout(() => reject("Timed out!"), timeout_ms);
      this._onOpen.event(
        () => {
          clearTimeout(timeout);
          resolve();
        },
        undefined,
        this.disposables
      );
    });
  }
}
