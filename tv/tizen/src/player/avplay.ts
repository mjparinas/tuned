// Samsung's hardware player. It draws the picture on its own plane behind the page (an <object> of type
// application/avplayer marks where), so starting a channel and its formats (live TS, HLS, MKV and MP4 movies) are the
// TV's own. One AVPlay at a time, which suits the one-connection rule: close() finishes before the next open().
import type { StreamPlayer } from "@tuned/shared";
import type { AvPlay } from "../tizen.ts";

export interface PlayerEvents {
    onBuffering(buffering: boolean): void;
    onEnded(): void;
    onError(message: string): void;
    onSubtitle(text: string): void;
}

export class AvPlayPlayer implements StreamPlayer {
    private readonly av: AvPlay;
    private readonly events: PlayerEvents;

    constructor(av: AvPlay, events: PlayerEvents) {
        this.av = av;
        this.events = events;
    }

    async open(url: string, options: { headers: Record<string, string>; startAt: number }): Promise<void> {
        const av = this.av;
        av.open(url);
        const agent = Object.entries(options.headers).find(([k]) => k.toLowerCase() === "user-agent")?.[1];
        if (agent) av.setStreamingProperty?.("USER_AGENT", agent);
        av.setDisplayRect(0, 0, 1920, 1080);
        av.setDisplayMethod?.("PLAYER_DISPLAY_MODE_LETTER_BOX");
        av.setListener({
            onbufferingstart: () => this.events.onBuffering(true),
            onbufferingcomplete: () => this.events.onBuffering(false),
            onstreamcompleted: () => this.events.onEnded(),
            onerror: (type) => this.events.onError(String(type)),
            onsubtitlechange: (_duration, text) => this.events.onSubtitle(String(text ?? "")),
        });
        await new Promise<void>((resolve, reject) =>
            av.prepareAsync(resolve, (e) => reject(new Error(String((e as { name?: string })?.name ?? e)))),
        );
        if (options.startAt > 0) await new Promise<void>((resolve) => av.seekTo(options.startAt, resolve, () => resolve()));
        av.play();
    }

    async close(): Promise<void> {
        try {
            const state = this.av.getState();
            if (state !== "NONE" && state !== "IDLE") this.av.stop();
        } catch {
            // Already stopped.
        }
        try {
            this.av.close();
        } catch {
            // Already closed.
        }
    }

    pause(): void {
        this.av.pause();
    }

    resume(): void {
        this.av.play();
    }

    seek(ms: number): void {
        this.av.seekTo(Math.max(0, ms));
    }

    position(): number {
        try {
            return this.av.getCurrentTime();
        } catch {
            return 0;
        }
    }

    duration(): number {
        try {
            return this.av.getDuration();
        } catch {
            return 0;
        }
    }

    /** Audio and caption tracks, for the player's buttons. */
    tracks(): { index: number; type: string; label: string }[] {
        try {
            return (this.av.getTotalTrackInfo?.() ?? [])
                .filter((t) => t.type === "AUDIO" || t.type === "TEXT")
                .map((t) => {
                    let label = "";
                    try {
                        const extra = JSON.parse(t.extra_info) as { language?: string; track_lang?: string };
                        label = extra.language ?? extra.track_lang ?? "";
                    } catch {
                        // Some streams give no details.
                    }
                    return { index: t.index, type: t.type, label };
                });
        } catch {
            return [];
        }
    }

    selectTrack(type: string, index: number): void {
        try {
            if (type === "TEXT" && index < 0) {
                this.av.setSilentSubtitle?.(true);
                return;
            }
            this.av.setSelectTrack?.(type, index);
            if (type === "TEXT") this.av.setSilentSubtitle?.(false);
        } catch {
            // The stream refused the track; the old one keeps playing.
        }
    }
}
