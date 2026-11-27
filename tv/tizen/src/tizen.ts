// The Samsung TV around the app: its remote's keys, its device APIs and leaving the app. In the browser harness
// (desktop Chromium, for development and CI) none of these exist and everything here degrades to keyboard keys.

declare global {
    interface Window {
        tizen?: {
            tvinputdevice?: { registerKey(name: string): void; getSupportedKeys?(): { name: string; code: number }[] };
            application?: { getCurrentApplication(): { exit(): void; hide(): void } };
            systeminfo?: unknown;
        };
        webapis?: {
            avplay?: AvPlay;
            productinfo?: { getRealModel?(): string; getModel?(): string; getFirmware?(): string };
        };
    }
}

/** Samsung's player (webapis.avplay), only the parts Tuned uses. */
export interface AvPlay {
    open(url: string): void;
    close(): void;
    prepareAsync(ok: () => void, fail: (error: unknown) => void): void;
    play(): void;
    pause(): void;
    stop(): void;
    seekTo(ms: number, ok?: () => void, fail?: (e: unknown) => void): void;
    getCurrentTime(): number;
    getDuration(): number;
    getState(): string;
    setDisplayRect(x: number, y: number, width: number, height: number): void;
    setDisplayMethod?(method: string): void;
    setStreamingProperty?(name: string, value: string): void;
    setListener(listener: Record<string, (...args: unknown[]) => void>): void;
    getTotalTrackInfo?(): { index: number; type: string; extra_info: string }[];
    setSelectTrack?(type: string, index: number): void;
    setSilentSubtitle?(silent: boolean): void;
}

export const onTv = (): boolean => typeof window !== "undefined" && Boolean(window.tizen);

/** Keys a Samsung remote sends only after the app asks for them. */
const EXTRA_KEYS = [
    "ChannelUp",
    "ChannelDown",
    "MediaPlayPause",
    "MediaPlay",
    "MediaPause",
    "MediaStop",
    "MediaFastForward",
    "MediaRewind",
    "Info",
    "Guide",
    "PreviousChannel",
    "ColorF0Red",
    "ColorF1Green",
    "ColorF2Yellow",
    "ColorF3Blue",
    "0",
    "1",
    "2",
    "3",
    "4",
    "5",
    "6",
    "7",
    "8",
    "9",
];

export function registerKeys(): void {
    const input = window.tizen?.tvinputdevice;
    if (!input) return;
    for (const key of EXTRA_KEYS) {
        try {
            input.registerKey(key);
        } catch {
            // An older remote or model without that key: the rest still work.
        }
    }
}

/** Leaves the app, as Back on Home does (Samsung's rules: Back must always work and exit cleanly from the top). */
export function exitApp(): void {
    try {
        window.tizen?.application?.getCurrentApplication().exit();
    } catch {
        // Not on a TV.
    }
}

/** What the Tuned account's device list calls this TV. */
export function deviceName(): string {
    try {
        const model = window.webapis?.productinfo?.getRealModel?.() ?? window.webapis?.productinfo?.getModel?.();
        if (model) return `Samsung TV ${model}`.slice(0, 60);
    } catch {
        // productinfo needs a privilege; without it the plain name will do.
    }
    return "Samsung TV";
}
