/** What to play: a source URL, and whether to pick the track up where it was left the last time. */
export interface MusicTarget {
  src: string;
  resume: boolean;
}

/** One of the two audio elements and where it stands in a fade. `gain` goes from 0 to 1 and the
 * volume follows an equal-power curve of it, so a crossfade (one gain up, the other down) keeps
 * the loudness steady. */
interface Slot {
  el: HTMLAudioElement;
  src: string | null;
  gain: number;
  /** 1 while it is the track that should be heard, 0 while it fades out. */
  target: 0 | 1;
  /** `play()` has resolved: only then does the gain start to rise. */
  ready: boolean;
  /** Invalidates a `play()` still pending when the slot is reused. */
  token: number;
}

export interface MusicPlayerOptions {
  fadeMs?: number;
  tickMs?: number;
}

export const DEFAULT_FADE_MS = 1500;
const DEFAULT_TICK_MS = 50;

/**
 * Plays one looping music track at a time and crossfades between tracks, with two audio elements.
 *
 * The new track starts first, silent, and only once it really plays does the old one fade out while
 * it fades in. A request made during a fade wins: the track that was fading in becomes the one that
 * fades out, from the volume it had reached. A track left for a fight is remembered where it was
 * and can be picked up there (`MusicTarget.resume`).
 *
 * A source that cannot be played (a missing file, a blocked autoplay) leaves silence, the old track
 * still fades out. Browsers refuse playback before the first user gesture: until `unlock()` is
 * called nothing is played, and the current track starts then.
 *
 * The fade is counted in ticks, not read from the clock, so it is the same under fake timers.
 */
export class MusicPlayer {
  private readonly slots: [Slot, Slot];
  private readonly positions = new Map<string, number>();
  private current: Slot | null = null;
  /** What was asked for last, kept even when it could not be played (a missing file is not retried
   * on every call). */
  private requested: string | null = null;
  private volume = 0.5;
  private unlocked = false;
  private tickId: ReturnType<typeof setInterval> | null = null;
  private readonly fadeMs: number;
  private readonly tickMs: number;

  constructor(createElement: () => HTMLAudioElement, options: MusicPlayerOptions = {}) {
    this.fadeMs = options.fadeMs ?? DEFAULT_FADE_MS;
    this.tickMs = options.tickMs ?? DEFAULT_TICK_MS;
    const make = (): Slot => {
      const el = createElement();
      el.loop = true;
      el.volume = 0;
      return { el, src: null, gain: 0, target: 0, ready: false, token: 0 };
    };
    this.slots = [make(), make()];
  }

  /** The source that is meant to be heard now, null for silence. */
  get currentSrc(): string | null {
    return this.requested;
  }

  /** The music volume, from 0 to 1. Applied at once, also in the middle of a fade. */
  setVolume(volume: number): void {
    this.volume = volume;
    for (const slot of this.slots) this.applyVolume(slot);
  }

  /** Call on the first user gesture: the track asked for before it starts now. */
  unlock(): void {
    if (this.unlocked) return;
    this.unlocked = true;
    const slot = this.current;
    if (slot && slot.target === 1 && !slot.ready) this.startPlayback(slot);
  }

  /** Fades to `target`, or to silence for null. Asking for what already plays does nothing. */
  play(target: MusicTarget | null): void {
    const src = target?.src ?? null;
    if (src === this.requested) return;
    this.requested = src;

    const outgoing = this.current;
    if (outgoing) this.retire(outgoing);

    if (!target) {
      this.current = null;
      this.ensureTicking();
      return;
    }

    // The slot that is not the one just retired, or the freest one. If it is still fading out an
    // older track, that track is cut: two requests in a row leave no room for three tracks.
    const incoming = this.freeSlot(outgoing);
    this.clear(incoming);

    const resumeAt = target.resume ? this.positions.get(target.src) : undefined;
    this.positions.delete(target.src);
    incoming.src = target.src;
    incoming.el.src = target.src;
    if (resumeAt !== undefined) incoming.el.currentTime = resumeAt;
    incoming.target = 1;
    incoming.gain = 0;
    this.current = incoming;

    // Before the first gesture nothing can play: the track starts, fading in, at `unlock()`.
    if (this.unlocked) this.startPlayback(incoming);
    this.ensureTicking();
  }

  private freeSlot(outgoing: Slot | null): Slot {
    const candidates = this.slots.filter((s) => s !== outgoing);
    return candidates.find((s) => s.src === null) ?? candidates.reduce((a, b) => (a.gain <= b.gain ? a : b));
  }

  private startPlayback(slot: Slot): void {
    const token = ++slot.token;
    Promise.resolve(slot.el.play()).then(
      () => {
        if (slot.token !== token) return;
        slot.ready = true;
        this.ensureTicking();
      },
      () => {
        if (slot.token !== token) return;
        this.clear(slot); // nothing to hear: silence, the other track still fades out
      },
    );
  }

  /** Starts fading a slot out, remembering where its track was. */
  private retire(slot: Slot): void {
    if (slot.src && slot.ready) this.positions.set(slot.src, slot.el.currentTime);
    slot.token++;
    slot.target = 0;
    if (!slot.ready) this.clear(slot); // never heard: nothing to fade
  }

  /** Stops a slot at once. */
  private clear(slot: Slot): void {
    slot.token++;
    slot.target = 0;
    slot.gain = 0;
    slot.ready = false;
    slot.src = null;
    slot.el.pause();
    slot.el.volume = 0;
  }

  private ensureTicking(): void {
    if (this.tickId !== null || !this.moving()) return;
    this.tickId = setInterval(() => this.tick(), this.tickMs);
  }

  /** A slot is moving while a track is fading in (once it plays) or out. */
  private moving(): boolean {
    return this.slots.some(
      (s) => (s.target === 1 && s.ready && s.gain < 1) || (s.target === 0 && s.src !== null && s.gain > 0),
    );
  }

  private tick(): void {
    const step = this.tickMs / this.fadeMs;
    for (const slot of this.slots) {
      if (slot.target === 1 && slot.ready) {
        slot.gain = Math.min(1, slot.gain + step);
      } else if (slot.target === 0 && slot.src !== null) {
        slot.gain = Math.max(0, slot.gain - step);
        if (slot.gain === 0) this.clear(slot);
      }
      this.applyVolume(slot);
    }
    if (!this.moving() && this.tickId !== null) {
      clearInterval(this.tickId);
      this.tickId = null;
    }
  }

  private applyVolume(slot: Slot): void {
    slot.el.volume = Math.sin((slot.gain * Math.PI) / 2) * this.volume;
  }
}
