"""Builds the idle animation strip of each NPC from the individual frames of the
"16x16 Fantasy RPG Characters" pack by Superdark (CC0, see CREDITS.md).

The pack ships one 32x32 PNG per frame. The game wants one horizontal strip per character
(4 frames side by side), which public/atlas/entities.json then describes (see generate-npc-atlas.mjs).

Each frame of a strip sits in a CELL x CELL cell, bigger than the pack's 32x32 frame: the Renderer draws
an NPC at 2 screen pixels per sprite pixel, standing on the bottom edge of its tile and centred on it
(see characterBox.ts), so a cell bigger than a tile lets a big character rise above its tile and spill
over its sides. With a 32x32 cell the executioner, who fills 30 of its 32 rows, could never grow.

The art is not kept in this repository (see CREDITS.md and sync-assets.ps1): this writes the strips
into public/sprites/entities/npcs/, which git ignores, ready to be copied to the private assets repo.

Each strip can also be scaled (SCALES below). The pack draws an ordinary villager 19 px tall in its
32x32 frame, against 27 for the bishop and 30 for the executioner and for the game's enemies, so next
to a player or an orc the villagers looked like children. The scale is applied to all 4 frames of a
strip alike, with nearest-neighbor so the pixels stay hard, around the bottom centre of the cell so
the feet stay on the same row and the body on the same column. The strips are the only copy of the art
the game keeps, so the scaling lives here, in the one place that rebuilds them from the pack.

Needs Pillow. Run from the frontend folder, naming the pack's zip file:
    python scripts/build-npc-strips.py "<path>/Fantasy RPG NPCs - Individuel Frames.zip"
"""

import io
import sys
import zipfile
from pathlib import Path

from PIL import Image

FRAME = 32  # a frame of the pack
CELL = 48  # a cell of a strip: keep in step with FRAME in generate-npc-atlas.mjs
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

# strip file name -> scale of its frames (1 = as drawn in the pack). A strip left out is not scaled.
# The largest scale a 32x32 frame can take in a CELL x CELL cell is CELL / FRAME. Chosen by eye, in the
# debug room that lines the cast up next to an orc and the player: the villagers stand at about the
# height of the player, the bishop a little above them (his mitre is most of his height, a full 1.5
# would make him nearly as tall as the executioner), and the executioner, broader, is the biggest.
SCALES: dict[str, float] = {
    "townsfolk_male_idle": 1.5,
    "herald_idle": 1.5,
    "archer_idle": 1.5,
    "bishop_idle": 1.25,
    "executioner_idle": 1.5,
}

PACK_ROOT = "Fantasy RPG NPCs - Individuel Frames/Fantasy RPG NPCs - Individuel Frames"
OUT_DIR = Path(__file__).resolve().parent.parent / "public" / "sprites" / "entities" / "npcs"


def opaque_pixels(image: Image.Image) -> int:
    return sum(1 for alpha in image.getchannel("A").getdata() if alpha)


def to_cell(frame: Image.Image, scale: float) -> Image.Image:
    """The frame scaled, in a CELL x CELL cell, standing on its bottom edge and centred."""
    size = round(FRAME * scale)
    scaled = frame if size == FRAME else frame.resize((size, size), Image.NEAREST)
    cell = Image.new("RGBA", (CELL, CELL), (0, 0, 0, 0))
    cell.paste(scaled, ((CELL - size) // 2, CELL - size))
    if opaque_pixels(cell) != opaque_pixels(scaled):
        raise SystemExit(f"scale {scale} pushes part of a sprite out of its {CELL}x{CELL} cell")
    return cell


def main(zip_path: str, out_dir: Path = OUT_DIR, scales: dict[str, float] = SCALES) -> None:
    out_dir.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(zip_path) as pack:
        for name, (folder, pattern) in STRIPS.items():
            strip = Image.new("RGBA", (CELL * FRAMES, CELL), (0, 0, 0, 0))
            for n in range(1, FRAMES + 1):
                frame = Image.open(io.BytesIO(pack.read(f"{PACK_ROOT}/{folder}/{pattern.format(n=n)}"))).convert("RGBA")
                if frame.size != (FRAME, FRAME):
                    raise SystemExit(f"{folder} frame {n} is {frame.size}, expected {FRAME}x{FRAME}")
                strip.paste(to_cell(frame, scales.get(name, 1)), ((n - 1) * CELL, 0))
            strip.save(out_dir / f"{name}.png")
            print(f"{name}.png <- {folder}" + (f" x{scales[name]:.3f}" if scales.get(name, 1) != 1 else ""))


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    main(sys.argv[1])
