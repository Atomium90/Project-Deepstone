import { gameState, combatDamageEvents, soundEvents } from "./StateStore";
import { settings } from "./SettingsStore";
import { lastStartedDifficulty } from "./RunStore";
import type { GamePhase, Difficulty } from "./protocol";

const SFX_DIR = "/audio/sfx/";
const MUSIC_DIR = "/audio/music/";

/** Tag -> candidate files, picked at random for a little variety. An empty list means the tag
 * is wired but no matching sound has been sourced yet - playSfx no-ops rather than erroring. */
const SFX_FILES: Record<string, string[]> = {
  pickup: ["handleCoins.ogg", "handleCoins2.ogg"],
  door_open: ["doorOpen_1.ogg", "doorOpen_2.ogg"],
  door_unlock: ["metalLatch.ogg"],
  hit: ["knifeSlice.ogg", "knifeSlice2.ogg"],
  level_up: [],
};

/** Music by filename convention, not a manifest: dropping correctly-named files into
 * frontend/public/audio/music/ turns a category on with no code changes. Missing files just
 * fail to play silently (caught in `applyMusic`). Each category is a pool, not a single file -
 * see `pickTrack` for the rotation rule. HUB/COMBAT/BOSS pools don't vary by difficulty;
 * EXPLORATION does (see EXPLORATION_MUSIC_FILES) since a run's difficulty is otherwise the only
 * thing that changes room-to-room while the phase itself stays EXPLORATION throughout. */
const MUSIC_FILES: Partial<Record<Exclude<GamePhase, "EXPLORATION" | "GAMEOVER">, string[]>> = {
  HUB: ["hub_lively_city.ogg", "hub_peaceful_village.ogg", "hub_wood_forest_town.ogg"],
  COMBAT: [
    "combat_battle_theme_1.ogg",
    "combat_battle_theme_2.ogg",
    "combat_battle_theme_3.ogg",
    "combat_battle_theme_4.ogg",
  ],
};
const BOSS_MUSIC_FILES = [
  "boss_soulrend_sovereign.ogg",
  "boss_ruinlord_ascendant.ogg",
  "boss_bloodbound_fight.ogg",
];
const EXPLORATION_MUSIC_FILES: Record<Difficulty, string[]> = {
  easy: ["exploration_easy_traveling_the_sky.ogg", "exploration_easy_unknown_island.ogg"],
  normal: [
    "exploration_normal_long_journey.ogg",
    "exploration_normal_spirits_forest.ogg",
    "exploration_normal_volcanic_crater.ogg",
    "exploration_normal_dark_factory.ogg",
  ],
  hard: ["exploration_hard_hidden_cavern.ogg", "exploration_hard_dangerous_cave.ogg"],
};

const MUSIC_FADE_MS = 400;

/** How many "units" (fights for COMBAT/BOSS, rooms for EXPLORATION, hub visits for HUB) the
 * current pick sticks around for before rerolling to a different track in the same pool - keeps
 * rotation from feeling either repetitive (never changes) or twitchy (changes constantly). */
const REROLL_AFTER_UNITS = 4;

/** Per-category rotation state: which track is currently "pinned", and how many units it's been
 * pinned for. Keyed by a rotation key (see `applyMusic`) - EXPLORATION gets one key per
 * difficulty so switching difficulty across runs doesn't fight over the same counter. */
interface RotationState {
  pinnedTrack: string;
  unitsSinceReroll: number;
}

/** Singleton, same shape as AssetManager.ts's `assets` export. Owns SFX playback and the looping
 * music track, wired directly to the relevant Svelte stores (no consuming component needs to
 * know audio exists). */
class AudioManager {
  private sfxVolume = 0.7;
  private musicVolume = 0.5;
  private readonly musicEl: HTMLAudioElement;
  private currentMusicSrc: string | null = null;
  private fadeIntervalId: ReturnType<typeof setInterval> | null = null;
  private unlocked = false;

  private currentDifficulty: Difficulty = "normal";
  private lastPhaseKey: string | null = null;
  private lastRoomId: string | null = null;
  private readonly rotationState = new Map<string, RotationState>();

  constructor() {
    this.musicEl = new Audio();
    this.musicEl.loop = true;
    this.musicEl.volume = 0;

    settings.subscribe((s) => {
      this.sfxVolume = s.sfxVolume / 100;
      this.musicVolume = s.musicVolume / 100;
      if (this.fadeIntervalId === null) this.musicEl.volume = this.musicVolume;
    });

    lastStartedDifficulty.subscribe((d) => {
      this.currentDifficulty = d;
    });

    gameState.subscribe((state) => {
      if (!state) return;
      this.applyMusic(state.phase, state.combat?.isBoss ?? false, state.room?.roomId ?? null);
    });

    combatDamageEvents.subscribe((events) => {
      for (const evt of events) {
        if (evt.kind === "damage") this.playSfx("hit");
      }
    });

    soundEvents.subscribe((tags) => {
      for (const tag of tags) this.playSfx(tag);
    });
  }

  /** Call once from App.svelte's onMount. Browsers block audio playback before a user gesture -
   * this starts/resumes music on the first click or keypress anywhere on the page. */
  init(): void {
    const unlock = (): void => {
      if (this.unlocked) return;
      this.unlocked = true;
      if (this.currentMusicSrc) void this.musicEl.play().catch(() => {});
      window.removeEventListener("pointerdown", unlock);
      window.removeEventListener("keydown", unlock);
    };
    window.addEventListener("pointerdown", unlock);
    window.addEventListener("keydown", unlock);
  }

  playSfx(tag: string): void {
    const files = SFX_FILES[tag];
    if (!files || files.length === 0) return;
    const file = files[Math.floor(Math.random() * files.length)];
    const el = new Audio(SFX_DIR + file);
    el.volume = this.sfxVolume;
    void el.play().catch(() => {});
  }

  /**
   * Picks the music file for the current state and crossfades to it if it actually changed.
   *
   * "Changed" means the resolved *pinned* track differs from what's already playing - not
   * simply that a new StateUpdate arrived. For COMBAT/BOSS, the pinned track naturally differs
   * every fight anyway (EXPLORATION music always plays between fights, so the shared `musicEl`
   * gets reassigned away and back - a plain rotation-key comparison would already restart it
   * fresh each time even with no reroll). For EXPLORATION, `roomId` changing while the phase
   * stays EXPLORATION is the "did a new unit happen" signal - a rotation key alone wouldn't move
   * at all otherwise, since the phase itself doesn't change room-to-room.
   */
  private applyMusic(phase: GamePhase, isBoss: boolean, roomId: string | null): void {
    const phaseKey = `${phase}:${isBoss}`;
    const phaseChanged = phaseKey !== this.lastPhaseKey;
    this.lastPhaseKey = phaseKey;

    let rotationKey: string | null = null;
    let pool: string[] | undefined;
    let isNewUnit = false;

    if (phase === "COMBAT" && isBoss) {
      rotationKey = "BOSS";
      pool = BOSS_MUSIC_FILES;
      isNewUnit = phaseChanged;
    } else if (phase === "COMBAT") {
      rotationKey = "COMBAT";
      pool = MUSIC_FILES.COMBAT;
      isNewUnit = phaseChanged;
    } else if (phase === "HUB") {
      rotationKey = "HUB";
      pool = MUSIC_FILES.HUB;
      isNewUnit = phaseChanged;
    } else if (phase === "EXPLORATION") {
      rotationKey = `EXPLORATION:${this.currentDifficulty}`;
      pool = EXPLORATION_MUSIC_FILES[this.currentDifficulty];
      isNewUnit = phaseChanged || (roomId !== null && roomId !== this.lastRoomId);
    }
    this.lastRoomId = phase === "EXPLORATION" ? roomId : null;

    const file = rotationKey && pool && pool.length > 0 ? this.pickTrack(rotationKey, pool, isNewUnit) : undefined;
    const src = file ? MUSIC_DIR + file : null;
    if (src === this.currentMusicSrc) return;
    this.currentMusicSrc = src;

    this.fade(this.musicEl.volume, 0, () => {
      this.musicEl.pause();
      if (!src) return;
      this.musicEl.src = src;
      if (this.unlocked) void this.musicEl.play().catch(() => {});
      this.fade(0, this.musicVolume);
    });
  }

  /** Returns the currently pinned track for `key`, rerolling to a different track in `pool`
   * every REROLL_AFTER_UNITS units (see the RotationState doc comment). Only counts a unit when
   * `isNewUnit` is true - repeated calls within the same fight/room/hub-visit (every other
   * player action also re-runs applyMusic) must not silently burn through the reroll budget. */
  private pickTrack(key: string, pool: string[], isNewUnit: boolean): string {
    const pickRandom = (exclude?: string): string => {
      const choices = exclude && pool.length > 1 ? pool.filter((t) => t !== exclude) : pool;
      return choices[Math.floor(Math.random() * choices.length)];
    };

    let state = this.rotationState.get(key);
    if (!state) {
      state = { pinnedTrack: pickRandom(), unitsSinceReroll: 1 };
      this.rotationState.set(key, state);
      return state.pinnedTrack;
    }

    if (isNewUnit) {
      state.unitsSinceReroll++;
      if (state.unitsSinceReroll > REROLL_AFTER_UNITS) {
        state.pinnedTrack = pickRandom(state.pinnedTrack);
        state.unitsSinceReroll = 1;
      }
    }
    return state.pinnedTrack;
  }

  private fade(from: number, to: number, onDone?: () => void): void {
    if (this.fadeIntervalId !== null) clearInterval(this.fadeIntervalId);
    const steps = 10;
    let i = 0;
    this.musicEl.volume = from;
    this.fadeIntervalId = setInterval(() => {
      i++;
      this.musicEl.volume = from + (to - from) * (i / steps);
      if (i >= steps) {
        clearInterval(this.fadeIntervalId!);
        this.fadeIntervalId = null;
        onDone?.();
      }
    }, MUSIC_FADE_MS / steps);
  }
}

export const audio = new AudioManager();
