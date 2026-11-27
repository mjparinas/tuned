// The browser harness's player (desktop Chromium, for development and CI): a <video>. Not used on a TV. close() drops
// the source and reloads, which ends the browser's connection before the next one opens.
import type { StreamPlayer } from "@tuned/shared";
import type { PlayerEvents } from "./avplay.ts";

const OPEN_TIMEOUT = 15_000;

export class VideoPlayer implements StreamPlayer {
    private readonly video: HTMLVideoElement;
    private readonly events: PlayerEvents;

    constructor(video: HTMLVideoElement, events: PlayerEvents) {
        this.video = video;
        this.events = events;
        video.addEventListener("waiting", () => events.onBuffering(true));
        video.addEventListener("playing", () => events.onBuffering(false));
        video.addEventListener("ended", () => events.onEnded());
    }

    open(url: string, options: { startAt: number }): Promise<void> {
        const video = this.video;
        return new Promise((resolve, reject) => {
            const timer = setTimeout(() => finish(new Error("timeout")), OPEN_TIMEOUT);
            const finish = (error?: Error) => {
                clearTimeout(timer);
                video.removeEventListener("canplay", ok);
                video.removeEventListener("error", fail);
                if (error) reject(error);
                else resolve();
            };
            const ok = () => {
                if (options.startAt > 0) video.currentTime = options.startAt / 1000;
                video.play().catch(() => undefined);
                finish();
            };
            const fail = () => finish(new Error(video.error?.message || "error"));
            video.addEventListener("canplay", ok);
            video.addEventListener("error", fail);
            video.src = url;
            video.load();
        });
    }

    async close(): Promise<void> {
        this.video.pause();
        this.video.removeAttribute("src");
        this.video.load();
    }

    pause(): void {
        this.video.pause();
    }

    resume(): void {
        void this.video.play().catch(() => undefined);
    }

    seek(ms: number): void {
        this.video.currentTime = Math.max(0, ms / 1000);
    }

    position(): number {
        return this.video.currentTime * 1000;
    }

    duration(): number {
        return Number.isFinite(this.video.duration) ? this.video.duration * 1000 : 0;
    }

    tracks(): { index: number; type: string; label: string }[] {
        return [];
    }

    selectTrack(): void {}

    /** For the e2e test: did the error path fire. */
    get element(): HTMLVideoElement {
        return this.video;
    }

    errorMessage(): void {
        this.events.onError("error");
    }
}
