import z from "zod";
import * as vscode from "vscode";

export type Response<T> = {
  serverId: number;
  data: T;
  success: boolean;
};
export const response = z.object({
  serverId: z.number().int(),
  data: z.any(),
  success: z.boolean(),
});

export const request = z.object({
  clientId: z.number().int(),
  data: z.any(),
});

export const RPC = z.object({
  command: z.string(),
  payload: z.any(),
});

export const filesystemErrorResponse = z.object({
  error: z.number().int(),
  path: z.string(),
});

export type Command = keyof RpcCommands;
export type RpcPayload<Command extends keyof RpcCommands> =
  RpcCommands[Command]["input"];
export type RpcResponse<Command extends keyof RpcCommands> =
  RpcCommands[Command]["output"];
export interface RpcCommands {
  copyFile: {
    input: { source: string; destination: string; overwrite?: boolean };
    output: void;
  };
  createDirectory: {
    input: { path: string };
    output: void;
  };
  delete: {
    input: { path: string; recursive?: boolean };
    output: void;
  };
  readDirectory: {
    input: { path: string };
    output: [string, vscode.FileType][];
  };
  readFile: {
    input: { path: string };
    output: string;
  };
  rename: {
    input: { oldPath: string; newPath: string; overwrite?: boolean };
    output: void;
  };
  stat: {
    input: { path: string };
    output: vscode.FileStat;
  };
  stopWatch: {
    input: { path: string };
    output: void;
  };
  watch: {
    input: { path: string; recursive: boolean; excludes: readonly string[] };
    output: void;
  };
  writeFile: {
    input: {
      path: string;
      content: string;
      create: boolean;
      overwrite?: boolean;
    };
    output: void;
  };

  ping: {
    input: number;
    output: number;
  };
  listFiles: {
    input: void;
    output: string[];
  };
  restart: {
    input: void;
    output: void;
  };

  createShell: {
    input: void;
    output: {
      shellID: number;
      backgroundColor: { r: number; g: number; b: number };
    };
  };
  keyToShell: {
    input: { shellID: number; key: string };
    output: void;
  };
  killShell: {
    input: { shellID: number };
    output: void;
  };
  readyShell: {
    input: { shellID: number };
    output: void;
  };
  resizeShell: {
    input: { shellID: number; columns: number; rows: number };
    output: void;
  };
}

export const responses = {
  copyFile: z.void(),
  createDirectory: z.void(),
  delete: z.void(),
  readDirectory: z.array(z.tuple([z.string(), z.nativeEnum(vscode.FileType)])),
  readFile: z.string(),
  rename: z.void(),
  stat: z.object({
    type: z.nativeEnum(vscode.FileType),
    ctime: z.number().int(),
    mtime: z.number().int(),
    size: z.number().int(),
    permissions: z.nativeEnum(vscode.FilePermission).optional(),
  }),
  stopWatch: z.void(),
  watch: z.void(),
  writeFile: z.void(),

  ping: z.number().int(),
  listFiles: z.array(z.string()),
  restart: z.void(),

  createShell: z.object({
    shellID: z.number().int(),
    backgroundColor: z.object({
      r: z.number().int(),
      g: z.number().int(),
      b: z.number().int(),
    }),
  }),
  keyToShell: z.void(),
  killShell: z.void(),
  readyShell: z.void(),
  resizeShell: z.void(),
};
