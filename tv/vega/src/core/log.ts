// The app's log (docs/decisions/diagnostics.md): every caught failure is written here, scrubbed of logins, and kept
// in memory (the last 300 lines) for Report a problem. Nothing here may throw: logging is the last thing that can fail.
// Replaced by @tuned/shared's log when it lands (docs/decisions/vega.md).

const MAX_LINES = 300;
const lines: string[] = [];
const secrets = new Set<string>();

/** A login to keep out of every line from now on, as typed and as it appears in a link. */
export function addSecret(value: string | undefined | null): void {
    if (!value || value.length < 2) return;
    secrets.add(value);
    try {
        secrets.add(encodeURIComponent(value));
    } catch {
        // A lone surrogate can't be encoded; the raw value is still scrubbed.
    }
}

export function clearSecrets(): void {
    secrets.clear();
}

/** `text` with every known login swapped for dots, and the login in any Xtream-style link too. */
export function scrub(text: string): string {
    let out = String(text);
    for (const secret of secrets) out = out.split(secret).join("•••");
    return out
        .replace(/([?&](?:username|password|user|pass|token)=)[^&\s#]*/gi, "$1•••")
        .replace(/\/(live|movie|series|timeshift)\/[^/\s]+\/[^/\s]+\//gi, "/$1/•••/•••/")
        .replace(/(Bearer )[A-Za-z0-9._-]+/g, "$1•••");
}

function write(level: string, tag: string, message: string, error?: unknown): void {
    try {
        const detail = error instanceof Error ? `: ${error.name}: ${error.message}` : error !== undefined ? `: ${String(error)}` : "";
        const line = `${new Date().toISOString()} ${level} ${tag} ${scrub(message + detail)}`;
        lines.push(line);
        if (lines.length > MAX_LINES) lines.splice(0, lines.length - MAX_LINES);
        if (level === "E") console.warn(line);
    } catch {
        // Nothing left to tell.
    }
}

export const log = {
    info: (tag: string, message: string) => write("I", tag, message),
    warn: (tag: string, message: string, error?: unknown) => write("W", tag, message, error),
    error: (tag: string, message: string, error?: unknown) => write("E", tag, message, error),
    lines: (): readonly string[] => [...lines],
};

/** Runs `work`, logging and returning `fallback` when it throws, so a failure is never silent and never a crash. */
export async function attempt<T>(tag: string, fallback: T, work: () => Promise<T>): Promise<T> {
    try {
        return await work();
    } catch (error) {
        log.error(tag, "failed", error);
        return fallback;
    }
}

/** Fire and forget, safely: the failure is logged and `onError` resets anything left busy. */
export function launchSafely(tag: string, work: () => Promise<unknown>, onError?: (error: unknown) => void): void {
    work().catch((error) => {
        log.error(tag, "failed", error);
        try {
            onError?.(error);
        } catch (inner) {
            log.error(tag, "onError failed", inner);
        }
    });
}
