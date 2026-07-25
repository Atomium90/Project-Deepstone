import { gameState, combatDamageEvents, soundEvents } from "./StateStore";
import { settings } from "./SettingsStore";
import type { GamePhase } from "./protocol";

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

/** Music by filename convention, not a manifest: dropping a correctly-named file into
 * frontend/public/audio/music/ turns a track on with no code changes. Missing files just fail
 * to play silently (caught in `applyMusicForPhase`). */
const MUSIC_FILES: Partial<Record<GamePhase, string>> = {
  HUB: "hub.ogg",
  EXPLORATION: "exploration.ogg",
  COMBAT: "combat.ogg",
};
const BOSS_MUSIC_FILE = "boss.ogg";

const MUSIC_FADE_MS = 400;

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

  constructor() {
    this.musicEl = new Audio();
    this.musicEl.loop = true;
    this.musicEl.volume = 0;

    settings.subscribe((s) => {
      this.sfxVolume = s.sfxVolume / 100;
      this.musicVolume = s.musicVolume / 100;
      if (this.fadeIntervalId === null) this.musicEl.volume = this.musicVolume;
    });

    gameState.subscribe((state) => {
      if (!state) return;
      this.applyMusicForPhase(state.phase, state.combat?.isBoss ?? false);
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

  private applyMusicForPhase(phase: GamePhase, isBoss: boolean): void {
    const file = phase === "COMBAT" && isBoss ? BOSS_MUSIC_FILE : MUSIC_FILES[phase];
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
