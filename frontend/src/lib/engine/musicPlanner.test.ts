import { describe, expect, it } from "vitest";
import { MusicPlanner, REROLL_AFTER_UNITS, type MusicContext, type MusicPools } from "./musicPlanner";

const POOLS: MusicPools = {
  hub: ["hub_a", "hub_b", "hub_c"],
  combat: ["combat_a", "combat_b"],
  boss: ["boss_a", "boss_b"],
  exploration: [
    ["first_a", "first_b"],
    ["second_a", "second_b", "second_c"],
    ["third_a", "third_b"],
  ],
};

/** A planner whose dice always give the first choice left, so a test reads without luck. */
function planner(): MusicPlanner {
  return new MusicPlanner(POOLS, () => 0);
}

const exploring = (section: number | null): MusicContext => ({
  phase: "EXPLORATION",
  bossFight: false,
  eliteFight: false,
  section,
});
const fight = (extra: Partial<MusicContext> = {}): MusicContext => ({
  phase: "COMBAT",
  bossFight: false,
  eliteFight: false,
  section: null,
  ...extra,
});
const hub: MusicContext = { phase: "HUB", bossFight: false, eliteFight: false, section: null };
const gameOver: MusicContext = { ...hub, phase: "GAMEOVER" };

describe("MusicPlanner exploration", () => {
  it("plays a track of the first pool in the first section, the second pool in the second, and so on", () => {
    const p = planner();
    p.choose(hub);
    expect(POOLS.exploration[0]).toContain(p.choose(exploring(0))?.file);
    expect(POOLS.exploration[1]).toContain(p.choose(exploring(1))?.file);
    expect(POOLS.exploration[2]).toContain(p.choose(exploring(2))?.file);
  });

  it("repeats the last pool for a section deeper than the pools", () => {
    const p = planner();
    p.choose(hub);
    p.choose(exploring(2));
    expect(POOLS.exploration[2]).toContain(p.choose(exploring(5))?.file);
  });

  it("plays the first pool while the section is unknown", () => {
    const p = planner();
    p.choose(hub);
    expect(POOLS.exploration[0]).toContain(p.choose(exploring(null))?.file);
  });

  it("keeps the same track through every room of a section", () => {
    const p = planner();
    p.choose(hub);
    const first = p.choose(exploring(0));
    for (let i = 0; i < 12; i++) expect(p.choose(exploring(0))?.file).toBe(first?.file);
  });

  it("changes track when the player enters the next section", () => {
    const p = planner();
    p.choose(hub);
    const first = p.choose(exploring(0));
    const second = p.choose(exploring(1));
    expect(second?.file).not.toBe(first?.file);
    expect(second?.resume).toBe(false);
    expect(p.choose(exploring(1))?.file).toBe(second?.file);
  });

  it("changes track at every new section, whatever the pools hold", () => {
    const p = planner();
    p.choose(hub);
    let previous = p.choose(exploring(0))?.file;
    for (const section of [1, 2, 3, 4]) {
      const next = p.choose(exploring(section))?.file;
      expect(next).not.toBe(previous);
      previous = next;
    }
  });

  it("starts a fresh track at every new run, not the one of the run before", () => {
    const p = planner();
    p.choose(hub);
    const first = p.choose(exploring(0));
    p.choose(gameOver);
    p.choose(hub);
    const second = p.choose(exploring(0));
    expect(POOLS.exploration[0]).toContain(second?.file);
    expect(second?.file).not.toBe(first?.file);
  });

  it("does not change track when the section only becomes known after the run began", () => {
    const p = planner();
    p.choose(hub);
    const first = p.choose(exploring(null));
    expect(p.choose(exploring(0))?.file).toBe(first?.file);
  });

  it("keeps the section's track when the section is unknown for a while", () => {
    const p = planner();
    p.choose(hub);
    const first = p.choose(exploring(2));
    expect(p.choose(exploring(null))?.file).toBe(first?.file);
  });

  it("plays nothing when the pool of the section is empty", () => {
    const p = new MusicPlanner({ ...POOLS, exploration: [[], POOLS.exploration[1]] }, () => 0);
    p.choose(hub);
    expect(p.choose(exploring(0))).toBeNull();
    expect(POOLS.exploration[1]).toContain(p.choose(exploring(1))?.file);
  });
});

describe("MusicPlanner fights", () => {
  it("leaves the exploration track alone during an ordinary fight, and picks it up afterwards", () => {
    const p = planner();
    p.choose(hub);
    const before = p.choose(exploring(0));
    expect(p.choose(fight())?.file).toBe(before?.file);
    const after = p.choose(exploring(0));
    expect(after?.file).toBe(before?.file);
    expect(after?.resume).toBe(true);
  });

  it("does not count an ordinary fight as a room: the section's track survives many fights", () => {
    const p = planner();
    p.choose(hub);
    const first = p.choose(exploring(0));
    for (let i = 0; i < 10; i++) {
      p.choose(fight());
      expect(p.choose(exploring(0))?.file).toBe(first?.file);
    }
  });

  it("plays the combat pool against an Elite or a MiniBoss, then resumes the exploration track", () => {
    const p = planner();
    p.choose(hub);
    const before = p.choose(exploring(0));
    const elite = p.choose(fight({ eliteFight: true }));
    expect(POOLS.combat).toContain(elite?.file);
    expect(elite?.resume).toBe(false);
    const after = p.choose(exploring(0));
    expect(after).toEqual({ file: before?.file, resume: true });
  });

  it("plays the boss pool against a Boss", () => {
    const p = planner();
    p.choose(hub);
    p.choose(exploring(0));
    expect(POOLS.boss).toContain(p.choose(fight({ bossFight: true }))?.file);
  });

  it("prefers the boss track when a fight is flagged as both", () => {
    const p = planner();
    p.choose(hub);
    p.choose(exploring(0));
    expect(POOLS.boss).toContain(p.choose(fight({ bossFight: true, eliteFight: true }))?.file);
  });

  it("gives a run that starts straight in a fight a track of its own", () => {
    const p = planner();
    const choice = p.choose(fight());
    expect(POOLS.exploration[0]).toContain(choice?.file);
    expect(p.choose(exploring(0))?.file).toBe(choice?.file);
  });

  it("keeps the same boss track through the updates of one fight", () => {
    const p = planner();
    p.choose(hub);
    p.choose(exploring(0));
    const first = p.choose(fight({ bossFight: true }));
    for (let i = 0; i < 6; i++) expect(p.choose(fight({ bossFight: true }))?.file).toBe(first?.file);
  });

  it("plays a different boss track at every boss fight, all of them before one comes back", () => {
    const p = planner();
    p.choose(hub);
    const heard: (string | undefined)[] = [];
    for (let i = 0; i < 3; i++) {
      p.choose(exploring(0));
      heard.push(p.choose(fight({ bossFight: true }))?.file);
    }
    expect(new Set(heard).size).toBe(POOLS.boss.length);
    // the pool is used up: the next fight starts a new round, not on the track just heard
    p.choose(exploring(0));
    expect(p.choose(fight({ bossFight: true }))?.file).not.toBe(heard[2]);
  });

  it("plays a different combat track at every Elite or MiniBoss fight", () => {
    const p = planner();
    p.choose(hub);
    const heard: (string | undefined)[] = [];
    for (let i = 0; i < POOLS.combat.length; i++) {
      p.choose(exploring(0));
      heard.push(p.choose(fight({ eliteFight: true }))?.file);
    }
    expect(new Set(heard).size).toBe(POOLS.combat.length);
  });

  it("keeps boss and combat tracks apart: one pool's rounds do not use up the other's", () => {
    const p = planner();
    p.choose(hub);
    p.choose(exploring(0));
    const boss = p.choose(fight({ bossFight: true }))?.file;
    p.choose(exploring(0));
    const combat = p.choose(fight({ eliteFight: true }))?.file;
    expect(POOLS.boss).toContain(boss);
    expect(POOLS.combat).toContain(combat);
  });

  it("plays the only track of a pool of one at every fight", () => {
    const p = new MusicPlanner({ ...POOLS, boss: ["only_boss"] }, () => 0);
    p.choose(hub);
    for (let i = 0; i < 3; i++) {
      p.choose(exploring(0));
      expect(p.choose(fight({ bossFight: true }))?.file).toBe("only_boss");
    }
  });

  it("plays nothing in a fight whose pool is empty", () => {
    const p = new MusicPlanner({ ...POOLS, boss: [] }, () => 0);
    p.choose(hub);
    p.choose(exploring(0));
    expect(p.choose(fight({ bossFight: true }))).toBeNull();
  });
});

describe("MusicPlanner hub and game over", () => {
  it("plays a hub track, and rerolls after REROLL_AFTER_UNITS visits", () => {
    const p = planner();
    const first = p.choose(hub)?.file;
    expect(POOLS.hub).toContain(first);
    for (let i = 1; i < REROLL_AFTER_UNITS; i++) {
      p.choose(exploring(0));
      expect(p.choose(hub)?.file).toBe(first);
    }
    p.choose(exploring(0));
    expect(p.choose(hub)?.file).not.toBe(first);
  });

  it("plays nothing on the game over screen", () => {
    const p = planner();
    p.choose(hub);
    p.choose(exploring(0));
    expect(p.choose(gameOver)).toBeNull();
  });

  it("plays nothing for a category whose pool is empty", () => {
    const p = new MusicPlanner({ ...POOLS, hub: [] }, () => 0);
    expect(p.choose(hub)).toBeNull();
  });
});
