import * as vscode from "vscode";

/** Get the full URI of this host, e.g. http://localhost:56560/ */
const getHostURI = (): vscode.Uri => {
  const config = vscode.workspace.getConfiguration("ccLink");

  let host = config.get("installScript.host", "localhost");
  const port = config.get("websocket.port", 56560);

  if (!host.includes(":")) {
    host += `:${port}`;
  }

  return vscode.Uri.parse("http://" + host);
};

const prependGlobalGlobs = (globs: string[]): string[] => {
  const config = vscode.workspace.getConfiguration("ccLink");

  const globalGlobs = config.get<string[]>("workspace.globalGlobs", []);
  return globalGlobs.concat(globs);
};

export default { getHostURI, prependGlobalGlobs };
