export class RPCCommandError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "RPCCommandError";
    Object.setPrototypeOf(this, RPCCommandError.prototype);
  }
}
