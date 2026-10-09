# Security policy

Deepstone is a single-player game. The download starts a small server on the player's own
computer, which only accepts connections from that computer, and the game runs in the player's
browser against it. There are no accounts, no online services and no personal data.

## Supported versions

Only the latest release gets fixes.

## Reporting a vulnerability

Please do not open a public issue for a security problem. Report it privately through GitHub:

https://github.com/Atomium90/Project-Deepstone/security/advisories/new

Say what you found, how to reproduce it and which version you tested. This is a spare-time
project, so a reply can take a few days, but every report is read, and you will be told what is
decided. If you wish, you will be credited once the fix is released.

## What counts

- The game server and the page it serves, as they are in the downloadable build
- The release packages and the way they are built and published
- The dependencies that ship in the download

## What does not count

- Cheating in the single-player game. The server trusts the player's own client, there is no
  leaderboard, and a player can edit their own save.
- Anything that needs the attacker to be already running code on the player's computer.
- Tools used only to develop the game and not shipped in the download, such as the dev server and
  the test tooling.

A version of the game hosted on a public server, or with online features, is not offered today.
It would be reviewed separately before it exists.
