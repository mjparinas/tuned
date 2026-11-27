// Downloads that report real progress (AGENTS.md: "Waiting is never a mystery"): bytes so far, and of how many when
// the server says. Text arrives in pieces where the platform streams (Tizen's fetch), or all at once (Vega).
import { Utf8Stream } from "./bytes.ts";
import type { AbortSignalLike, Fetch } from "./platform.ts";

export interface Progress {
    readonly done: number;
    /** 0 when the server doesn't say how big it is. */
    readonly total: number;
}

/** A failed download, with a message safe to show and log (no login in it). */
export class HttpError extends Error {
    readonly status: number;

    constructor(message: string, status = 0) {
        super(message);
        this.name = "HttpError";
        this.status = status;
    }
}

export interface ReadOptions {
    readonly headers?: Record<string, string>;
    readonly signal?: AbortSignalLike;
    readonly onProgress?: (progress: Progress) => void;
    /** Takes the login out of anything that reaches an error message. */
    readonly redact?: (text: string) => string;
}

const PIECE = 64 * 1024;

/** Reads `url` as text, handing each piece to `onText` as it arrives. Resolves with the byte count. */
export async function readText(
    fetch: Fetch,
    url: string,
    onText: (text: string) => void | Promise<void>,
    options: ReadOptions = {},
): Promise<number> {
    const redact = options.redact ?? ((t: string) => t);
    let response;
    try {
        response = await fetch(url, { headers: options.headers, signal: options.signal });
    } catch (error) {
        throw new HttpError(redact(`Couldn't reach the server: ${String((error as Error)?.message ?? error)}`));
    }
    if (!response.ok) throw new HttpError(`The server answered ${response.status}`, response.status);
    const total = Number(response.headers.get("content-length")) || 0;
    const reader = response.body?.getReader?.();
    let done = 0;
    if (!reader) {
        const text = await response.text();
        done = text.length;
        for (let at = 0; at < text.length; at += PIECE) {
            await onText(text.slice(at, at + PIECE));
            options.onProgress?.({ done: Math.min(at + PIECE, text.length), total: total || text.length });
        }
        return done;
    }
    const decoder = new Utf8Stream();
    for (;;) {
        if (options.signal?.aborted) {
            await reader.cancel().catch(() => undefined);
            throw new HttpError("Cancelled");
        }
        const { done: finished, value } = await reader.read();
        if (finished) break;
        if (!value) continue;
        done += value.length;
        await onText(decoder.decode(value));
        options.onProgress?.({ done, total });
    }
    const rest = decoder.decode(new Uint8Array(0), true);
    if (rest) await onText(rest);
    return done;
}

/** Reads `url` as JSON, with progress. A server's HTML error page reads as an HttpError, not a crash. */
export async function readJson(fetch: Fetch, url: string, options: ReadOptions = {}): Promise<unknown> {
    const parts: string[] = [];
    await readText(
        fetch,
        url,
        (t) => {
            parts.push(t);
        },
        options,
    );
    const text = parts.join("");
    try {
        return JSON.parse(text);
    } catch {
        throw new HttpError("The server's answer wasn't readable");
    }
}
