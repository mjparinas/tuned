// A movie's page (Play, or Resume where it was left, and Favourite) and a show's (its seasons and episodes, with real
// episode names where the playlist has them).
import { useEffect, useMemo, useState } from "preact/hooks";
import type { Item } from "@tuned/shared";
import { episodesOf, favourites } from "../app.ts";
import { toggleFavourite } from "../my-lists.ts";
import { play, savedPosition } from "../player/controller.ts";
import { useStore } from "../state.ts";
import { clock, t } from "../strings.ts";
import { AddToList } from "./add-to-list.tsx";
import { VirtualList } from "./lists.tsx";
import { Button, Screen, Spinner } from "./parts.tsx";
import { Poster } from "./posters.tsx";

function FavouriteButton(props: { item: Item }) {
    const favs = useStore(favourites);
    const on = favs.has(props.item.id);
    return <Button label={on ? t.unfavourite : t.favourite} onClick={() => void toggleFavourite(props.item)} />;
}

export function FilmPage(props: { item: Item }) {
    const [resumeAt, setResumeAt] = useState(0);
    const [adding, setAdding] = useState(false);
    useEffect(() => {
        void savedPosition(props.item).then(setResumeAt);
    }, [props.item.id]);
    const item = props.item;
    return (
        <Screen class="detail">
            <div class="detail-art">
                <Poster item={item} />
            </div>
            <div class="detail-text">
                <h1>{item.name}</h1>
                <p class="muted">
                    {[item.year, item.rating ? `★ ${item.rating.toFixed(1)}` : null, item.group].filter(Boolean).join(" · ")}
                </p>
                <div class="buttons" data-row>
                    {resumeAt ? (
                        <>
                            <Button
                                kind="primary"
                                start
                                label={t.resume(clock(resumeAt))}
                                onClick={() => void play(item, [item], resumeAt)}
                            />
                            <Button label={t.fromStart} onClick={() => void play(item, [item], 0)} />
                        </>
                    ) : (
                        <Button kind="primary" start label={t.play} onClick={() => void play(item, [item], 0)} />
                    )}
                    <FavouriteButton item={item} />
                    <Button label={t.addToList} onClick={() => setAdding(true)} />
                </div>
            </div>
            {adding ? <AddToList item={item} onClose={() => setAdding(false)} /> : null}
        </Screen>
    );
}

export function ShowPage(props: { item: Item }) {
    const [episodes, setEpisodes] = useState<Item[] | null>(null);
    const [season, setSeason] = useState(0);
    useEffect(() => {
        let live = true;
        void episodesOf(props.item).then((list) => {
            if (!live) return;
            setEpisodes(list);
            setSeason(list[0]?.season ?? 0);
        });
        return () => {
            live = false;
        };
    }, [props.item.id]);
    const seasons = useMemo(() => [...new Set((episodes ?? []).map((e) => e.season))].sort((a, b) => a - b), [episodes]);
    const inSeason = useMemo(() => (episodes ?? []).filter((e) => e.season === season), [episodes, season]);
    const name = props.item.showName ?? props.item.name;
    return (
        <Screen class="detail show">
            <div class="detail-art">
                <Poster item={props.item} />
                <FavouriteButton item={props.item} />
            </div>
            <div class="detail-text">
                <h1>{name}</h1>
                {episodes === null ? (
                    <p class="muted">
                        <Spinner small /> {t.loadingEpisodes}
                    </p>
                ) : !episodes.length ? (
                    <p class="muted">{t.noEpisodes}</p>
                ) : (
                    <>
                        {seasons.length > 1 ? (
                            <div class="groups" data-row>
                                {seasons.map((s) => (
                                    <button
                                        key={s}
                                        type="button"
                                        class={s === season ? "chip picked" : "chip"}
                                        data-focus
                                        onClick={() => setSeason(s)}
                                        onFocus={() => setSeason(s)}
                                    >
                                        {t.season(s)}
                                    </button>
                                ))}
                            </div>
                        ) : null}
                        <VirtualList
                            items={inSeason}
                            rowHeight={84}
                            height={seasons.length > 1 ? 720 : 800}
                            resetKey={String(season)}
                            start
                            onPick={(item) => void savedPosition(item).then((at) => play(item, inSeason, at))}
                            row={(e) => (
                                <div class="episode">
                                    <span class="number">{t.episode(e.season, e.episode)}</span>
                                    <span class="name">{episodeTitle(e, name)}</span>
                                </div>
                            )}
                        />
                    </>
                )}
            </div>
        </Screen>
    );
}

/** The episode's own name when the playlist gives one beyond the show's name and numbers, else "Episode 2". */
function episodeTitle(e: Item, show: string): string {
    const rest = e.name
        .replace(show, "")
        .replace(/\bS\d{1,2}\s*E\d{1,3}\b/i, "")
        .replace(/^[\s\-–:|.]+|[\s\-–:|.]+$/g, "");
    return rest || t.episodeNumber(e.episode);
}
