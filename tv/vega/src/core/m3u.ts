// An M3U playlist read in pieces as it downloads (`feed`, then `end`), since React Native's fetch can't stream a body.
// It keeps what Live TV needs: the name, the group, the logo, the guide id and the link. Films and episodes (an
// Xtream /movie/ or /series/ path, or a file extension) are left out until the stick has Movies and Shows. A port of
// the parts of core/M3uParser.kt and core/Classifier.kt Live TV uses; replaced by @tuned/shared's parser when it lands.
import type { Category, Channel } from "./models";

const ATTR = /([a-zA-Z0-9-]+)="([^"]*)"/g;
const VOD = /\/(movie|series)\/|\.(mp4|mkv|avi|mov|m4v|wmv|flv)(\?|$)/i;

export class M3uReader {
    private rest = "";
    private pending: { name: string; group: string; logo: string | null; guideId?: string; number?: number } | null = null;
    private groups = new Map<string, Category>();
    readonly channels: Channel[] = [];
    /** Lines read, so a broken file can be told apart from an empty one. */
    lines = 0;
    sawHeader = false;

    feed(text: string): void {
        const all = this.rest + text;
        const cut = all.lastIndexOf("\n");
        if (cut < 0) {
            this.rest = all;
            return;
        }
        this.rest = all.slice(cut + 1);
        for (const line of all.slice(0, cut).split("\n")) this.line(line);
    }

    end(): { categories: Category[]; channels: Channel[] } {
        if (this.rest) this.line(this.rest);
        this.rest = "";
        return { categories: [...this.groups.values()], channels: this.channels };
    }

    private line(raw: string): void {
        const line = raw.replace(/^﻿/, "").trim();
        if (!line) return;
        this.lines++;
        if (line.startsWith("#EXTM3U")) {
            this.sawHeader = true;
            return;
        }
        if (line.startsWith("#EXTINF")) {
            const comma = this.commaAfterAttributes(line);
            const attrs: Record<string, string> = {};
            for (const m of line.slice(0, comma).matchAll(ATTR)) attrs[m[1].toLowerCase()] = m[2];
            const name = (line.slice(comma + 1).trim() || attrs["tvg-name"] || "").trim();
            const number = Number(attrs["tvg-chno"] ?? attrs["channel-number"]);
            this.pending = {
                name,
                group: (attrs["group-title"] ?? "").trim(),
                logo: /^https?:\/\//.test(attrs["tvg-logo"] ?? "") ? attrs["tvg-logo"] : null,
                guideId: attrs["tvg-id"] || undefined,
                number: Number.isFinite(number) && number > 0 ? number : undefined,
            };
            return;
        }
        if (line.startsWith("#EXTGRP:") && this.pending && !this.pending.group) {
            this.pending.group = line.slice(8).trim();
            return;
        }
        if (line.startsWith("#")) return;
        const entry = this.pending;
        this.pending = null;
        if (!/^(https?|rtmp|rtsp|udp):\/\//i.test(line) || VOD.test(line)) return;
        const group = entry?.group || "Other";
        let category = this.groups.get(group);
        if (!category) {
            category = { id: `g${this.groups.size}`, name: group };
            this.groups.set(group, category);
        }
        const position = this.channels.length + 1;
        const name = entry?.name || `Channel ${position}`;
        this.channels.push({
            id: `${position}:${name}`,
            name,
            number: entry?.number ?? position,
            categoryId: category.id,
            logo: entry?.logo ?? null,
            url: line,
            guideId: entry?.guideId,
        });
    }

    /** The comma that ends the attributes: the first one outside quotes. */
    private commaAfterAttributes(line: string): number {
        let quoted = false;
        for (let i = 0; i < line.length; i++) {
            const c = line[i];
            if (c === '"') quoted = !quoted;
            else if (c === "," && !quoted) return i;
        }
        return line.length;
    }
}
