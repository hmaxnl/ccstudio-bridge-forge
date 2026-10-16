import * as vscode from "vscode";
import winston from "winston";
import { VSCodeOutputWindowTransport } from "./transports/OutputPanel";

export type ccLogger = winston.Logger & {
  trace: winston.LeveledLogMethod;
};

const LoggingLevels = {
  levels: {
    off: 0,
    error: 1,
    warn: 2,
    info: 3,
    debug: 4,
    trace: 5,
  },
  colors: {
    error: "red",
    warn: "yellow",
    info: "green",
    debug: "magenta",
    trace: "gray",
  },
};

const vscodeLogLevelMap: { [key in vscode.LogLevel]: string } = {
  [vscode.LogLevel.Off]: "off",
  [vscode.LogLevel.Error]: "error",
  [vscode.LogLevel.Warning]: "warn",
  [vscode.LogLevel.Info]: "info",
  [vscode.LogLevel.Debug]: "debug",
  [vscode.LogLevel.Trace]: "trace",
};

const logger = winston.createLogger({
  levels: LoggingLevels.levels,
  transports: [
    new winston.transports.Console({
      level: "trace",
      format: winston.format.combine(
        winston.format.errors({ stack: true }),
        winston.format.metadata(),
        winston.format.timestamp(),
        winston.format.printf((info) => {
          const metadata = info.metadata as any;
          const level = info.level.toUpperCase().padEnd(5, " ");

          const label = (metadata.label ?? "General").padEnd(25, " ");
          delete metadata.label;

          const stack = metadata.stack;
          delete metadata.stack;

          let msg = `${info.timestamp}  ${level}  ${label}   ${info.message}`;

          if (Object.keys(metadata).length > 0) {
            msg += ` ${JSON.stringify(metadata)}`;
          }

          if (stack) {
            msg += `\n  ${stack}`;
          }

          return msg;
        }),
        winston.format.colorize({ all: true })
      ),
    }),
    new VSCodeOutputWindowTransport({
      level: vscodeLogLevelMap[vscode.env.logLevel],
      format: winston.format.combine(
        winston.format.label(),
        winston.format.metadata(),
        winston.format.json()
      ),
    }),
  ],
});

winston.addColors(LoggingLevels.colors);

vscode.env.onDidChangeLogLevel((level) => {
  logger.transports[1].level = vscodeLogLevelMap[level];
});

export default logger as ccLogger;
