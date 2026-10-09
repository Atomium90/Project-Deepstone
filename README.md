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

Requires: Java 17+, sbt 1.12+

```bash
cd deepstone-backend
sbt "run --port 8080"   # starts the server on ws://localhost:8080/ws (the dev page expects 8080)
sbt test                # run all tests
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

This tooling is off by default. Start the backend in debug mode with `./run-dev.ps1 --debug`, or
`sbt "run --debug"` from `deepstone-backend/` (a packaged build takes the same `--debug` argument);
a plain `./run-dev.ps1` or `sbt run` runs the normal game, with none of it exposed. In debug mode the
Hub screen shows a "⚠ Debug Rooms
(dev only)" row listing every room file found anywhere under `deepstone-backend/debug-rooms/`
(subfolders included, listed by file name) - click one to
drop straight into that single room, bypassing the normal dungeon generation entirely. The folder
is read fresh on every click, so you can re-run the conversion command above and click the button
again to see your latest changes with no server restart needed - convert a batch of rooms into
that folder up front and switch between them freely while iterating. `debug-rooms/` is gitignored:
it's a local scratch folder for previewing content, never real, shippable game data. Once a room
is finished, delete its file from `debug-rooms/` and paste its JSON into `rooms.json` instead.

## Choosing the save file

The save is `deepstone.db` in your own data folder, so it is the same wherever the game is unzipped
or started from:

- Windows: `%APPDATA%\Deepstone`
- macOS: `~/Library/Application Support/Deepstone`
- Linux: `$XDG_DATA_HOME/deepstone`, or `~/.local/share/deepstone`

The first time, a `deepstone.db` found in the folder the game is started from (where earlier
versions wrote it) is copied there. The old file is left in place, and a save already in the data
folder is never overwritten. A save kept elsewhere can simply be copied into the data folder.

To use another file, pass `--db <path>` (for example `sbt "run --db target/other.db"`, or the same
argument on a packaged build): the file is created if it does not exist, and your own save is left
alone. The end-to-end tests use this to play on a database of their own.

## Choosing the port

Without `--port`, the server listens on the first free port from 8080 to 8089 and prints the address
to open, for example `Deepstone is running. Open http://localhost:8081 in your browser.` The page
finds the server on the port it was loaded from, so nothing else needs changing.

`--port <number>` asks for one port, and the server stops with an explanation if another program
holds it, rather than moving elsewhere. The dev setup (`run-dev.ps1`, the end-to-end tests) passes
`--port 8080`, because the Vite dev page is wired to that port.

## Art and audio

The game's art and audio are the work of other people and are not stored in this repo, see
[CREDITS.md](CREDITS.md). They live in a private repo, `deepstone-licensed-assets`, which holds the
files the game uses in the layout of `frontend/public/`. To have them locally, clone that repo next
to this one and run `.\sync-assets.ps1` from the root of this repo (`-WhatIf` shows what it would
do, `-Prune` also removes the files the game does not use).

Without them the game still runs, with plain shapes instead of sprites and interface skins (the
health and resource bars show only their numbers) and no sound, which is all the tests and the CI
need. `npm run audit:assets` in `frontend/` lists the files the game
refers to and fails when one is missing. A release is built by the release workflow, or locally by
`build-release.ps1`, which refuses to build without the assets.

## Credits

Third-party art and audio, and their licenses, are listed in [CREDITS.md](CREDITS.md) and in the
Credits tab of the Character screen in the game.

## Security

To report a vulnerability, see [SECURITY.md](SECURITY.md).