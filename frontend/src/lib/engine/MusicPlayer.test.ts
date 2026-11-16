import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { MusicPlayer } from "./MusicPlayer";

/** Just enough of an audio element: what the player sets, and a `play()` that can succeed or fail. */
class FakeAudio {
  src = "";
  volume = 1;
  loop = false;
  /** Every position the player asked for, in order. */
  seeks: number[] = [];
  private position = 0;
  paused = true;
  playCalls = 0;
  get currentTime(): number {
    return this.position;
  }

  set currentTime(value: number) {
    this.position = value;
    this.seeks.push(value);
  }

  /** Makes the next `play()` reject, like a missing file or a blocked autoplay. */
  failNextPlay = false;

  play(): Promise<void> {
    this.playCalls++;
    if (this.failNextPlay) {
      this.failNextPlay = false;
      return Promise.reject(new Error("cannot play"));
    }
    this.paused = false;
    return Promise.resolve();
  }

  pause(): void {
    this.paused = true;
  }
}

const FADE_MS = 1000;
const TICK_MS = 100;

function setup(volume = 1): { player: MusicPlayer; els: FakeAudio[] } {
  const els: FakeAudio[] = [];
  const player = new MusicPlayer(
    () => {
      const el = new FakeAudio();
      els.push(el);
      return el as unknown as HTMLAudioElement;
    },
    { fadeMs: FADE_MS, tickMs: TICK_MS },
  );
  player.setVolume(volume);
  player.unlock();
  return { player, els };
}

/** Lets the pending `play()` promises settle, then runs the fade for `ms`. */
async function run(ms: number): Promise<void> {
  await vi.advanceTimersByTimeAsync(ms);
}

const A = { src: "/music/a.ogg", resume: false };
const B = { src: "/music/b.ogg", resume: false };

beforeEach(() => {
  vi.useFakeTimers();
});
afterEach(() => {
  vi.useRealTimers();
});

describe("MusicPlayer", () => {
  it("fades the first track in from silence up to the music volume", async () => {
    const { player, els } = setup(0.6);
    player.play(A);
    await run(0);
    expect(els[0].volume).toBe(0);
    await run(FADE_MS / 2);
    expect(els[0].volume).toBeGreaterThan(0);
    expect(els[0].volume).toBeLessThan(0.6);
    await run(FADE_MS);
    expect(els[0].volume).toBeCloseTo(0.6, 5);
    expect(els[0].paused).toBe(false);
    expect(els[0].loop).toBe(true);
  });

  it("does nothing when asked for the track that already plays", async () => {
    const { player, els } = setup();
    player.play(A);
    await run(FADE_MS * 2);
    player.play({ ...A });
    await run(FADE_MS);
    expect(els[0].playCalls).toBe(1);
    expect(els[1].playCalls).toBe(0);
    expect(player.currentSrc).toBe(A.src);
  });

  it("crossfades to another track with the second element, both heard at the middle", async () => {
    const { player, els } = setup();
    player.play(A);
    await run(FADE_MS * 2);
    player.play(B);
    await run(FADE_MS / 2);
    expect(els[1].src).toBe(B.src);
    expect(els[0].volume).toBeGreaterThan(0);
    expect(els[1].volume).toBeGreaterThan(0);
    await run(FADE_MS);
    expect(els[0].paused).toBe(true);
    expect(els[0].volume).toBe(0);
    expect(els[1].volume).toBeCloseTo(1, 5);
  });

  it("starts the new track before the old one begins to fade", async () => {
    const { player, els } = setup();
    player.play(A);
    await run(FADE_MS * 2);
    player.play(B);
    // play() has been called on the new element, the old one is still at full volume
    expect(els[1].playCalls).toBe(1);
    expect(els[0].volume).toBeCloseTo(1, 5);
  });

  it("fades to silence for null and stops the element", async () => {
    const { player, els } = setup();
    player.play(A);
    await run(FADE_MS * 2);
    player.play(null);
    await run(FADE_MS * 2);
    expect(els[0].paused).toBe(true);
    expect(els[0].volume).toBe(0);
    expect(player.currentSrc).toBeNull();
  });

  it("resumes a track where it was left when asked to", async () => {
    const { player, els } = setup();
    player.play(A);
    await run(FADE_MS * 2);
    els[0].currentTime = 42;
    els[0].seeks = [];
    player.play(B);
    await run(FADE_MS * 2);
    player.play({ ...A, resume: true });
    expect(els[0].src).toBe(A.src);
    expect(els[0].seeks).toEqual([42]);
  });

  it("starts a track from the beginning when resume is not asked for", async () => {
    const { player, els } = setup();
    player.play(A);
    await run(FADE_MS * 2);
    els[0].currentTime = 42;
    els[0].seeks = [];
    player.play(B);
    await run(FADE_MS * 2);
    player.play(A);
    expect(els[0].src).toBe(A.src);
    expect(els[0].seeks).toEqual([]);
  });

  it("lets the last request win when asked again in the middle of a fade", async () => {
    const { player, els } = setup();
    player.play(A);
    await run(FADE_MS * 2);
    player.play(B);
    await run(FADE_MS / 2);
    const bVolume = els[1].volume;
    player.play({ src: "/music/c.ogg", resume: false });
    await run(FADE_MS * 3);
    expect(player.currentSrc).toBe("/music/c.ogg");
    expect(els.filter((e) => !e.paused).length).toBe(1);
    const heard = els.find((e) => !e.paused)!;
    expect(heard.src).toBe("/music/c.ogg");
    expect(heard.volume).toBeCloseTo(1, 5);
    expect(bVolume).toBeGreaterThan(0);
  });

  it("goes back to the track that was fading out when it is asked for again", async () => {
    const { player, els } = setup();
    player.play(A);
    await run(FADE_MS * 2);
    player.play(B);
    await run(FADE_MS / 2);
    player.play(A);
    await run(FADE_MS * 3);
    expect(player.currentSrc).toBe(A.src);
    expect(els.filter((e) => !e.paused).map((e) => e.src)).toEqual([A.src]);
  });

  it("applies a volume change at once, also during a fade", async () => {
    const { player, els } = setup(1);
    player.play(A);
    await run(FADE_MS * 2);
    player.setVolume(0.3);
    expect(els[0].volume).toBeCloseTo(0.3, 5);
    player.play(B);
    await run(FADE_MS / 2);
    player.setVolume(0.2);
    expect(els[1].volume).toBeLessThanOrEqual(0.2);
    await run(FADE_MS * 2);
    expect(els[1].volume).toBeCloseTo(0.2, 5);
  });

  it("stays silent when the new track cannot play, and the old one still fades out", async () => {
    const { player, els } = setup();
    player.play(A);
    await run(FADE_MS * 2);
    // the element the next track will use fails to play
    els[1].failNextPlay = true;
    player.play(B);
    await run(FADE_MS * 2);
    expect(els[0].paused).toBe(true);
    expect(els[1].paused).toBe(true);
    expect(player.currentSrc).toBe(B.src);
  });

  it("does not try a track that failed again on every call", async () => {
    const { player, els } = setup();
    els[0].failNextPlay = true;
    player.play(A);
    await run(FADE_MS);
    player.play({ ...A });
    player.play({ ...A });
    await run(FADE_MS);
    expect(els[0].playCalls).toBe(1);
  });

  it("holds the track back until the first gesture, then fades it in", async () => {
    const els: FakeAudio[] = [];
    const player = new MusicPlayer(
      () => {
        const el = new FakeAudio();
        els.push(el);
        return el as unknown as HTMLAudioElement;
      },
      { fadeMs: FADE_MS, tickMs: TICK_MS },
    );
    player.setVolume(1);
    player.play(A);
    await run(FADE_MS);
    expect(els[0].playCalls).toBe(0);
    expect(els[0].src).toBe(A.src);
    player.unlock();
    player.unlock();
    await run(FADE_MS * 2);
    expect(els[0].playCalls).toBe(1);
    expect(els[0].volume).toBeCloseTo(1, 5);
  });

  it("drops the track asked for before the gesture if another one is asked for first", async () => {
    const els: FakeAudio[] = [];
    const player = new MusicPlayer(() => {
      const el = new FakeAudio();
      els.push(el);
      return el as unknown as HTMLAudioElement;
    });
    player.play(A);
    player.play(B);
    player.unlock();
    await run(5000);
    expect(els.filter((e) => !e.paused).map((e) => e.src)).toEqual([B.src]);
  });
});
