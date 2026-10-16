import logger, { ccLogger } from "@/logging/logger";
import { createServer, IncomingMessage, Server, ServerResponse } from "http";
import * as vscode from "vscode";

export class HttpFileServer {
  readonly server: Server;
  private files: Map<string, vscode.Uri>;
  private logger: ccLogger;

  constructor(port: number) {
    this.server = createServer(this.handleRequest.bind(this));
    this.files = new Map();
    this.logger = logger.child({ label: "HttpFileServer" });

    this.server.listen(port, () =>
      this.logger.info(`HTTP server listening on ${port}`)
    );
  }

  private async handleRequest(req: IncomingMessage, res: ServerResponse) {
    const path = req.url as string;

    this.logger.info(`${req.method}: ${path}`);

    const targetURI = this.files.get(path);
    if (!targetURI) {
      res.writeHead(404, "file not found");
      res.end();
      return;
    }

    try {
      const content = await vscode.workspace.fs.readFile(targetURI);
      res.writeHead(200, {
        "content-type": "text/x-lua",
        "content-length": content.byteLength,
      });
      res.end(content);
    } catch (e) {
      this.handleFileError(e, res);
    }
  }

  private handleFileError(e: unknown, res: ServerResponse) {
    this.logger.error("Failed to read file", e);
    if (e instanceof vscode.FileSystemError) {
      switch (e.code) {
        case "FileNotFound":
          res.writeHead(404, "File not found");
          break;
        case "FileIsADirectory":
          res.writeHead(400, "File is a directory");
          break;
        case "NoPermissions":
          res.writeHead(403, "No Permissions to read the file");
          break;
        default:
          res.writeHead(500, "Unknown error occurred");
          break;
      }
    } else {
      res.writeHead(500, "Unknown error occurred");
    }
    this.logger.error(`${res.statusCode}: ${res.statusMessage}`);
    res.end();
  }

  serveFile(path: string, file: vscode.Uri): () => void {
    const relative = vscode.workspace.asRelativePath(file);
    this.files.set(path, file);
    this.logger.info(`${relative} is now available at ${path}`);

    return () => {
      this.files.delete(path);
      this.logger.debug(`${relative} is no longer being hosted at ${path}`);
    };
  }

  dispose() {
    this.server.close();
  }
}
