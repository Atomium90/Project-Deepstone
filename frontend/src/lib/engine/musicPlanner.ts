import type { GamePhase } from "./protocol";

/** Music by filename convention, not a manifest: dropping correctly-named files into
 * frontend/public/audio/music/ turns a category on with no code changes. Missing files just
 * stay silent (see MusicPlayer). Each category is a pool, see `MusicPlanner`. */
export interface MusicPools {
  hub: string[];
  /** Fights against an Elite or a MiniBoss. Ordinary fights keep the exploration track. */
  combat: string[];
  /** Fights against a Boss only. */
  boss: string[];
  /** Exploration pools by section, the calmest first: the first section of a run plays the first
   * pool, the second the second, and so on. A run with more sections than pools repeats the last. */
  exploration: string[][];
}

export const MUSIC_POOLS: MusicPools = {
  hub: ["hub_lively_city.ogg", "hub_peaceful_village.ogg", "hub_wood_forest_town.ogg"],
  combat: [
    "combat_battle_theme_1.ogg",
    "combat_battle_theme_2.ogg",
    "combat_battle_theme_3.ogg",
    "combat_battle_theme_4.ogg",
  ],
  boss: ["boss_soulrend_sovereign.ogg", "boss_ruinlord_ascendant.ogg", "boss_bloodbound_fight.ogg"],
  exploration: [
    // First section, whatever the difficulty of the run
    ["exploration_easy_traveling_the_sky.ogg", "exploration_easy_unknown_island.ogg"],
    // Second section
    [
      "exploration_normal_long_journey.ogg",
      "exploration_normal_spirits_forest.ogg",
      "exploration_normal_volcanic_crater.ogg",
      "exploration_normal_dark_factory.ogg",
    ],
    // Third section
    ["exploration_hard_hidden_cavern.ogg", "exploration_hard_dangerous_cave.ogg"],
  ],
};

/** How many hub visits the current hub track sticks around for before rerolling to a different one.
 * Exploration changes with the dungeon section, and combat and boss music with every fight, see
 * `MusicPlanner`. */
export const REROLL_AFTER_UNITS = 4;

export interface MusicContext {
  phase: GamePhase;
  /** A Boss fight. */
  bossFight: boolean;
  /** An Elite or a MiniBoss fight. */
  eliteFight: boolean;
  /** Index of the dungeon section the player is in, or null when the client does not know it (the
   * map is only sent while exploring). */
  section: number | null;
}

export interface MusicChoice {
  file: string;
  /** Pick the track up where it was left, instead of from the start: the exploration track of the
   * section the player comes back to after a fight. */
  resume: boolean;
}

interface Rotation {
  pinned: string;
  unitsSinceReroll: number;
}

/** The tracks of a pool not heard yet in this round, and the one playing. */
interface Bag {
  remaining: string[];
  current: string;
}

/**
 * Decides which music file goes with a game state. Pure: nothing here touches audio.
 *
 *  - Exploration has one track per dungeon section (a "zone"), picked at the start of a run and
 *    whenever the player enters the next section, from the pool of that section: the music gets
 *    heavier as the run goes deeper, whatever its difficulty. The Rest room and the Sanctuary keep
 *    the track of the section that just ended, they are not zones of their own.
 *  - An ordinary fight keeps the exploration track playing, untouched. An Elite or MiniBoss fight uses
 *    the combat pool, a Boss fight the boss pool, and the exploration track resumes after them.
 *  - Each combat or boss fight plays a different track from the last, and every track of the pool is
 *    heard before one comes back (a "bag", so the 3 bosses of a Hard run never share a track while the
 *    pool has 3). The hub track sticks around for `REROLL_AFTER_UNITS` visits.
 */
export class MusicPlanner {
  private readonly rotations = new Map<string, Rotation>();
  private readonly bags = new Map<string, Bag>();
  private lastPhaseKey: string | null = null;
  private lastPhase: GamePhase | null = null;
  private zone: { section: number | null; file: string } | null = null;

  constructor(
    private readonly pools: MusicPools = MUSIC_POOLS,
    private readonly random: () => number = Math.random,
  ) {}

  /** The track for this state, or null for silence. Call it on every state update: it only counts
   * a new unit when the phase really changed. */
  choose(ctx: MusicContext): MusicChoice | null {
    const phaseKey = `${ctx.phase}:${ctx.bossFight}:${ctx.eliteFight}`;
    const phaseChanged = phaseKey !== this.lastPhaseKey;
    const previousPhase = this.lastPhase;
    this.lastPhaseKey = phaseKey;
    this.lastPhase = ctx.phase;

    switch (ctx.phase) {
      case "GAMEOVER":
        return null;
      case "HUB":
        return this.rotating("HUB", this.pools.hub, phaseChanged);
      case "COMBAT":
        if (ctx.bossFight) return this.fromBag("BOSS", this.pools.boss, phaseChanged);
        if (ctx.eliteFight) return this.fromBag("COMBAT", this.pools.combat, phaseChanged);
        // An ordinary fight does not change the music. A run entered straight into a fight (a debug
        // room) has no zone yet, so it gets one.
        return this.zone
          ? { file: this.zone.file, resume: true }
          : this.startZone(ctx);
      case "EXPLORATION": {
        const runStart = previousPhase === null || previousPhase === "HUB" || previousPhase === "GAMEOVER";
        if (this.zone === null || runStart) return this.startZone(ctx);
        if (this.zone.section === null) {
          this.zone.section = ctx.section;
        } else if (ctx.section !== null && ctx.section !== this.zone.section) {
          return this.startZone(ctx);
        }
        return { file: this.zone.file, resume: previousPhase === "COMBAT" };
      }
    }
  }

  /** A new zone: a track other than the one just heard, from the pool of the section. A section the
   * client does not know yet (a run entered straight into a fight) counts as the first. */
  private startZone(ctx: MusicContext): MusicChoice | null {
    const pools = this.pools.exploration;
    const pool = pools[Math.min(ctx.section ?? 0, pools.length - 1)];
    if (!pool || pool.length === 0) return null;
    const file = this.pickRandom(pool, this.zone?.file);
    this.zone = { section: ctx.section, file };
    return { file, resume: false };
  }

  private rotating(key: string, pool: string[], isNewUnit: boolean): MusicChoice | null {
    if (pool.length === 0) return null;
    let state = this.rotations.get(key);
    if (!state) {
      state = { pinned: this.pickRandom(pool), unitsSinceReroll: 1 };
      this.rotations.set(key, state);
    } else if (isNewUnit) {
      state.unitsSinceReroll++;
      if (state.unitsSinceReroll > REROLL_AFTER_UNITS) {
        state.pinned = this.pickRandom(pool, state.pinned);
        state.unitsSinceReroll = 1;
      }
    }
    return { file: state.pinned, resume: false };
  }

  /** A track for a fight: the same one for the updates of a fight, a new one for the next fight,
   * taken from the tracks not heard yet in this round of the pool. */
  private fromBag(key: string, pool: string[], isNewFight: boolean): MusicChoice | null {
    if (pool.length === 0) return null;
    let bag = this.bags.get(key);
    if (!bag || isNewFight) {
      const remaining = bag && bag.remaining.length > 0 ? bag.remaining : pool.filter((f) => f !== bag?.current || pool.length === 1);
      const file = remaining[Math.floor(this.random() * remaining.length)];
      bag = { remaining: remaining.filter((f) => f !== file), current: file };
      this.bags.set(key, bag);
    }
    return { file: bag.current, resume: false };
  }

  private pickRandom(pool: string[], exclude?: string): string {
    const choices = exclude && pool.length > 1 ? pool.filter((f) => f !== exclude) : pool;
    return choices[Math.floor(this.random() * choices.length)];
  }
}
