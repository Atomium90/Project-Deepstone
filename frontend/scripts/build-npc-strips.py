"""Builds the idle animation strip of each NPC from the individual frames of the
"16x16 Fantasy RPG Characters" pack by Superdark (CC0, see CREDITS.md).

The pack ships one 32x32 PNG per frame. The game wants one horizontal strip per character
(4 frames side by side, 128x32), the same layout as the enemy sheets, which public/atlas/entities.json
then describes (see generate-npc-atlas.mjs).

The art is not kept in this repository (see CREDITS.md and sync-assets.ps1): this writes the strips
into public/sprites/entities/npcs/, which git ignores, ready to be copied to the private assets repo.

Needs Pillow. Run from the frontend folder, naming the pack's zip file:
    python scripts/build-npc-strips.py "<path>/Fantasy RPG NPCs - Individuel Frames.zip"
"""

import io
import sys
import zipfile
from pathlib import Path

from PIL import Image

FRAME = 32
FRAMES = 4

# strip file name -> (folder of the pack, file name pattern of its frames, {n} is 1 to 4)
STRIPS = {
    "townsfolk_male_idle": ("Townsfolk - Male", "Townsfolk_M_Idle_{n}.png"),
    "executioner_idle": ("Executioner", "Executioner_Idle_{n}.png"),
    # The bishop has no walk cycle of its own: its four frames serve for both.
    "bishop_idle": ("Bishop", "Bishop_Idle + Walk_{n}.png"),
    "herald_idle": ("Herald", "Herald_Idle_{n}.png"),
    "archer_idle": ("Archer", "Archer_Idle_{n}.png"),
}

PACK_ROOT = "Fantasy RPG NPCs - Individuel Frames/Fantasy RPG NPCs - Individuel Frames"
OUT_DIR = Path(__file__).resolve().parent.parent / "public" / "sprites" / "entities" / "npcs"


def main(zip_path: str) -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(zip_path) as pack:
        for name, (folder, pattern) in STRIPS.items():
            strip = Image.new("RGBA", (FRAME * FRAMES, FRAME), (0, 0, 0, 0))
            for n in range(1, FRAMES + 1):
                frame = Image.open(io.BytesIO(pack.read(f"{PACK_ROOT}/{folder}/{pattern.format(n=n)}"))).convert("RGBA")
                if frame.size != (FRAME, FRAME):
                    raise SystemExit(f"{folder} frame {n} is {frame.size}, expected {FRAME}x{FRAME}")
                strip.paste(frame, ((n - 1) * FRAME, 0))
            strip.save(OUT_DIR / f"{name}.png")
            print(f"{name}.png <- {folder}")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    main(sys.argv[1])
