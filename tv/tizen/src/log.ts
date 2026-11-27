// The app's log (diagnostics.md): the last few hundred lines in memory, shown in Settings › About. Callers pass
// text that already went through `redact`; anything that looks like a login in a link is scrubbed again here.

const MAX = 300;
const lines: string[] = [];

const scrub = (text: string) =>
    text
        .replace(/(username|password|token|mac)=[^&\s]*/gi, "$1=•••")
        .replace(/(\/(?:live|movie|series|timeshift))\/[^/\s]+\/[^/\s]+\//gi, "$1/•••/•••/");

export function log(tag: string, message: string): void {
    const line = `${new Date().toISOString().slice(11, 19)} ${tag}: ${scrub(message)}`;
    lines.push(line);
    if (lines.length > MAX) lines.shift();
    console.info(line);
}

export const logLines = (): readonly string[] => lines;

/** Runs `work`, logging and swallowing any failure, so a background job can never take the app down. */
export async function safely<T>(tag: string, work: () => Promise<T>, fallback: T): Promise<T> {
    try {
        return await work();
    } catch (error) {
        log(tag, `Failed: ${String((error as Error)?.message ?? error)}`);
        return fallback;
    }
}

window.addEventListener?.("error", (e) => log("Crash", String(e.message)));
window.addEventListener?.("unhandledrejection", (e) =>
    log("Crash", String((e as PromiseRejectionEvent).reason?.message ?? (e as PromiseRejectionEvent).reason)),
);
