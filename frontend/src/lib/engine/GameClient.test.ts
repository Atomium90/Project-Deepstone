import { describe, test, expect, beforeEach, vi } from "vitest";
import { GameClient } from "./GameClient";

/** jsdom has no real WebSocket implementation, and a real socket wouldn't be deterministic in a
 * unit test anyway - this fake gives GameClient exactly the surface it depends on (readyState,
 * the on* handler slots, send/close) with the open/close transitions driven by hand from the
 * test. Every real instance the test creates is captured on FakeWebSocket.instances so a test can
 * reach in and simulate a server message/error/close.
 */
class FakeWebSocket {
    static OPEN = 1;
    static CONNECTING = 0;
    static instances: FakeWebSocket[] = [];

    readyState = FakeWebSocket.CONNECTING;
    onopen: (() => void) | null = null;
    onmessage: ((event: { data: string }) => void) | null = null;
    onerror: ((event: Event) => void) | null = null;
    onclose: (() => void) | null = null;
    sent: string[] = [];

    constructor(public url: string) {
        FakeWebSocket.instances.push(this);
    }

    send(data: string): void {
        this.sent.push(data);
    }

    close(): void {
        this.readyState = 3; // CLOSED
        this.onclose?.();
    }

    open(): void {
        this.readyState = FakeWebSocket.OPEN;
        this.onopen?.();
    }
}

describe("GameClient", () => {
    beforeEach(() => {
        FakeWebSocket.instances = [];
        vi.stubGlobal("WebSocket", FakeWebSocket);
    });

    test("connect() opens a socket at the configured url", () => {
        const client = new GameClient("ws://example/ws");
        client.connect();
        expect(FakeWebSocket.instances).toHaveLength(1);
        expect(FakeWebSocket.instances[0].url).toBe("ws://example/ws");
    });

    test("connect() is a no-op when already connected", () => {
        const client = new GameClient();
        client.connect();
        FakeWebSocket.instances[0].open();
        client.connect();
        expect(FakeWebSocket.instances).toHaveLength(1);
    });

    test("send() serializes the action as JSON once the socket is open", () => {
        const client = new GameClient();
        client.connect();
        FakeWebSocket.instances[0].open();
        client.send({ type: "MOVE", direction: "UP" });
        expect(FakeWebSocket.instances[0].sent).toEqual([JSON.stringify({ type: "MOVE", direction: "UP" })]);
    });

    test("send() is a silent no-op when the socket isn't open yet", () => {
        const client = new GameClient();
        client.connect();
        client.send({ type: "MOVE", direction: "UP" });
        expect(FakeWebSocket.instances[0].sent).toEqual([]);
    });

    test("onStateUpdate callback fires with the parsed StateUpdate on a valid message", () => {
        const client = new GameClient();
        const received: unknown[] = [];
        client.onStateUpdate((u) => received.push(u));
        client.connect();
        FakeWebSocket.instances[0].onmessage?.({ data: JSON.stringify({ phase: "HUB" }) });
        expect(received).toEqual([{ phase: "HUB" }]);
    });

    test("a malformed server message is swallowed, not thrown, and never reaches the callback", () => {
        const client = new GameClient();
        const received: unknown[] = [];
        client.onStateUpdate((u) => received.push(u));
        client.connect();
        expect(() => FakeWebSocket.instances[0].onmessage?.({ data: "not json" })).not.toThrow();
        expect(received).toEqual([]);
    });

    test("onError callback fires on a socket error", () => {
        const client = new GameClient();
        let called = false;
        client.onError(() => (called = true));
        client.connect();
        FakeWebSocket.instances[0].onerror?.(new Event("error"));
        expect(called).toBe(true);
    });

    test("disconnect() closes the socket and clears the reference", () => {
        const client = new GameClient();
        client.connect();
        const socket = FakeWebSocket.instances[0];
        socket.open();
        client.disconnect();
        expect(socket.readyState).toBe(3);
        // A second connect() after disconnect must open a fresh socket, not treat the old one as live.
        client.connect();
        expect(FakeWebSocket.instances).toHaveLength(2);
    });
});
