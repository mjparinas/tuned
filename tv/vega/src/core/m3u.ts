// An M3U playlist read in pieces as it downloads (`feed`, then `end`), since React Native's fetch can't stream a body.
// It keeps what Live TV needs (the name, the group, the logo, the guide id and the link) and sorts movies and episodes
// (an Xtream /movie/ or /series/ path, or a file extension) into Movies and Shows, an episode going to its show by the
// name before "S01E02". A port of the parts of core/M3uParser.kt and core/Classifier.kt the stick uses.
import type { Category, Channel, Episode, Title } from "./models";

const ATTR = /([a-zA-Z0-9-]+)="([^"]*)"/g;
const VOD = /\/(movie|series)\/|\.(mp4|mkv|avi|mov|m4v|wmv|flv)(\?|$)/i;
const EPISODE = /^(.*?)[\s\-–:|.]*\bS(\d{1,2})\s*E(\d{1,3})\b(.*)$/i;
const YEAR = /\((19\d\d|20\d\d)\)/;

export class M3uReader {
    private rest = "";
    private pending: { name: string; group: string; logo: string | null; guideId?: string; number?: number } | null = null;
    private groups = new Map<string, Category>();
    private movieGroups = new Map<string, Category>();
    private showGroups = new Map<string, Category>();
    private showsByName = new Map<string, Title & { episodes: Episode[] }>();
    readonly channels: Channel[] = [];
    readonly movies: Title[] = [];
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

    end(): {
        categories: Category[];
        channels: Channel[];
        movieCategories: Category[];
        showCategories: Category[];
        movies: Title[];
        shows: Title[];
    } {
        if (this.rest) this.line(this.rest);
        this.rest = "";
        const shows = [...this.showsByName.values()];
        for (const show of shows) show.episodes.sort((a, b) => a.season - b.season || a.episode - b.episode);
        return {
            categories: [...this.groups.values()],
            channels: this.channels,
            movieCategories: [...this.movieGroups.values()],
            showCategories: [...this.showGroups.values()],
            movies: this.movies,
            shows,
        };
    }

    private category(groups: Map<string, Category>, name: string, prefix: string): Category {
        let category = groups.get(name);
        if (!category) {
            category = { id: `${prefix}${groups.size}`, name };
            groups.set(name, category);
        }
        return category;
    }

    /** A movie, or an episode of the show named before its "S01E02". */
    private vod(entry: { name: string; group: string; logo: string | null } | null, url: string): void {
        const name = entry?.name || "";
        const group = entry?.group || "Other";
        const episode = EPISODE.exec(name);
        if (episode || /\/series\//i.test(url)) {
            const showName = (episode?.[1] ?? name).trim() || name;
            if (!showName) return;
            let show = this.showsByName.get(showName);
            if (!show) {
                show = {
                    id: `s:${showName}`,
                    kind: "show",
                    name: showName,
                    categoryId: this.category(this.showGroups, group, "s").id,
                    poster: entry?.logo ?? null,
                    year: Number(YEAR.exec(showName)?.[1]) || null,
                    rating: null,
                    episodes: [],
                };
                this.showsByName.set(showName, show);
            }
            const rest = (episode?.[4] ?? "").replace(/^[\s\-–:|.]+|[\s\-–:|.]+$/g, "").trim();
            show.episodes.push({
                id: `e${this.lines}`,
                season: Number(episode?.[2]) || 1,
                episode: Number(episode?.[3]) || show.episodes.length + 1,
                name: rest || null,
                url,
            });
            return;
        }
        if (!name) return;
        this.movies.push({
            id: `m${this.movies.length + 1}:${name}`,
            kind: "movie",
            name,
            categoryId: this.category(this.movieGroups, group, "m").id,
            poster: entry?.logo ?? null,
            year: Number(YEAR.exec(name)?.[1]) || null,
            rating: null,
            url,
        });
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
        if (!/^(https?|rtmp|rtsp|udp):\/\//i.test(line)) return;
        if (VOD.test(line)) return this.vod(entry, line);
        const category = this.category(this.groups, entry?.group || "Other", "g");
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
