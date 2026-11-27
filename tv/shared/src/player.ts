// One stream connection, ever (Melvin, 2026-11-17: "i dont want to get banned"; scope.md). Each TV app's player
// (AVPlay on Tizen, Vega's VideoPlayer) sits behind this guard: the old stream is closed, and that close has finished,
// before the next one opens. Nothing prefetches and nothing previews.

/** What an app's player must do. `close` resolves only once the old connection is really shut. */
export interface StreamPlayer {
    open(url: string, options: { headers: Record<string, string>; startAt: number }): Promise<void>;
    close(): Promise<void>;
}

export class OneConnection {
    private readonly player: StreamPlayer;
    private queue: Promise<unknown> = Promise.resolve();
    private current: string | null = null;
    /** Opens in flight or playing: never more than 1 (the device smoke test's rule, on the TV). */
    open = 0;
    /** The most connections ever open at once, for tests. */
    highest = 0;

    constructor(player: StreamPlayer) {
        this.player = player;
    }

    /** What's playing, or null. */
    get playing(): string | null {
        return this.current;
    }

    /**
     * Closes whatever plays, then opens `url`. Calls run in order, so channel up pressed five times quickly closes
     * and opens one after another and never overlaps. A failed open leaves nothing open and rejects.
     */
    play(url: string, options: { headers?: Record<string, string>; startAt?: number } = {}): Promise<void> {
        const run = this.queue.then(async () => {
            await this.shut();
            this.open++;
            this.highest = Math.max(this.highest, this.open);
            try {
                await this.player.open(url, { headers: options.headers ?? {}, startAt: options.startAt ?? 0 });
                this.current = url;
            } catch (error) {
                await this.player.close().catch(() => undefined);
                this.open--;
                throw error;
            }
        });
        this.queue = run.catch(() => undefined);
        return run;
    }

    /** Closes the stream, after anything already queued. */
    stop(): Promise<void> {
        const run = this.queue.then(() => this.shut());
        this.queue = run.catch(() => undefined);
        return run;
    }

    private async shut(): Promise<void> {
        if (this.open === 0) return;
        try {
            await this.player.close();
        } finally {
            this.open = 0;
            this.current = null;
        }
    }
}
