import type { PlayerAction, StateUpdate } from "./protocol";

type UpdateCallback = (update: StateUpdate) => void;
type ErrorCallback = (event: Event) => void;

/** The WebSocket of the backend as the dev setup runs it: the Vite dev server serves the page, and
 * the backend is started on 8080 (run-dev.ps1 and the Playwright config both pass `--port 8080`). */
export const DEV_SERVER_URL = "ws://127.0.0.1:8080/ws";

/** Where the game server's WebSocket is.
 *
 * In the packaged game the backend serves the page itself, so the page and the WebSocket share a
 * port, and that port is not always 8080 (the backend moves to the next free one when 8080 is
 * taken): it is read from the address the page was loaded from. Under the Vite dev server the page
 * comes from another port, so the dev address is used instead.
 *
 * The host is 127.0.0.1, not "localhost": the backend binds IPv4 only, and Windows can resolve
 * "localhost" to IPv6 first, silently breaking the WebSocket connection.
 */
export function serverUrl(pagePort: string, isDevServer: boolean): string {
  if (isDevServer) return DEV_SERVER_URL;
  return `ws://127.0.0.1:${pagePort || "80"}/ws`;
}

function defaultServerUrl(): string {
  if (typeof window === "undefined") return DEV_SERVER_URL;
  return serverUrl(window.location.port, import.meta.env.DEV);
}

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

  constructor(url: string = defaultServerUrl()) {
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