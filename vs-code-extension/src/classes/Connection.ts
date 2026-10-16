import { WebSocket } from "ws";
import logger from "@/logging/logger";
import { EventSource } from "./EventSource";
import { IncomingMessage } from "http";
import * as ccLinkProtocol from "@/schema/protocol";
import { Computer } from "./Computer";
import * as vscode from "vscode";

type PendingMessage = {
  id: number;
  resolve: (result: unknown) => void;
  reject: (error: Error) => void;
  timeSent: number;
};

type ClientInfo = {
  version?: string;
  os?: string;
};

type Events = {
  message: (data: unknown) => void;
  error: (err: Error) => void;
  close: (code: number, reason: string) => void;
};

export const ConnectionWebsocketMissingError = new Error(
  "connection not ready: websocket not attached"
);
export const ConnectionDisconnectedError = new Error("client is not connected");
export const ConnectionTimeoutError = new Error(
  "timeout: client not responding"
);

const truncateString = (s: string, length: number) => {
  if (s.length <= length) {
    return s;
  }

  return `${s.substring(0, length)}...`;
};

const RESPONSE_TIMEOUT = 5;

export class Connection extends EventSource<Events> {
  private computer: Computer;
  private ip?: string;
  private ws: WebSocket | null;
  private client?: ClientInfo;
  private pendingMessages: PendingMessage[];
  private messageIDCounter: number;
  private responseTimeout: number;
  private disposables: vscode.Disposable[];
  private logger: typeof logger;

  constructor(computer: Computer) {
    super();

    const config = vscode.workspace.getConfiguration("ccLink");
    const timeout = config.get("computer.timeout", RESPONSE_TIMEOUT);

    this.computer = computer;

    // To be initialized by attachWebsocket
    this.ip = undefined;
    this.ws = null;
    this.client = undefined;

    this.pendingMessages = [];
    this.messageIDCounter = 0;
    this.responseTimeout = timeout * 1000;

    const timeoutInterval = setInterval(
      () => this.pruneTimeouts(),
      Math.min(this.responseTimeout, 500)
    );
    this.disposables = [
      new vscode.Disposable(() => clearInterval(timeoutInterval)),
    ];

    this.logger = logger.child({
      label: "Connection",
      IP: this.ip ?? "N/A",
      computer: { uuid: computer.uuid, name: computer.name },
      client: this.client,
    });
  }

  get isConnected(): boolean {
    return this.ws?.readyState === WebSocket.OPEN;
  }

  attachWebsocket(ws: WebSocket, request: IncomingMessage) {
    this.ws = ws;
    this.client = {
      version: request.headers["x-cclink-version"] as string,
      os: request.headers["x-cclink-os"] as string,
    };
    this.ip = request.socket.remoteAddress;

    this.ws.on("message", (data) => this.handleMessage(data.toString()));
    this.ws.on("error", (err) => this.handleError(err));
    this.ws.on("close", (code, reason) =>
      this.handleClose(code, reason.toString())
    );

    this.logger = logger.child({
      label: "Connection",
      IP: this.ip ?? "N/A",
      computer: { uuid: this.computer.uuid, name: this.computer.name },
      client: this.client,
    });
    this.logger.info("Connected!");
  }

  private pruneTimeouts() {
    const now = new Date().getTime();

    this.pendingMessages = this.pendingMessages.filter((msg) => {
      const elapsedMS = now - msg.timeSent;

      if (elapsedMS > this.responseTimeout) {
        this.logger.warn(`Message ${msg.id} timed out after ${elapsedMS}ms`);
        msg.reject(ConnectionTimeoutError);
        return false;
      }

      return true;
    });
  }

  private handleMessage(messageRaw: string) {
    let msg;
    try {
      msg = JSON.parse(messageRaw);
    } catch (e) {
      this.logger.error(`Failed to parse message as JSON: ${e}`);
      return;
    }

    if (typeof msg.serverId === "number") {
      let response;
      try {
        response = ccLinkProtocol.response.parse(msg);
      } catch (e) {
        this.logger.error(`Message response violates schema: ${e}`);
        return;
      }

      this.logger.trace(`Received response to message ${msg.serverId}`, {
        data: truncateString(messageRaw, 50),
      });

      const index = this.pendingMessages.findIndex(
        (m) => m.id === response.serverId
      );
      const promise = this.pendingMessages.splice(index, 1)[0];
      promise.resolve(response);
    } else if (typeof msg.clientId === "number") {
      let message;
      try {
        message = ccLinkProtocol.request.parse(msg);
      } catch (e) {
        this.logger.error(`New message violates schema: ${e}`);
        return;
      }

      this.logger.trace(`Received new message: ${message.clientId}`, {
        data: message.data,
      });

      const answer = (success: boolean, result: unknown) => {
        this.logger.trace(`Responding to message: ${message.clientId}`, {
          success: success,
          data: result,
        });

        if (!this.ws) {
          throw ConnectionWebsocketMissingError;
        }

        this.ws.send(
          JSON.stringify({
            clientId: message.clientId,
            success: success,
            data: result,
          })
        );
      };

      try {
        this.dispatch("message", msg.data).then((p) => answer(true, p));
      } catch (e) {
        answer(false, e?.toString());
        return;
      }
    } else {
      this.logger.error(`Received message did not contain an ID`);
    }
  }

  private handleError(err: Error) {
    this.logger.error(`Websocket connection encountered an error: ${err}`);
    this.dispatch("error", err);
  }

  private handleClose(code: number, reason: string) {
    this.logger.info("Closed connection!", {
      code: code,
      reason: reason,
    });
    this.dispatch("close", code, reason);
    this.dispose();
  }

  dispose() {
    while (this.disposables.length > 0) {
      this.disposables.pop()?.dispose();
    }
  }

  disconnect(code?: number, reason?: string) {
    if (!this.ws) {
      throw ConnectionWebsocketMissingError;
    }
    this.logger.debug(`${code}: ${reason}`);
    this.ws.close(code, reason);
  }

  async send(data: unknown): Promise<unknown> {
    if (!this.isConnected) {
      return Promise.reject(ConnectionDisconnectedError);
    }

    return new Promise((resolve, reject) => {
      const messageID = this.messageIDCounter++;

      this.pendingMessages.push({
        id: messageID,
        resolve: resolve,
        reject: reject,
        timeSent: new Date().getTime(),
      });

      this.logger.trace(`Sending message: ${messageID}`, {
        message: JSON.stringify(data),
        serverId: messageID,
      });

      if (!this.ws) {
        return reject(ConnectionWebsocketMissingError);
      }

      this.ws.send(
        JSON.stringify({
          serverId: messageID,
          data: data,
        })
      );
    });
  }
}
