import * as vscode from "vscode";
import TransportStream = require("winston-transport");
import { MESSAGE } from "triple-beam";
import winston = require("winston");

export class VSCodeOutputWindowTransport extends TransportStream {
  private outputWindow: vscode.LogOutputChannel;

  constructor(opts?: TransportStream.TransportStreamOptions) {
    super(opts);

    this.outputWindow = vscode.window.createOutputChannel(
      "ComputerCraft Link",
      { log: true }
    );
  }

  log(info: winston.LogEntry, callback: () => void) {
    setImmediate(() => {
      this.emit("logged", info);
    });

    const msg = info[MESSAGE as unknown as keyof typeof info];

    switch (info.level) {
      case "error":
        this.outputWindow.error(msg);
        break;
      case "warn":
        this.outputWindow.warn(msg);
        break;
      case "info":
        this.outputWindow.info(msg);
        break;
      case "debug":
        this.outputWindow.debug(msg);
        break;
      case "trace":
        this.outputWindow.trace(msg);
        break;
      default:
        this.outputWindow.error(
          `Unknown log level "${info.level}"! Original message: ${info.message}`
        );
        break;
    }

    return callback();
  }
}
