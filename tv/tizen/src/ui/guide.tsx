// The grid guide (playlist.md › Grid guide): channels down the side, time across the top, a line at the current time
// and the day in the corner. The remote moves programme by programme: up and down keep the time, left and right step
// through a channel's programmes and scroll time just enough to show them. OK on what's on now plays the channel; on a
// past programme of a channel that keeps recordings it plays the recording from the start (catch-up), through the same
// one connection. One focusable box holds the whole grid and draws only the rows and hours on screen, so a group of
// 2,000 channels moves like one of 20.
import { useEffect, useMemo, useRef, useState } from "preact/hooks";
import type { Item, Programme } from "@tuned/shared";
import {
    HOUR,
    SLOT,
    at as programmeAt,
    clampStart,
    earliest,
    latest,
    overlapping,
    replayable,
    reveal,
    slotStart,
    ticks,
} from "@tuned/shared/guide";
import { GUIDE_DAYS, guideByChannel, guideVersion } from "../app.ts";
import { log } from "../log.ts";
import { play, playReplay } from "../player/controller.ts";
import { useStore } from "../state.ts";
import { language, t, timeOfDay } from "../strings.ts";
import { px } from "./lists.tsx";
import { Logo } from "./live.tsx";
import { useKeys } from "./nav.ts";

const CHANNEL_WIDTH = 300;
const ROW = 76;
const HEADER = 48;
const INFO = 168;
/** Two hours across, as Android's TV guide shows. */
const VISIBLE = 2 * HOUR;

/** A row's stand-in when the guide has nothing for it then: the half hour the remote is on. */
interface Slot {
    readonly start: number;
    readonly stop: number;
    readonly title: string | null;
    readonly desc: string | null;
}

export function GuideGrid(props: { channels: readonly Item[]; width: number; height: number; resetKey: string; numbers: boolean }) {
    const box = useRef<HTMLDivElement>(null);
    const version = useStore(guideVersion);
    const [guide, setGuide] = useState<Map<string, Programme[]>>(new Map());
    const [now, setNow] = useState(Date.now());
    const [row, setRow] = useState(0);
    const [time, setTime] = useState(now);
    const [viewStart, setViewStart] = useState(slotStart(now));
    const [message, setMessage] = useState<string | null>(null);

    useEffect(() => {
        let live = true;
        void guideByChannel().then((g) => live && setGuide(g));
        return () => {
            live = false;
        };
    }, [version]);
    // The line at the current time moves on, and what's "now" with it.
    useEffect(() => {
        const timer = setInterval(() => setNow(Date.now()), 30_000);
        return () => clearInterval(timer);
    }, []);
    useEffect(() => {
        setRow(0);
        setTime(Date.now());
        setViewStart(slotStart(Date.now()));
    }, [props.resetKey]);
    // Opening Live TV with nothing focused yet: the remote lands on the grid, as it would on the list's first row.
    useEffect(() => {
        const active = document.activeElement;
        if (box.current && props.channels.length && (!active || active === document.body)) box.current.focus({ preventScroll: true });
    }, [props.channels.length > 0]);

    const timeline = props.width - CHANNEL_WIDTH;
    const perMs = timeline / VISIBLE;
    const rows = Math.max(1, Math.floor((props.height - INFO - HEADER) / ROW));
    const top = Math.min(Math.max(0, row - Math.floor(rows / 2)), Math.max(0, props.channels.length - rows));
    const longest = useMemo(() => props.channels.reduce((m, c) => Math.max(m, c.catchUpDays), 0), [props.channels]);
    const first = earliest(now, longest);
    const last = latest(now, GUIDE_DAYS);

    const listOf = (item: Item | undefined): Programme[] => (item?.tvgId ? (guide.get(item.tvgId) ?? []) : []);
    /** What the remote is on in [r] at [when]: the programme then, or the half hour itself when the guide has none. */
    const cellAt = (r: number, when: number): Programme | Slot => {
        const found = programmeAt(listOf(props.channels[r]), when);
        if (found && found.start <= when) return found;
        const start = slotStart(when);
        const stop = found ? Math.min(found.start, start + SLOT) : start + SLOT;
        return { start, stop, title: null, desc: null };
    };
    const channel = props.channels[row];
    const cell = channel ? cellAt(row, time) : null;

    const moveTo = (r: number, when: number) => {
        const target = cellAt(r, when);
        setRow(r);
        setTime(when);
        setMessage(null);
        setViewStart((v) => clampStart(reveal(target.start, target.stop, v, VISIBLE), first, last, VISIBLE));
    };

    const pick = async () => {
        if (!channel || !cell) return;
        const at = Date.now();
        if (cell.start <= at && cell.stop > at) return void play(channel, props.channels);
        if (cell.start > at) return;
        if ("title" in cell && cell.title !== null && replayable(cell.start, at, channel.catchUpDays)) {
            const ok = await playReplay(channel, cell as Programme).catch((error: unknown) => {
                log("Guide", String((error as Error)?.message ?? error));
                return false;
            });
            if (!ok) setMessage(t.replayFailed);
        }
    };

    useKeys((press) => {
        if (!box.current || document.activeElement !== box.current || !props.channels.length) return false;
        switch (press.action) {
            case "up":
                if (row === 0) return false;
                moveTo(row - 1, Math.max(time, cellAt(row, time).start));
                return true;
            case "down":
                if (row < props.channels.length - 1) moveTo(row + 1, Math.max(time, cellAt(row, time).start));
                return true;
            case "channelUp":
                moveTo(Math.max(0, row - rows), time);
                return true;
            case "channelDown":
                moveTo(Math.min(props.channels.length - 1, row + rows), time);
                return true;
            case "right": {
                const next = cellAt(row, time).stop;
                if (next < last) moveTo(row, next);
                return true;
            }
            case "left": {
                const start = cellAt(row, time).start;
                if (start <= first) return false;
                moveTo(row, Math.max(first, start - 1));
                return true;
            }
            case "ok":
                void pick();
                return true;
            default:
                return false;
        }
    });

    const viewEnd = viewStart + VISIBLE;
    const x = (ms: number) => (Math.min(Math.max(ms, viewStart), viewEnd) - viewStart) * perMs;
    const shown = props.channels.slice(top, top + rows + 1);
    const nowInView = now >= viewStart && now < viewEnd;

    return (
        <div class="guide" style={{ width: px(props.width), height: px(props.height) }}>
            <Details channel={channel ?? null} cell={cell} now={now} message={message} />
            <div
                class="grid"
                ref={box}
                data-focus
                data-start=""
                tabIndex={-1}
                role="grid"
                aria-label={t.live}
                aria-rowcount={props.channels.length}
                style={{ height: px(HEADER + rows * ROW) }}
            >
                <div class="grid-head" style={{ height: px(HEADER) }}>
                    <div class="grid-day" style={{ width: px(CHANNEL_WIDTH) }}>
                        {nowInView ? t.today : dayName(viewStart, now)}
                    </div>
                    {ticks(viewStart, viewEnd - 1).map((tick) =>
                        tick >= viewStart ? (
                            <span key={tick} class="tick" style={{ left: px(CHANNEL_WIDTH + x(tick)) }}>
                                {timeOfDay(tick)}
                            </span>
                        ) : null,
                    )}
                </div>
                {shown.map((item, i) => {
                    const r = top + i;
                    const programmes = overlapping(listOf(item), viewStart, viewEnd);
                    return (
                        <div key={item.id} class="grid-row" role="row" style={{ top: px(HEADER + i * ROW), height: px(ROW) }}>
                            <div class={r === row ? "grid-channel on" : "grid-channel"} style={{ width: px(CHANNEL_WIDTH) }}>
                                {props.numbers ? <span class="number">{item.number}</span> : null}
                                <Logo item={item} size={44} />
                                <span class="name">{item.name}</span>
                            </div>
                            {programmes.map((p) => {
                                const focused = r === row && cell !== null && p.start === cell.start;
                                const past = p.stop <= now;
                                const replay = past && replayable(p.start, now, item.catchUpDays);
                                return (
                                    <div
                                        key={p.start}
                                        role="gridcell"
                                        aria-selected={focused}
                                        class={`prog${focused ? " on" : ""}${past ? " past" : ""}${p.start <= now && p.stop > now ? " now" : ""}`}
                                        style={{ left: px(CHANNEL_WIDTH + x(p.start)), width: px(Math.max(8, x(p.stop) - x(p.start) - 6)) }}
                                    >
                                        {replay ? (
                                            <span class="replay" aria-label={t.catchUp}>
                                                ↺
                                            </span>
                                        ) : null}
                                        {p.title}
                                    </div>
                                );
                            })}
                            {!listOf(item).length ? (
                                // A channel with no guide at all: one box across, which the remote lands on.
                                <div
                                    class={r === row ? "prog empty on" : "prog empty"}
                                    style={{ left: px(CHANNEL_WIDTH), width: px(timeline - 6) }}
                                >
                                    {t.noGuide}
                                </div>
                            ) : r === row && cell !== null && cell.title === null ? (
                                // A gap in a channel's guide: the half hour the remote is on.
                                <div
                                    class="prog empty on"
                                    role="gridcell"
                                    aria-selected
                                    style={{
                                        left: px(CHANNEL_WIDTH + x(cell.start)),
                                        width: px(Math.max(8, x(cell.stop) - x(cell.start) - 6)),
                                    }}
                                />
                            ) : null}
                        </div>
                    );
                })}
                {nowInView ? (
                    <div class="now-line" style={{ left: px(CHANNEL_WIDTH + x(now)), top: px(HEADER), height: px(shown.length * ROW) }} />
                ) : null}
            </div>
            <p class="hint">{t.gridHint}</p>
        </div>
    );
}

/** The focused programme above the grid: its time, title and description, and what OK does with it. */
function Details(props: { channel: Item | null; cell: Programme | Slot | null; now: number; message: string | null }) {
    const { channel, cell, now } = props;
    if (!channel || !cell) return <div class="guide-info" style={{ height: px(INFO) }} />;
    const title = cell.title ?? channel.name;
    let action: string;
    if (cell.start <= now && cell.stop > now) action = t.watchLiveHint;
    else if (cell.start > now) action = t.startsAt(timeOfDay(cell.start));
    else if (cell.title !== null && replayable(cell.start, now, channel.catchUpDays)) action = t.replayHint;
    else action = t.aired;
    return (
        <div class="guide-info" style={{ height: px(INFO) }} aria-live="polite">
            <div class="when">
                {channel.name} · {dayName(cell.start, now)} {timeOfDay(cell.start)} – {timeOfDay(cell.stop)}
            </div>
            <div class="title">{title}</div>
            {cell.desc ? <p class="desc">{cell.desc}</p> : null}
            <div class={props.message ? "action error" : "action"}>{props.message ?? action}</div>
        </div>
    );
}

let days: Intl.DateTimeFormat | null = null;

/** "Today", or the day in the TV's language ("Sat 29"), for the corner and a programme's line. */
function dayName(ms: number, now: number): string {
    if (new Date(ms).toDateString() === new Date(now).toDateString()) return t.today;
    try {
        if (!days) days = new Intl.DateTimeFormat(language, { weekday: "short", day: "numeric" });
        return days.format(new Date(ms));
    } catch {
        return new Date(ms).toDateString();
    }
}
