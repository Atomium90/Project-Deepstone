import { gameState, combatDamageEvents, soundEvents } from "./StateStore";
import { settings } from "./SettingsStore";
import { lastStartedDifficulty } from "./RunStore";
import { MusicPlanner } from "./musicPlanner";
import { MusicPlayer } from "./MusicPlayer";
import type { Difficulty, StateUpdate } from "./protocol";

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

/** The dungeon section the player is in, read from the map the server already sends while
 * exploring. Null when there is no map (hub, fights, game over). */
function currentSection(state: StateUpdate): number | null {
  return state.minimap?.nodes.find((n) => n.current)?.section ?? null;
}

/** Singleton, same shape as AssetManager.ts's `assets` export. Owns SFX playback and the looping
 * music, wired directly to the relevant Svelte stores (no consuming component needs to know audio
 * exists). Which track plays is decided by `MusicPlanner`, how it changes by `MusicPlayer`. */
class AudioManager {
  private sfxVolume = 0.7;
  private currentDifficulty: Difficulty = "normal";
  private readonly planner = new MusicPlanner();
  private readonly music = new MusicPlayer(() => new Audio());

  constructor() {
    settings.subscribe((s) => {
      this.sfxVolume = s.sfxVolume / 100;
      this.music.setVolume(s.musicVolume / 100);
    });

    lastStartedDifficulty.subscribe((d) => {
      this.currentDifficulty = d;
    });

    gameState.subscribe((state) => {
      if (!state) return;
      // A MiniBoss checkpoint reuses the boss music too - same cosmetic treatment, it just never
      // ends the run the way a real Boss room does.
      const choice = this.planner.choose({
        phase: state.phase,
        bossFight: (state.combat?.isBoss ?? false) || (state.combat?.isMiniBoss ?? false),
        eliteFight: state.combat?.isElite ?? false,
        difficulty: this.currentDifficulty,
        section: currentSection(state),
      });
      this.music.play(choice ? { src: MUSIC_DIR + choice.file, resume: choice.resume } : null);
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
   * this starts the music on the first click or keypress anywhere on the page. */
  init(): void {
    const unlock = (): void => {
      this.music.unlock();
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
}

export const audio = new AudioManager();
