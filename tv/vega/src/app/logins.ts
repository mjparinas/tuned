// @tuned/shared's playlist logins as the stick keeps them (core/models.ts `Login`).
import { fromHandoff, type PlaylistLogin } from "@tuned/shared/login";
import type { Login } from "../core/models";

export function toLogin(login: PlaylistLogin | null | undefined): Login | null {
    if (!login) return null;
    const name = login.name || undefined;
    if (login.kind === "xtream") return { kind: "xtream", server: login.server, username: login.username, password: login.password, name };
    return { kind: "link", playlistUrl: login.url, guideUrl: login.guideUrl || undefined, name };
}

/** The playlists in an account's backup (the Android app's sync copy), in its order. */
export const backupLogins = (backup: unknown): Login[] =>
    backup ? fromHandoff(JSON.stringify(backup)).flatMap((l) => toLogin(l) ?? []) : [];

/** The first playlist in an account's backup, as on Android's TV sign-in. */
export const backupLogin = (backup: unknown): Login | null => backupLogins(backup)[0] ?? null;

/** The same playlist: the same server and username, or the same link. */
export function sameLogin(a: Login, b: Login): boolean {
    if (a.kind === "xtream" && b.kind === "xtream")
        return a.server.replace(/\/+$/, "") === b.server.replace(/\/+$/, "") && a.username === b.username;
    return a.kind === "link" && b.kind === "link" && a.playlistUrl === b.playlistUrl;
}

/** What a playlist is called on screen: its own name, else its server's or link's host. Never the username or password. */
export function playlistName(login: Login): string {
    if (login.name) return login.name;
    const address = login.kind === "xtream" ? login.server : login.playlistUrl;
    try {
        return new URL(address).host || address;
    } catch {
        return address.replace(/^[a-z]+:\/\//i, "").split(/[/?#]/)[0] || address;
    }
}
