// The remote's keys as actions. Samsung sends its own codes (Back is 10009, channel up 427); the browser harness sends
// a keyboard's, so both map here and nothing else looks at key codes.

export type Action =
    | "up"
    | "down"
    | "left"
    | "right"
    | "ok"
    | "back"
    | "channelUp"
    | "channelDown"
    | "playPause"
    | "play"
    | "pause"
    | "stop"
    | "forward"
    | "rewind"
    | "info"
    | "guide"
    | "previousChannel"
    | "red"
    | "green"
    | "yellow"
    | "blue";

const BY_CODE: Record<number, Action> = {
    37: "left",
    38: "up",
    39: "right",
    40: "down",
    13: "ok",
    10009: "back",
    27: "back",
    8: "back",
    427: "channelUp",
    428: "channelDown",
    33: "channelUp",
    34: "channelDown",
    10252: "playPause",
    415: "play",
    19: "pause",
    413: "stop",
    417: "forward",
    412: "rewind",
    457: "info",
    458: "guide",
    10190: "previousChannel",
    403: "red",
    404: "green",
    405: "yellow",
    406: "blue",
};

export interface KeyPress {
    readonly action: Action | null;
    /** 0 to 9 for a number key, otherwise null. */
    readonly digit: number | null;
}

export function readKey(event: { keyCode: number; key?: string; target?: unknown }): KeyPress {
    const code = event.keyCode;
    if (code >= 48 && code <= 57) return { action: null, digit: code - 48 };
    if (code >= 96 && code <= 105) return { action: null, digit: code - 96 };
    // Backspace is Back only outside a text box, where it deletes.
    if (code === 8 && isTyping(event.target)) return { action: null, digit: null };
    return { action: BY_CODE[code] ?? null, digit: null };
}

export function isTyping(target: unknown): boolean {
    const el = target as { tagName?: string } | null;
    return el?.tagName === "INPUT" || el?.tagName === "TEXTAREA";
}
