// Live TV: groups across the top, channels down the left and, beside them, what the channel the remote is on shows
// now and next from the guide. OK plays it; channel up and down then walk the same group (player.md).
import { useEffect, useMemo, useState } from "preact/hooks";
import type { Item, Programme } from "@tuned/shared";
import { currentId, favourites, groupsOf, guideVersion, inGroup, nowNext, saveSettings, settings } from "../app.ts";
import { toggleFavourite } from "../my-lists.ts";
import { play } from "../player/controller.ts";
import { useStore } from "../state.ts";
import { t, timeOfDay } from "../strings.ts";
import { AddToList } from "./add-to-list.tsx";
import { GuideGrid } from "./guide.tsx";
import { Groups, usePickedGroup } from "./groups.tsx";
import { useItems } from "./library.ts";
import { px, VirtualList } from "./lists.tsx";
import { useKeys } from "./nav.ts";
import { Spinner } from "./parts.tsx";

export function Logo(props: { item: Item; size: number }) {
    const [failed, setFailed] = useState(false);
    useEffect(() => setFailed(false), [props.item.logo]);
    if (!props.item.logo || failed)
        return (
            <div class="logo none" style={{ width: px(props.size), height: px(props.size) }}>
                {props.item.name.slice(0, 2)}
            </div>
        );
    return (
        <img
            class="logo"
            src={props.item.logo}
            alt=""
            loading="lazy"
            width={props.size}
            height={props.size}
            onError={() => setFailed(true)}
        />
    );
}

/** Live TV's width beside Home's rail, inside the overscan margin. */
const WIDTH = 1920 - 2 * 64 - 300 - 40;

export function LiveTv(props: { height: number }) {
    const { items, loading } = useItems("live");
    const listId = useStore(currentId);
    const { channelNumbers: numbers, guideGrid } = useStore(settings);
    const favs = useStore(favourites);
    const [picked, pick] = usePickedGroup("live", listId);
    const groups = useMemo(() => groupsOf(items), [items, favs]);
    const shown = useMemo(
        () => inGroup(items, groups.some((g) => g.id === picked) ? picked : (groups[0]?.id ?? "")),
        [items, picked, groups],
    );
    const [focused, setFocused] = useState<Item | null>(null);
    const [adding, setAdding] = useState<Item | null>(null);

    // Yellow on a channel: add to or take from Favourites, as the Android TV app's long press does; blue: Add to list.
    useKeys((press) => {
        // Green switches between the list and the grid guide, and Live TV stays that way (Settings has the same switch).
        if (press.action === "green") {
            void saveSettings({ guideGrid: !guideGrid });
            return true;
        }
        if (!focused || guideGrid || adding) return false;
        if (press.action === "blue") {
            setAdding(focused);
            return true;
        }
        if (press.action !== "yellow") return false;
        void toggleFavourite(focused);
        return true;
    });

    if (loading) return <Spinner />;
    if (!items.length) return <p class="empty">{t.nothingHere}</p>;
    const listHeight = props.height - 96;
    if (guideGrid)
        return (
            <div class="live">
                <Groups groups={groups} picked={picked} onPick={pick} />
                <GuideGrid channels={shown} width={WIDTH} height={listHeight} resetKey={picked} numbers={numbers} />
            </div>
        );
    return (
        <div class="live">
            <Groups groups={groups} picked={picked} onPick={pick} />
            <div class="live-body">
                <VirtualList
                    items={shown}
                    rowHeight={76}
                    height={listHeight}
                    resetKey={picked}
                    start
                    label={t.live}
                    onFocusItem={(item) => setFocused(item)}
                    onPick={(item) => void play(item, shown)}
                    row={(item) => (
                        <div class="channel">
                            {numbers ? <span class="number">{item.number}</span> : null}
                            <Logo item={item} size={52} />
                            <span class="name">{item.name}</span>
                            {favs.has(item.id) ? (
                                <span class="star" aria-label={t.favourite}>
                                    ★
                                </span>
                            ) : null}
                        </div>
                    )}
                />
                <ChannelInfo item={focused ?? shown[0] ?? null} />
            </div>
            {adding ? <AddToList item={adding} onClose={() => setAdding(null)} /> : null}
        </div>
    );
}

function ChannelInfo(props: { item: Item | null }) {
    const version = useStore(guideVersion);
    const [on, setOn] = useState<{ now: Programme | null; next: Programme | null }>({ now: null, next: null });
    useEffect(() => {
        let live = true;
        setOn({ now: null, next: null });
        if (props.item) void nowNext(props.item).then((x) => live && setOn(x));
        return () => {
            live = false;
        };
    }, [props.item?.id, version]);
    if (!props.item) return <div class="info" />;
    return (
        <aside class="info" aria-live="polite">
            <Logo item={props.item} size={120} />
            <h2>{props.item.name}</h2>
            {on.now || on.next ? (
                <>
                    {on.now ? <ProgrammeLine label={t.nowOn} p={on.now} showProgress /> : null}
                    {on.next ? <ProgrammeLine label={t.nextOn} p={on.next} /> : null}
                </>
            ) : (
                <p class="muted">{t.noGuide}</p>
            )}
            <p class="hint">{t.liveKeysHint}</p>
            <p class="hint">{t.gridHint}</p>
        </aside>
    );
}

export function ProgrammeLine(props: { label: string; p: Programme; showProgress?: boolean }) {
    const share = Math.min(1, Math.max(0, (Date.now() - props.p.start) / (props.p.stop - props.p.start)));
    return (
        <div class="programme">
            <div class="when">
                {props.label} · {timeOfDay(props.p.start)} – {timeOfDay(props.p.stop)}
            </div>
            <div class="title">{props.p.title}</div>
            {props.showProgress ? (
                <div class="bar thin">
                    <div style={{ width: `${share * 100}%` }} />
                </div>
            ) : null}
            {props.p.desc ? <p class="desc">{props.p.desc}</p> : null}
        </div>
    );
}
