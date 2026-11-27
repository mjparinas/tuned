// Which screen shows, and who hears the remote. Screens stack (Home, then a film's page, then Settings › App log...);
// Back pops one. A screen that handles a key itself (the player's channel up, a list's paging) registers with
// useKeys, and the newest registration hears each key first.
import { useEffect, useRef } from "preact/hooks";
import type { Item } from "@tuned/shared";
import type { KeyPress } from "../keys.ts";
import { store } from "../state.ts";

export type Tab = "live" | "movie" | "series" | "search" | "settings";

export type Route =
    | { readonly name: "home"; readonly tab: Tab }
    | { readonly name: "film"; readonly item: Item }
    | { readonly name: "show"; readonly item: Item }
    | { readonly name: "signin"; readonly purpose: "playlist" | "account" }
    | { readonly name: "manual" }
    | { readonly name: "log" };

export const routes = store<Route[]>([{ name: "home", tab: "live" }]);

export const top = (): Route => routes.get()[routes.get().length - 1];

export function open(route: Route): void {
    routes.set((r) => [...r, route]);
}

/** Back: one screen down. False at the bottom, where Back leaves the app. */
export function back(): boolean {
    if (routes.get().length <= 1) return false;
    routes.set((r) => r.slice(0, -1));
    return true;
}

export function setTab(tab: Tab): void {
    routes.set((r) => [{ name: "home", tab }, ...r.slice(1)]);
}

export type KeyHandler = (press: KeyPress) => boolean;

const handlers: { current: KeyHandler }[] = [];

/** The newest handler first; true means the key was used. */
export function handleKey(press: KeyPress): boolean {
    for (let i = handlers.length - 1; i >= 0; i--) if (handlers[i].current(press)) return true;
    return false;
}

export function useKeys(handler: KeyHandler, active = true): void {
    const ref = useRef(handler);
    ref.current = handler;
    useEffect(() => {
        if (!active) return;
        const entry = { current: (p: KeyPress) => ref.current(p) };
        handlers.push(entry);
        return () => {
            const at = handlers.indexOf(entry);
            if (at >= 0) handlers.splice(at, 1);
        };
    }, [active]);
}
