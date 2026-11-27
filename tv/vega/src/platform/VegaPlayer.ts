// The stick's player: Amazon's W3C VideoPlayer (the stick's own GStreamer pipeline, hardware decoding) in URL mode,
// which plays an Xtream `.ts` live link straight from the link (docs/decisions/vega.md). Only `OneStream` opens and
// closes it, so there is never more than one connection. The screen hands it the video surface when it has one.
import { VideoPlayer } from "@amazon-devices/react-native-w3cmedia";
import { log } from "../core/log";
import type { StreamPlayer } from "../core/oneStream";

export type PlaybackState = "idle" | "starting" | "playing" | "buffering" | "failed";

/** How long a stream may take to show its first frame before the screen says it didn't start. */
const START_LIMIT_MS = 20_000;

export class VegaPlayer implements StreamPlayer {
    private video: VideoPlayer | null = null;
    private surface: string | null = null;
    private startTimer: ReturnType<typeof setTimeout> | null = null;
    private lastTime = -1;
    onState: (state: PlaybackState) => void = () => {};

    /** From `KeplerVideoSurfaceView`'s onSurfaceViewCreated, or null when it goes away. */
    setSurface(handle: string | null): void {
        const video = this.video;
        try {
            if (handle === null && this.surface && video) video.clearSurfaceHandle(this.surface);
            if (handle !== null && video) video.setSurfaceHandle(handle);
        } catch (error) {
            log.error("VegaPlayer", "surface", error);
        }
        this.surface = handle;
    }

    async open(url: string, startAt?: number): Promise<void> {
        this.onState("starting");
        const video = new VideoPlayer();
        this.video = video;
        await video.initialize();
        if (this.video !== video) return;
        this.lastTime = -1;
        let sought = !startAt;
        video.addEventListener("timeupdate", () => {
            if (!sought) {
                // A movie or episode picks up where it was left once the player knows its length.
                sought = true;
                this.seekTo(startAt ?? 0);
            }
            const t = Number(video.currentTime);
            if (t !== this.lastTime) {
                this.lastTime = t;
                this.clearStartTimer();
                this.onState("playing");
            }
        });
        video.addEventListener("waiting", () => this.onState("buffering"));
        video.addEventListener("error", () => {
            log.warn("VegaPlayer", `playback error ${String((video as { error?: { code?: number } }).error?.code ?? "")}`);
            this.clearStartTimer();
            this.onState("failed");
        });
        if (this.surface) video.setSurfaceHandle(this.surface);
        video.autoplay = true;
        video.src = url;
        this.startTimer = setTimeout(() => {
            log.warn("VegaPlayer", "no first frame in time");
            this.onState("failed");
        }, START_LIMIT_MS);
    }

    async close(): Promise<void> {
        this.clearStartTimer();
        const video = this.video;
        this.video = null;
        if (!video) return;
        try {
            video.pause();
        } catch (error) {
            log.warn("VegaPlayer", "pause", error);
        }
        try {
            if (this.surface) video.clearSurfaceHandle(this.surface);
        } catch (error) {
            log.warn("VegaPlayer", "clear surface", error);
        }
        // The connection is only let go once deinitialize resolves; OneStream waits for it before the next open.
        await video.deinitialize();
        this.onState("idle");
    }

    /** Where a movie or episode is and how long it is, in seconds; zeros for live TV or before it starts. */
    position(): { at: number; length: number } {
        const video = this.video;
        const at = Number(video?.currentTime);
        const length = Number(video?.duration);
        return { at: Number.isFinite(at) ? at : 0, length: Number.isFinite(length) && length > 0 ? length : 0 };
    }

    seekTo(seconds: number): void {
        const video = this.video;
        if (!video) return;
        try {
            const { length } = this.position();
            video.currentTime = Math.max(0, length ? Math.min(seconds, length - 1) : seconds);
        } catch (error) {
            log.warn("VegaPlayer", "seek", error);
        }
    }

    seekBy(seconds: number): void {
        this.seekTo(this.position().at + seconds);
    }

    /** Play or pause; true when it's now paused. */
    togglePause(): boolean {
        const video = this.video;
        if (!video) return false;
        try {
            if (video.paused) {
                void video.play();
                return false;
            }
            video.pause();
            return true;
        } catch (error) {
            log.warn("VegaPlayer", "pause", error);
            return false;
        }
    }

    private clearStartTimer(): void {
        if (this.startTimer) clearTimeout(this.startTimer);
        this.startTimer = null;
    }
}
