// Search across channels, films and shows of the playlist in use. Typing uses the TV's own keyboard.
import { useEffect, useMemo, useState } from "preact/hooks";
import type { Item } from "@tuned/shared";
import { kindItems, libraryVersion, search, shows, visibleItems } from "../app.ts";
import { play } from "../player/controller.ts";
import { useStore } from "../state.ts";
import { t } from "../strings.ts";
import { VirtualList } from "./lists.tsx";
import { Logo } from "./live.tsx";
import { open } from "./nav.ts";

export function Search(props: { height: number }) {
    const version = useStore(libraryVersion);
    const [all, setAll] = useState<Item[]>([]);
    const [query, setQuery] = useState("");
    useEffect(() => {
        let live = true;
        void Promise.all([kindItems("live"), kindItems("movie"), kindItems("series")]).then(([l, m, s]) => {
            if (live) setAll(visibleItems([...l, ...m, ...shows(s)]));
        });
        return () => {
            live = false;
        };
    }, [version]);
    const [typed, setTyped] = useState("");
    useEffect(() => {
        const id = setTimeout(() => setQuery(typed), 250);
        return () => clearTimeout(id);
    }, [typed]);
    const found = useMemo(() => search(all, query).slice(0, 500), [all, query]);
    const kindName = (i: Item) => (i.kind === "live" ? t.live : i.kind === "movie" ? t.movies : t.shows);
    return (
        <div class="search">
            <input
                class="field"
                data-focus
                data-start
                type="search"
                value={typed}
                placeholder={t.searchHint}
                aria-label={t.search}
                onInput={(e) => setTyped((e.currentTarget as HTMLInputElement).value)}
            />
            {query && !found.length ? <p class="empty">{t.noResults}</p> : null}
            <VirtualList
                items={found}
                rowHeight={76}
                height={props.height - 120}
                resetKey={query}
                onPick={(item) =>
                    item.kind === "live"
                        ? void play(
                              item,
                              found.filter((i) => i.kind === "live"),
                          )
                        : open(item.kind === "movie" ? { name: "film", item } : { name: "show", item })
                }
                row={(item) => (
                    <div class="channel">
                        <Logo item={item} size={52} />
                        <span class="name">{item.kind === "series" ? (item.showName ?? item.name) : item.name}</span>
                        <span class="muted">{kindName(item)}</span>
                    </div>
                )}
            />
        </div>
    );
}
