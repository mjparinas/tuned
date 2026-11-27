// The row of groups across the top of Live TV, Films and Shows. Landing on a group picks it after a short pause, so
// running the remote along the row doesn't redraw the list at every step. The pick stays until changed, per section,
// even after the app closes (playlist.md › filters stay chosen).
import { useEffect, useRef, useState } from "preact/hooks";
import { kv } from "../db.ts";
import type { Group } from "../app.ts";
import { ALL, FAVOURITES } from "../app.ts";
import { t } from "../strings.ts";

const nameOf = (g: Group) => (g.id === ALL ? t.all : g.id === FAVOURITES ? t.favourites : g.name);

export function Groups(props: { groups: Group[]; picked: string; onPick: (id: string) => void }) {
    const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
    useEffect(() => () => clearTimeout(timer.current), []);
    return (
        <div class="groups" data-row role="tablist">
            {props.groups.map((g) => (
                <button
                    key={g.id}
                    type="button"
                    role="tab"
                    aria-selected={g.id === props.picked}
                    class={g.id === props.picked ? "chip picked" : "chip"}
                    data-focus
                    onFocus={() => {
                        clearTimeout(timer.current);
                        timer.current = setTimeout(() => props.onPick(g.id), 300);
                    }}
                    onClick={() => props.onPick(g.id)}
                >
                    {nameOf(g)} <span class="count">{g.count}</span>
                </button>
            ))}
        </div>
    );
}

/** The group chosen for a section, kept on the TV. */
export function usePickedGroup(section: string, playlistId: string | null): [string, (id: string) => void] {
    const key = `group.${playlistId ?? ""}.${section}`;
    const [picked, setPicked] = useState(ALL);
    useEffect(() => {
        let live = true;
        void kv
            .get(key)
            .then((v) => live && setPicked(v ?? ALL))
            .catch(() => undefined);
        return () => {
            live = false;
        };
    }, [key]);
    return [
        picked,
        (id: string) => {
            setPicked(id);
            void kv.set(key, id).catch(() => undefined);
        },
    ];
}
