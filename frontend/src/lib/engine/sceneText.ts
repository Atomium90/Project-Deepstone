import type { PendingSceneView } from "./protocol";

/** Finds the text of a key, or null when no language file has it. */
export type TextLookup = (key: string) => string | null;

/** The yes and no that end a conversation offering a vow, and what the NPC answers to each. */
export interface VowOfferTexts {
    accept: string;
    decline: string;
    replyAccept: string;
    replyDecline: string;
}

/** What the player reads in a conversation, resolved from the language file. */
export interface SceneScript {
    speaker: string;
    /** One text per bubble, in order. */
    lines: string[];
    /** Present when the conversation ends by offering a vow. */
    offer: VowOfferTexts | null;
}

/** The last short line each NPC said, so a visit never repeats the previous one. */
const lastIdleLines = new Map<string, string>();

/** The texts under `${prefix}.1`, `${prefix}.2` and so on, up to the first one that is missing. */
export function numberedTexts(prefix: string, lookup: TextLookup): string[] {
    const texts: string[] = [];
    for (let n = 1; ; n++) {
        const text = lookup(`${prefix}.${n}`);
        if (text === null) return texts;
        texts.push(text);
    }
}

/**
 * A short line for an NPC with nothing new to say: one of the general ones, or one of the pool the
 * last run calls for (after a death, after a victory), never the one said last time when another
 * exists. Records the line it picks.
 */
function idleLines(scene: PendingSceneView, lookup: TextLookup, random: () => number, last: Map<string, string>): string[] {
    const pools = [`story.${scene.npc}.idle.general`];
    if (scene.context && scene.context !== "general") pools.push(`story.${scene.npc}.idle.${scene.context}`);
    const candidates = pools.flatMap((pool) => numberedTexts(pool, lookup));
    if (candidates.length === 0) return [];

    const fresh = candidates.filter((line) => line !== last.get(scene.npc));
    const pool = fresh.length > 0 ? fresh : candidates;
    const line = pool[Math.floor(random() * pool.length)];
    last.set(scene.npc, line);
    return [line];
}

/** The key a conversation's lines are read under, which is also what shows when none exists. */
function linesKey(scene: PendingSceneView): string {
    switch (scene.kind) {
        case "scene":
            return `story.${scene.sceneId}`;
        case "vow_report":
            return `story.${scene.npc}.vow.${scene.kept ? "kept" : "failed"}`;
        case "idle":
            return `story.${scene.npc}.idle`;
    }
}

/**
 * Turns the conversation the server describes into the texts to show. Lines come from the keys the
 * server's description points at (see PendingSceneView); a conversation whose texts are missing
 * shows its key instead of nothing, so the gap is visible and the player is never stuck.
 *
 * @param random Picks the short line of an idle conversation, 0 up to 1 exclusive.
 * @param last The memory of the short lines already said, one per NPC.
 */
export function buildScript(
    scene: PendingSceneView,
    lookup: TextLookup,
    random: () => number = Math.random,
    last: Map<string, string> = lastIdleLines,
): SceneScript {
    const key = linesKey(scene);
    const lines =
        scene.kind === "idle"
            ? idleLines(scene, lookup, random, last)
            : numberedTexts(key, lookup);

    const text = (textKey: string) => lookup(textKey) ?? textKey;
    const offerScene = scene.offerScene;
    return {
        speaker: lookup(`story.${scene.npc}.name`) ?? scene.npc,
        lines: lines.length > 0 ? lines : [key],
        offer: offerScene
            ? {
                  accept: text(`story.${offerScene}.choice.accept`),
                  decline: text(`story.${offerScene}.choice.decline`),
                  replyAccept: text(`story.${offerScene}.reply.accept`),
                  replyDecline: text(`story.${offerScene}.reply.decline`),
              }
            : null,
    };
}
