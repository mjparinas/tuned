// Waiting is never a mystery (AGENTS.md): numbered steps, bytes of the full size or bytes so far, and about how much
// longer once the job has moved long enough to guess. Like core/Eta.kt and ui/ProgressEta on Android.

export interface StepProgress {
    /** 1-based. */
    step: number;
    steps: number;
    label: string;
    loaded?: number;
    total?: number | null;
    /** "about 3 min left", when it can tell. */
    eta?: string | null;
}

export function formatBytes(bytes: number): string {
    if (!Number.isFinite(bytes) || bytes < 0) return "0 KB";
    if (bytes < 1024 * 1024) return `${Math.max(1, Math.round(bytes / 1024))} KB`;
    const mb = bytes / (1024 * 1024);
    return `${mb < 10 ? mb.toFixed(1) : Math.round(mb)} MB`;
}

/** "4.2 MB of 18 MB", or "4.2 MB so far" when the size isn't known. */
export function bytesLine(loaded: number, total: number | null | undefined): string {
    return total && total > 0 ? `${formatBytes(loaded)} of ${formatBytes(total)}` : `${formatBytes(loaded)} so far`;
}

/** Guesses the time left from the rate since `startedAt`, after 3 seconds and 5% of the way, as core/Eta does. */
export function eta(loaded: number, total: number | null | undefined, startedAt: number, now: number): string | null {
    const elapsed = (now - startedAt) / 1000;
    if (!total || total <= 0 || loaded <= 0 || elapsed < 3 || loaded / total < 0.05 || loaded >= total) return null;
    const seconds = ((total - loaded) * elapsed) / loaded;
    if (seconds < 50) return "less than a minute left";
    const minutes = Math.round(seconds / 60);
    if (minutes < 60) return `about ${minutes} min left`;
    const hours = Math.floor(minutes / 60);
    return `about ${hours} h ${minutes % 60} min left`;
}

export function stepLine(p: StepProgress): string {
    return `Step ${p.step} of ${p.steps}: ${p.label}`;
}

export function detailLine(p: StepProgress): string | null {
    if (p.loaded === undefined) return null;
    const bytes = bytesLine(p.loaded, p.total);
    return p.eta ? `${bytes}, ${p.eta}` : bytes;
}

/** 1:02:03 or 12:34. */
export function clockTime(seconds: number): string {
    const s = Math.max(0, Math.floor(seconds));
    const h = Math.floor(s / 3600);
    const m = Math.floor((s % 3600) / 60);
    const ss = String(s % 60).padStart(2, "0");
    return h ? `${h}:${String(m).padStart(2, "0")}:${ss}` : `${m}:${ss}`;
}
