// One stream connection, ever (AGENTS.md; Melvin, 2026-11-17: "i dont want to get banned"). Every stream goes through
// this guard: the old one is closed, and its close has finished, before the next one opens, and a newer request
// cancels an older one that is still waiting. The interface is the one agreed for @tuned/shared, which each TV app
// backs with its own player (AVPlay on Samsung, Amazon's VideoPlayer here).
import { log } from "./log";

export interface StreamPlayer {
    /** Opens `url` and starts playing, from `startAt` seconds for a movie or episode. Resolves once it's asked for. */
    open(url: string, startAt?: number): Promise<void>;
    /** Stops and lets go of the connection. Resolves only when it's closed. */
    close(): Promise<void>;
    /** Where a movie or episode is, in seconds, so it can pick up there next time. */
    position?(): { at: number; length: number };
}

export class OneStream {
    private chain: Promise<void> = Promise.resolve();
    private generation = 0;
    private openUrl: string | null = null;
    /** How many streams are open: never more than one (the tests check it). */
    open = 0;

    constructor(private readonly player: StreamPlayer) {}

    get current(): string | null {
        return this.openUrl;
    }

    /** Plays `url`, closing whatever played before. A later `play` or `stop` wins over this one. */
    play(url: string, startAt?: number): Promise<boolean> {
        const mine = ++this.generation;
        const step = this.chain.then(async () => {
            await this.closeCurrent();
            if (mine !== this.generation) return false;
            this.open++;
            this.openUrl = url;
            try {
                await this.player.open(url, startAt);
                return true;
            } catch (error) {
                log.error("OneStream", "open failed", error);
                await this.closeCurrent();
                return false;
            }
        });
        this.chain = step.then(
            () => undefined,
            () => undefined,
        );
        return step;
    }

    stop(): Promise<void> {
        this.generation++;
        const step = this.chain.then(() => this.closeCurrent());
        this.chain = step.catch(() => undefined);
        return step;
    }

    private async closeCurrent(): Promise<void> {
        if (this.openUrl === null) return;
        this.openUrl = null;
        try {
            await this.player.close();
        } catch (error) {
            log.error("OneStream", "close failed", error);
        } finally {
            this.open = Math.max(0, this.open - 1);
        }
    }
}
