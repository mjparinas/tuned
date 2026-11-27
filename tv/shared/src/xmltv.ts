// An XMLTV guide read as it downloads, the TV half of core/XmltvParser.kt: hand it text in any pieces and it answers
// with each programme once its closing tag arrives. Guides run to hundreds of megabytes, so only the programmes `keep`
// wants cost anything, and the reader holds at most one element's text. No DOMParser (Vega has none).
import { readText, type ReadOptions } from "./http.ts";
import type { Fetch } from "./platform.ts";

export interface Programme {
    readonly channel: string;
    /** Epoch milliseconds. */
    readonly start: number;
    readonly stop: number;
    readonly title: string;
    readonly desc: string | null;
    /** The guide's `<category>` tags, at most four ("Sports", "Movie"). */
    readonly categories: string[];
}

export interface GuideChannel {
    readonly id: string;
    /** The first `<display-name>`, or the id. */
    readonly name: string;
    readonly icon: string | null;
}

export interface GuidePiece {
    readonly programmes: Programme[];
    readonly channels: GuideChannel[];
}

const MAX_CATEGORIES = 4;

/**
 * XMLTV times: `20261117050000 +0000`, the offset optional (then UTC), seconds optional, and sometimes no space before
 * the offset. Null when it isn't one.
 */
export function parseTime(value: string): number | null {
    const v = value.trim();
    if (v.length < 12 || !/^\d{12}/.test(v)) return null;
    const n = (from: number, to: number) => Number(v.slice(from, to));
    const seconds = v.length >= 14 && /^\d\d/.test(v.slice(12, 14)) ? n(12, 14) : 0;
    let millis = Date.UTC(n(0, 4), n(4, 6) - 1, n(6, 8), n(8, 10), n(10, 12), seconds);
    const offset = v.replace(/^\d+/, "").trim();
    if (/^[+-]\d{4}$/.test(offset)) {
        const minutes = Number(offset.slice(1, 3)) * 60 + Number(offset.slice(3, 5));
        millis -= (offset[0] === "+" ? 1 : -1) * minutes * 60_000;
    }
    return millis;
}

const ENTITIES: Record<string, string> = { amp: "&", lt: "<", gt: ">", quot: '"', apos: "'", nbsp: " " };

export function decodeEntities(text: string): string {
    if (!text.includes("&")) return text;
    return text.replace(/&(#x[0-9a-f]+|#\d+|[a-z]+);/gi, (whole, name: string) => {
        if (name[0] === "#") {
            const code = name[1] === "x" || name[1] === "X" ? parseInt(name.slice(2), 16) : parseInt(name.slice(1), 10);
            return Number.isFinite(code) && code > 0 && code <= 0x10ffff ? String.fromCodePoint(code) : whole;
        }
        return ENTITIES[name.toLowerCase()] ?? whole;
    });
}

function textOf(inner: string): string {
    const cdata = /^\s*<!\[CDATA\[([\s\S]*?)\]\]>\s*$/.exec(inner);
    if (cdata) return cdata[1];
    return decodeEntities(inner.replace(/<[^>]*>/g, ""));
}

function attributes(openTag: string): Map<string, string> {
    const out = new Map<string, string>();
    const pattern = /([\w:-]+)\s*=\s*("([^"]*)"|'([^']*)')/g;
    for (let m = pattern.exec(openTag); m; m = pattern.exec(openTag)) out.set(m[1], decodeEntities(m[3] ?? m[4] ?? ""));
    return out;
}

function children(body: string, tag: string, limit: number): string[] {
    const out: string[] = [];
    const pattern = new RegExp(`<${tag}(?:\\s[^>]*)?>([\\s\\S]*?)</${tag}>`, "g");
    for (let m = pattern.exec(body); m && out.length < limit; m = pattern.exec(body)) out.push(textOf(m[1]).trim());
    return out;
}

type Keep = (channel: string, start: number, stop: number) => boolean;

export class XmltvReader {
    private buffer = "";
    private readonly keep: Keep;
    /** How many programmes were kept so far. */
    count = 0;

    constructor(keep: Keep = () => true) {
        this.keep = keep;
    }

    feed(text: string): GuidePiece {
        this.buffer += text;
        return this.drain(false);
    }

    end(): GuidePiece {
        const out = this.drain(true);
        this.buffer = "";
        return out;
    }

    private drain(last: boolean): GuidePiece {
        const programmes: Programme[] = [];
        const channels: GuideChannel[] = [];
        let at = 0;
        const b = this.buffer;
        for (;;) {
            const p = b.indexOf("<programme", at);
            const c = b.indexOf("<channel", at);
            const start = p < 0 ? c : c < 0 ? p : Math.min(p, c);
            if (start < 0) {
                // Keep only a tail that could be the start of a tag cut in half.
                const lt = b.lastIndexOf("<");
                at = lt >= at && b.length - lt < 16 ? lt : b.length;
                break;
            }
            const tag = start === p ? "programme" : "channel";
            const openEnd = b.indexOf(">", start);
            if (openEnd < 0) {
                at = start;
                break;
            }
            const openTag = b.slice(start, openEnd + 1);
            let close: number;
            let body: string;
            if (openTag.endsWith("/>")) {
                close = openEnd + 1;
                body = "";
            } else {
                const closeTag = b.indexOf(`</${tag}>`, openEnd);
                if (closeTag < 0) {
                    at = start;
                    break;
                }
                close = closeTag + tag.length + 3;
                body = b.slice(openEnd + 1, closeTag);
            }
            at = close;
            const attrs = attributes(openTag);
            if (tag === "channel") {
                const id = attrs.get("id")?.trim();
                if (!id) continue;
                const name = children(body, "display-name", 1)[0] || id;
                const icon = /<icon[^>]*\ssrc\s*=\s*["']([^"']+)["']/.exec(body)?.[1] ?? null;
                channels.push({ id, name, icon: icon ? decodeEntities(icon) : null });
                continue;
            }
            const channel = attrs.get("channel");
            const begin = parseTime(attrs.get("start") ?? "");
            const stop = parseTime(attrs.get("stop") ?? "");
            if (!channel || begin === null || stop === null || stop <= begin || !this.keep(channel, begin, stop)) continue;
            const title = children(body, "title", 1)[0] || "Untitled";
            const desc = children(body, "desc", 1)[0] || null;
            const categories = children(body, "category", MAX_CATEGORIES).filter(Boolean);
            programmes.push({ channel, start: begin, stop, title, desc, categories });
            this.count++;
        }
        this.buffer = last ? "" : b.slice(at);
        return { programmes, channels };
    }
}

/** Downloads a guide and reads it as it arrives; each piece's programmes go to `onPiece` before the next is read. */
export async function loadGuide(
    fetch: Fetch,
    url: string,
    keep: Keep,
    onPiece: (piece: GuidePiece) => void | Promise<void>,
    options: ReadOptions = {},
): Promise<number> {
    const reader = new XmltvReader(keep);
    await readText(
        fetch,
        url,
        async (text) => {
            const piece = reader.feed(text);
            if (piece.programmes.length || piece.channels.length) await onPiece(piece);
        },
        options,
    );
    const rest = reader.end();
    if (rest.programmes.length || rest.channels.length) await onPiece(rest);
    return reader.count;
}
