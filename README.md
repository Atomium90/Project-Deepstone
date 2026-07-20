# Roguelite Dungeon Crawler

A roguelite dungeon crawler built without a game engine.
Inspired by Children of Morta (dungeon + meta-progression) and Moonlighter (hub between runs).

## Tech stack

| Layer    | Technology                            |
|----------|---------------------------------------|
| Backend  | Scala 3, http4s, Circe                |
| Frontend | Svelte, TypeScript, Vite, HTML Canvas |
| Protocol | JSON over WebSocket                   |
| Database | SQLite (to be added later)            |
| Testing  | MUnit + munit-cats-effect             |

## Project structure

```
roguelite/
  backend/    # Scala http4s server — authoritative game state
  frontend/   # Svelte + Canvas client — rendering and input only
```

## Getting started

### Quick start (Windows)

```powershell
./run-dev.ps1
```

Opens the backend and frontend dev servers in their own windows. Use the manual
steps below if you only need one of the two, or if you're not on Windows.

### Backend

Requires: Java 17+, sbt 1.9+

```bash
cd backend
sbt run          # starts the server on ws://localhost:8080/ws
sbt test         # run all tests
```

### Frontend

Requires: Node 18+

```bash
cd frontend
npm install
npm run dev      # starts Vite dev server on http://localhost:5173
```

The Vite dev server proxies `/ws` to `localhost:8080`, so both can run simultaneously without CORS issues.

## Architecture

The server is the single source of truth. The client sends `PlayerAction` messages and receives full `StateUpdate` snapshots in response. No game logic lives on the client.

```
Client (Svelte)                  Server (Scala)
────────────────                 ──────────────────────────
User input
  → GameClient.send()
  → JSON PlayerAction   ──────→  WebSocketRouter
                                   → MessageProtocol.decodeAction()
                                   → StateMachine.transition()
                                       → new GameState
  ← JSON StateUpdate    ←──────  → MessageProtocol.encodeUpdate()
  → gameState (store)
  → UI re-renders
```