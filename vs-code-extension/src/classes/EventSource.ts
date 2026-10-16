/** Name of the event that can be fired and listened to */
type EventName = string;
/** Callback function to execute when the event is received */
type EventCallback = (...args: any) => any | Promise<any>;
/** List of events that can be listened to */
type EventList = { [key: EventName]: EventCallback };

export class EventSource<Events extends EventList> {
  private eventListeners: { [K in keyof Events]: Events[K][] };

  constructor() {
    this.eventListeners = {} as { [K in keyof Events]: Events[K][] };
  }

  /**
   * Listens for an event, executing the callback function when the event is fired.
   *
   * @param eventName Name of the event to listen for.
   * @param callback Function to call when event is fired.
   * @throws If `eventName` is not a string.
   * @throws If `callback` is not a `function`.
   */
  on<T extends keyof Events>(eventName: T, callback: Events[T]) {
    if (!this.eventListeners[eventName]) {
      this.eventListeners[eventName] = [];
    }

    if (typeof callback === "function") {
      this.eventListeners[eventName].push(callback);
    } else {
      throw new Error("callback is not a function");
    }
  }

  /**
   * Fires an event which can be listened to using {@link on|`on()`}.
   *
   * The first registered listener that returns will forward to the caller of this function:
   * ```ts
   * const firstResult = this.dispatch("example");
   * ```
   */
  dispatch<T extends keyof Events>(
    eventName: T,
    ...data: Parameters<Events[T]>
  ): Promise<unknown> {
    let ret;
    let first = true;
    if (this.eventListeners[eventName]) {
      for (const listener of this.eventListeners[eventName]) {
        if (first) {
          first = false;
          ret = listener(...data);
        } else {
          listener(...data);
        }
      }
    }
    return ret;
  }
}
