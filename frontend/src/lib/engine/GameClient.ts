import type { PlayerAction, StateUpdate } from "./protocol";

type UpdateCallback = (update: StateUpdate) => void;
type ErrorCallback = (event: Event) => void;

/** Thin wrapper around the browser WebSocket API.
 *
 * Handles connection lifecycle and JSON serialization. Contains no game logic.
 * Call `connect()` once; use `send()` to dispatch actions; register a callback
 * with `onStateUpdate()` to receive server snapshots.
 */
export class GameClient {
  private socket: WebSocket | null = null;
  private updateCallback: UpdateCallback | null = null;
  private errorCallback: ErrorCallback | null = null;
  private readonly url: string;

  constructor(url: string = "ws://localhost:8080/ws") {
    this.url = url;
  }

  /** Open the WebSocket connection to the game server. */
  connect(): void {
    if (this.socket?.readyState === WebSocket.OPEN) {
      console.warn("[GameClient] Already connected.");
      return;
    }

    this.socket = new WebSocket(this.url);

    this.socket.onopen = () => {
      console.info("[GameClient] Connected to server.");
    };

    this.socket.onmessage = (event: MessageEvent) => {
      try {
        const update: StateUpdate = JSON.parse(event.data as string);
        this.updateCallback?.(update);
      } catch (err) {
        console.error("[GameClient] Failed to parse server message:", err, event.data);
      }
    };

    this.socket.onerror = (event: Event) => {
      console.error("[GameClient] WebSocket error:", event);
      this.errorCallback?.(event);
    };

    this.socket.onclose = () => {
      console.info("[GameClient] Connection closed.");
    };
  }

  /** Send a player action to the server. */
  send(action: PlayerAction): void {
    if (this.socket?.readyState !== WebSocket.OPEN) {
      console.warn("[GameClient] Cannot send — socket is not open.");
      return;
    }
    this.socket.send(JSON.stringify(action));
  }

  /** Register a callback to be called on every StateUpdate from the server. */
  onStateUpdate(callback: UpdateCallback): void {
    this.updateCallback = callback;
  }

  /** Register a callback to be called on WebSocket errors. */
  onError(callback: ErrorCallback): void {
    this.errorCallback = callback;
  }

  /** Close the connection gracefully. */
  disconnect(): void {
    this.socket?.close();
    this.socket = null;
  }
}