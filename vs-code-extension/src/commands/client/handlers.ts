import * as vscode from "vscode";
import { Computer, COMPUTER_STATES } from "../../classes/Computer";

type CommandHandler<P, R = void> = (
  computer: Computer,
  payload: P
) => Promise<R>;

const ping: CommandHandler<number, number> = (_, payload) => {
  return Promise.resolve(payload);
};

const writeTerminal: CommandHandler<{ shellID: number; text: string }> = async (
  computer,
  payload
) => {
  const shell = computer.getShell(payload.shellID);
  if (!shell) throw new Error("Failed to get shell");

  await shell.whenReady();

  await shell.write(payload.text.replace(/\r?\n/g, "\r\n"));
};

const getTerminalSize: CommandHandler<
  { shellID: number },
  { columns: number; rows: number }
> = async (computer, payload) => {
  const shell = computer.getShell(payload.shellID);
  if (!shell) throw new Error("Failed to get shell");

  await shell.whenReady(3000);

  if (!shell.dimensions) {
    throw new Error("failed to get shell dimensions");
  }

  return shell.dimensions;
};

const getTerminalCursorPos: CommandHandler<
  { shellID: number },
  { x: number; y: number }
> = async (computer, payload) => {
  const terminal = computer.getShell(payload.shellID);
  if (!terminal) throw new Error("Failed to get terminal");

  await terminal.whenReady();

  return terminal.getCursorPosition();
};

const setStatus: CommandHandler<{ status: COMPUTER_STATES }> = async (
  computer,
  payload
) => {
  computer.treeItem.setStatus(payload.status);
};

const killTerminal: CommandHandler<{ shellID: number }> = async (
  computer,
  payload
) => {
  const shell = computer.getShell(payload.shellID);
  if (!shell) throw new Error(`shell ${payload.shellID} does not exist`);
  shell.terminated = true;
};

const fileChange: CommandHandler<
  {
    type: vscode.FileChangeType;
    path: string;
  }[]
> = async (computer, payload) => {
  computer.triggerFileChange(
    payload.map((v) => {
      return {
        type: v.type,
        uri: vscode.Uri.from({
          scheme: "ccLink",
          authority: computer.uuid,
          path: v.path,
        }),
      };
    })
  );
};

const listFiles: CommandHandler<string[]> = async (computer, payload) => {
  const URIs = payload.map((path) =>
    vscode.Uri.from({
      scheme: "ccLink",
      authority: computer.uuid,
      path: `/${path}`,
    })
  );

  computer.remoteFiles = URIs;
  computer.treeItem.updateRemoteFileTreeNode(URIs);
  computer.treeItem.update();
};

export default {
  ping,
  writeTerminal,
  getTerminalSize,
  getTerminalCursorPos,
  setStatus,
  killTerminal,
  fileChange,
  listFiles,
};
