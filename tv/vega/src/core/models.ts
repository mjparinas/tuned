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
}

export interface Lineup {
    categories: Category[];
    channels: Channel[];
    /** When it was downloaded, in ms. */
    savedAt: number;
}

export interface Programme {
    title: string;
    start: number;
    end: number;
}
