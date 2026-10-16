import logger, { ccLogger } from "@/logging/logger";
import { IncomingMessage, Server } from "http";
import { VerifyClientCallbackAsync, WebSocketServer } from "ws";
import { ComputerManager } from "./ComputerManager";
import { AddressInfo } from "net";

const PROTOCOL = "computercraft-link";

export class WebsocketManager {
  private wss: WebSocketServer;
  private logger: ccLogger;

  constructor(server: Server, computerManager: ComputerManager) {
    this.logger = logger.child({ label: "WebsocketManager" });

    this.wss = new WebSocketServer({
      server,
      handleProtocols: () => PROTOCOL,
      verifyClient: (info, cb) =>
        this.authenticateClient(computerManager, info, cb),
    });

    this.wss.on("listening", () => {
      const port = (server.address() as AddressInfo)?.port;
      this.logger.info(`Listening on ${port}`);
    });

    this.wss.on("connection", (ws, req) => {
      const UUID = req.headers.authorization as string;
      computerManager.requireComputer(UUID).attachWebsocket(ws, req);
    });

    this.wss.on("error", (err) => this.logger.error(err));
    this.wss.on("close", () => this.logger.info("Websocket server closed"));
  }

  private authenticateClient(
    computerManager: ComputerManager,
    info: Parameters<VerifyClientCallbackAsync<IncomingMessage>>[0],
    cb: Parameters<VerifyClientCallbackAsync<IncomingMessage>>[1]
  ) {
    const IP = info.req.socket.remoteAddress;
    const UUID = info.req.headers.authorization;

    if (!UUID) {
      this.logger.warn(`Unauthorized connection from ${IP}`);
      return cb(false, 401, "UUID is expected in Authorization header");
    }

    // Auto-register unknown computers so the game can pair on connect without
    // a manual "Add Computer" step (the game generates its own UUID and sends
    // its display name in the X-ccLink-Computer-Name header).
    const name = info.req.headers["x-cclink-computer-name"] as
      | string
      | undefined;
    const computer = computerManager.getOrCreateComputer(UUID, name);
    if (computer.isConnected) {
      this.logger.warn(`UUID in use: ${UUID} from ${IP}`);
      return cb(false, 403, "UUID is already in use!");
    }

    this.logger.info(`Authenticated computer ${UUID} from ${IP}`);
    cb(true);
  }

  dispose() {
    this.wss.close();
  }
}
