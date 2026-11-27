// Movies and Shows: groups across the top and a grid of posters. OK opens the movie's or show's page.
import { useMemo, useState } from "preact/hooks";
import { core, type Item, type Kind } from "@tuned/shared";
import { currentId, favourites, groupsOf, inGroup, shows } from "../app.ts";
import { useStore } from "../state.ts";
import { t } from "../strings.ts";
import { Groups, usePickedGroup } from "./groups.tsx";
import { useItems } from "./library.ts";
import { VirtualGrid } from "./lists.tsx";
import { open } from "./nav.ts";
import { Spinner } from "./parts.tsx";

export function Poster(props: { item: Item }) {
    const [failed, setFailed] = useState(false);
    const full = props.item.kind === "series" ? (props.item.showName ?? props.item.name) : props.item.name;
    // "The Long Field (2021)" shows as its name, with the year beside it.
    const found = props.item.year ?? core.yearInName(full);
    const name = full.replace(/\s*[([]((19|20)\d{2})[)\]]\s*$/, "") || full;
    const year = found ? ` · ${found}` : "";
    return (
        <div class="poster">
            {props.item.logo && !failed ? (
                <img src={props.item.logo} alt="" loading="lazy" onError={() => setFailed(true)} />
            ) : (
                <div class="poster-none">{name}</div>
            )}
            <div class="poster-name">
                {name}
                <span class="muted">{year}</span>
            </div>
        </div>
    );
}

export function Posters(props: { kind: Exclude<Kind, "live">; height: number }) {
    const { items, loading } = useItems(props.kind);
    const listId = useStore(currentId);
    const favs = useStore(favourites);
    const all = useMemo(() => (props.kind === "series" ? shows(items) : items), [items, props.kind]);
    const [picked, pick] = usePickedGroup(props.kind, listId);
    const groups = useMemo(() => groupsOf(all), [all, favs]);
    const shown = useMemo(() => inGroup(all, groups.some((g) => g.id === picked) ? picked : (groups[0]?.id ?? "")), [all, picked, groups]);
    if (loading) return <Spinner />;
    if (!all.length) return <p class="empty">{t.nothingHere}</p>;
    return (
        <div class="posters">
            <Groups groups={groups} picked={picked} onPick={pick} />
            <VirtualGrid
                items={shown}
                columns={6}
                cellHeight={400}
                height={props.height - 96}
                resetKey={picked}
                start
                label={props.kind === "movie" ? t.movies : t.shows}
                cell={(item) => <Poster item={item} />}
                onPick={(item) => open(props.kind === "movie" ? { name: "movie", item } : { name: "show", item })}
            />
        </div>
    );
}
