// @tuned/shared's playlist logins as the stick keeps them (core/models.ts `Login`).
import { fromHandoff, type PlaylistLogin } from "@tuned/shared/login";
import type { Login } from "../core/models";

export function toLogin(login: PlaylistLogin | null | undefined): Login | null {
    if (!login) return null;
    const name = login.name || undefined;
    if (login.kind === "xtream") return { kind: "xtream", server: login.server, username: login.username, password: login.password, name };
    return { kind: "link", playlistUrl: login.url, guideUrl: login.guideUrl || undefined, name };
}

/** The first playlist in an account's backup (the Android app's sync copy), as on Android's TV sign-in. */
export const backupLogin = (backup: unknown): Login | null => (backup ? toLogin(fromHandoff(JSON.stringify(backup))[0]) : null);
