# Deepstone

A roguelite dungeon crawler built without a game engine.
Inspired by Children of Morta (dungeon + meta-progression) and Moonlighter (hub between runs).

## Tech stack

| Layer    | Technology                                                    |
|----------|-----------------------------------------------------------------|
| Backend  | Scala 3, http4s, Circe, Cats Effect 3                          |
| Frontend | Svelte, TypeScript, Vite, HTML Canvas                          |
| Protocol | JSON over WebSocket                                            |
| Database | SQLite (Doobie + HikariCP)                                     |
| Testing  | MUnit + munit-cats-effect (backend); svelte-check (frontend)   |

## Project structure

```
deepstone/
  deepstone-backend/  # Scala http4s server, authoritative game state
  frontend/           # Svelte + Canvas client, rendering and input only
```

## Getting started

### Quick start (Windows)

```powershell
./run-dev.ps1
```

Opens the backend and frontend dev servers as tabs in one Windows Terminal window,
then opens the game in your browser once both are ready. Run `./stop-dev.ps1` to
stop both at once. Use the manual steps below if you only need one of the two, or
if you're not on Windows.

### Backend

Requires: Java 17+, sbt 1.9+

```bash
cd deepstone-backend
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
                                   → GameSession.handle()
                                       → StateMachine.applyActionPure()
                                       → new GameState
  ← JSON StateUpdate    ←──────  → MessageProtocol.encodeUpdate()
  → gameState (store)
  → UI re-renders
```

## Content authoring: previewing a Tiled room

Rooms are authored in [Tiled](https://www.mapeditor.org/) and converted to `rooms.json` entries by
`frontend/scripts/convert-tiled-room.mjs` (full setup instructions - map properties, layer names,
tile/entity custom properties - are documented at the top of that script). Once you've built a
room in Tiled, you can load it straight into a live session to check it before touching
`rooms.json` at all:

```bash
cd frontend
node scripts/convert-tiled-room.mjs path/to/your-room.tmx
```

(No sprites assigned yet? No flag needed for that - every cell's sprite key is derived
automatically, and a tileset with no matching atlas just prints a warning and renders as a flat
fallback color client-side, so you can check geometry/doors/entities right away regardless.)

The converted file lands in `deepstone-backend/debug-rooms/`, inside the same subfolder the map has
in the Tiled project (a map in `Tiled/darkDungeon/` goes to `debug-rooms/darkDungeon/`). A tileset
file stays in the Tiled project folder, even for a map moved into a subfolder.

With the backend running (`sbt run` / `run-dev.ps1`), the Hub screen shows a "⚠ Debug Rooms
(dev only)" row listing every room file found anywhere under `deepstone-backend/debug-rooms/`
(subfolders included, listed by file name) - click one to
drop straight into that single room, bypassing the normal dungeon generation entirely. The folder
is read fresh on every click, so you can re-run the conversion command above and click the button
again to see your latest changes with no server restart needed - convert a batch of rooms into
that folder up front and switch between them freely while iterating. `debug-rooms/` is gitignored:
it's a local scratch folder for previewing content, never real, shippable game data. Once a room
is finished, delete its file from `debug-rooms/` and paste its JSON into `rooms.json` instead.

## Credits

Third-party art assets and their licenses are listed in [CREDITS.md](CREDITS.md).