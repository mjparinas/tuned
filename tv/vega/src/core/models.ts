import type { ScrobbleMedia } from "@tuned/shared/simkl";
import type { CatchUp } from "@tuned/shared/guide";

/** A playlist the stick signs in to: an Xtream login, or an M3U link with an optional guide. */
export type Login =
    | { kind: "xtream"; server: string; username: string; password: string; name?: string }
    | { kind: "link"; playlistUrl: string; guideUrl?: string; name?: string };

export interface Category {
    id: string;
    name: string;
}

export interface Channel {
    /** Stable within the playlist: the Xtream stream id, or the link's position and name. */
    id: string;
    name: string;
    number: number;
    categoryId: string;
    logo: string | null;
    url: string;
    /** The Xtream stream id, for now and next. */
    streamId?: number;
    guideId?: string;
    /** How its recordings are reached, for a channel that keeps them (catch-up); missing when it keeps none. */
    catchUp?: CatchUp;
    /** The playlist's own name, for a channel renamed on the phone or website, so search finds it by either. */
    was?: string;
}

export interface Lineup {
    categories: Category[];
    channels: Channel[];
    /** An Xtream server's time zone (`server_info.timezone`), for catch-up links' dates. */
    timezone?: string | null;
    /** A link's guide named in its `#EXTM3U` line (`url-tvg`), when the login gave none. */
    guideUrl?: string;
    /** When it was downloaded, in ms. */
    savedAt: number;
}

export interface Programme {
    title: string;
    start: number;
    end: number;
}

/** A programme in the grid guide, in the shared guide code's shape. */
export interface GuideProgramme {
    start: number;
    stop: number;
    title: string;
    desc: string | null;
}

/** A movie or a show in Movies and Shows. */
export interface Title {
    /** Stable within the playlist: "m" or "s" and the Xtream id, or the link's position or the show's name. */
    id: string;
    kind: "movie" | "show";
    name: string;
    categoryId: string;
    poster: string | null;
    year: number | null;
    /** Out of 10, as the provider gives it; null when it gives none. */
    rating: number | null;
    /** When the provider added it, in ms, for Recently added; missing when it doesn't say. */
    added?: number;
    plot?: string;
    /** A movie's link. */
    url?: string;
    /** An Xtream show, whose episodes are read when it's opened (`get_series_info`). */
    seriesId?: number;
    /** A show from an M3U link, whose episodes came with the playlist. */
    episodes?: Episode[];
}

export interface Episode {
    id: string;
    season: number;
    episode: number;
    /** The episode's own name where the provider gives one (Melvin, 2026-11-17: real names over "S1 E1"). */
    name: string | null;
    url: string;
    plot?: string;
}

export interface Library {
    movieCategories: Category[];
    showCategories: Category[];
    movies: Title[];
    shows: Title[];
    savedAt: number;
}

/** What the player is playing when it isn't a live channel. */
export interface Playable {
    /** The movie's or episode's id, for where it was left. */
    id: string;
    title: string;
    /** The movie, or the show the episode is in, for Continue Watching. */
    titleId?: string;
    /** A recording from the guide (catch-up): it seeks, but where it was left isn't kept. */
    replay?: boolean;
    /** "Season 1, episode 2" under a show's name. */
    subtitle?: string;
    url: string;
    /** What Simkl is told is playing: the movie, or the show with the season and episode. */
    scrobble?: ScrobbleMedia;
}
