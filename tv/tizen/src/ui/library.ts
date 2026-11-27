// A section's items for a screen, read from the device and read again when the lists or settings change.
import { useEffect, useState } from "preact/hooks";
import type { Item, Kind } from "@tuned/shared";
import { kindItems, libraryVersion, settings, visibleItems, favourites } from "../app.ts";
import { useStore } from "../state.ts";

export function useItems(kind: Kind): { items: Item[]; loading: boolean } {
    const version = useStore(libraryVersion);
    const s = useStore(settings);
    useStore(favourites);
    const [state, setState] = useState<{ items: Item[]; loading: boolean }>({ items: [], loading: true });
    useEffect(() => {
        let live = true;
        void kindItems(kind).then((all) => live && setState({ items: visibleItems(all), loading: false }));
        return () => {
            live = false;
        };
    }, [kind, version, s.hideAdult]);
    return state;
}
