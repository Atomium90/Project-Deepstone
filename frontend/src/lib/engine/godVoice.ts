import type { GodLineView } from "./protocol";
import { numberedTexts, type TextLookup } from "./sceneText";

/** The last line said for each kind of moment, so the voice never says the same one twice in a row
 * when it has another. A death line and a run-start line are remembered apart. */
const lastLines = new Map<string, string>();

/** Which memory a moment belongs to: every death line follows the last death line, whatever its case. */
function memoryKey(line: GodLineView): string {
    return line.moment === "start" ? "start" : "death";
}

/**
 * The text of the line the god's voice says, picked among the ones the language file has for this
 * moment and tone (`story.god.<moment>.<phase>.<n>`), never the one said last time when another
 * exists. Records the line it picks. Returns null when the file has none: the voice then says
 * nothing, which costs the player nothing, unlike a conversation they would be stuck in.
 *
 * @param random Picks the line, 0 up to 1 exclusive.
 * @param last The memory of the lines already said.
 */
export function pickGodLine(
    line: GodLineView,
    lookup: TextLookup,
    random: () => number = Math.random,
    last: Map<string, string> = lastLines,
): string | null {
    const candidates = numberedTexts(`story.god.${line.moment}.${line.phase}`, lookup);
    if (candidates.length === 0) return null;

    const fresh = candidates.filter((text) => text !== last.get(memoryKey(line)));
    const pool = fresh.length > 0 ? fresh : candidates;
    const text = pool[Math.floor(random() * pool.length)];
    last.set(memoryKey(line), text);
    return text;
}
