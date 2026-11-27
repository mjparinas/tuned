// Live events (docs/decisions/events.md): today's games, fights and pay-per-views from the sports channels' guides and
// their names, one row per event with how many channels show it, under On now, Later today and Tomorrow, the ones
// followed on the phone or the website first. OK on one that's on (or starts within the hour, or gives no time)
// auto-tunes it; on a later one it sets or clears a reminder, which auto-tunes it when it starts. Reading the guides
// shows how many channels are read so far.
import * as React from "react";
import { FlatList, Text, View } from "react-native";
import { follows, liveAt, type LiveEvent } from "@tuned/shared/events";
import type { AppModel, State } from "../app/model";
import { Button, Focusable, ProgressBar, Screen, Spinner } from "./parts";
import { locale, t } from "./strings";
import { TopBar } from "./TopBar";
import { colours, useDp } from "./theme";

const HOUR = 3600_000;

type Entry = { kind: "head"; key: string; text: string } | { kind: "event"; key: string; event: LiveEvent };

const sameDay = (a: number, b: number) => new Date(a).toDateString() === new Date(b).toDateString();

function clockOf(ms: number, now: number): string {
    try {
        const time = new Date(ms).toLocaleTimeString(locale(), { hour: "numeric", minute: "2-digit" });
        if (sameDay(ms, now)) return time;
        return `${new Date(ms).toLocaleDateString(locale(), { weekday: "short" })} ${time}`;
    } catch {
        return new Date(ms).toTimeString().slice(0, 5);
    }
}

function dayName(ms: number): string {
    try {
        return new Date(ms).toLocaleDateString(locale(), { weekday: "long" });
    } catch {
        return new Date(ms).toDateString().slice(0, 3);
    }
}

/** On now, Later today, Tomorrow and any day after, followed events first in each. */
export function sections(events: readonly LiveEvent[], followed: readonly string[], now: number): Entry[] {
    const tomorrow = new Date(now);
    tomorrow.setHours(24, 0, 0, 0);
    const groups = new Map<string, LiveEvent[]>();
    const heads: string[] = [];
    for (const e of events) {
        const head = liveAt(e, now)
            ? t.onNow
            : !e.timeKnown || e.start < tomorrow.getTime()
              ? t.laterToday
              : sameDay(e.start, tomorrow.getTime())
                ? t.tomorrow
                : dayName(e.start);
        if (!groups.has(head)) {
            groups.set(head, []);
            heads.push(head);
        }
        groups.get(head)!.push(e);
    }
    const order = [t.onNow, t.laterToday, t.tomorrow];
    heads.sort((a, b) => (order.indexOf(a) + 1 || 9) - (order.indexOf(b) + 1 || 9));
    const out: Entry[] = [];
    for (const head of heads) {
        out.push({ kind: "head", key: `h:${head}`, text: head });
        const list = groups.get(head)!;
        const mine = followed.length ? list.filter((e) => follows(e, followed)) : [];
        for (const e of [...mine, ...list.filter((x) => !mine.includes(x))]) {
            out.push({ kind: "event", key: `${e.key}:${e.start}:${e.channels[0]}`, event: e });
        }
    }
    return out;
}

/** Tune shows when the event is on or starts within the hour, or its name gives no time; before that, a bell. */
const tunable = (e: LiveEvent, now: number) => !e.timeKnown || e.start - now <= HOUR;

function EventRow({
    event,
    now,
    followed,
    reminder,
    start,
    showFocused,
    onPress,
}: {
    event: LiveEvent;
    now: number;
    followed: boolean;
    reminder: boolean;
    start?: boolean;
    showFocused?: boolean;
    onPress: () => void;
}) {
    const dp = useDp();
    const live = liveAt(event, now);
    const when = live ? t.live : event.timeKnown ? clockOf(event.start, now) : t.noTime;
    const action = tunable(event, now) ? t.autoTune : reminder ? t.reminderSet : t.remindMe;
    return (
        <Focusable
            start={start}
            showFocused={showFocused}
            onPress={onPress}
            accessibilityRole="button"
            accessibilityLabel={`${event.title}, ${when}, ${t.onChannels(event.channels.length)}, ${action}`}
            style={{
                minHeight: dp(60),
                marginBottom: dp(6),
                paddingHorizontal: dp(14),
                paddingVertical: dp(8),
                flexDirection: "row",
                alignItems: "center",
                gap: dp(16),
                backgroundColor: colours.card,
            }}
        >
            {() => (
                <>
                    <Text
                        numberOfLines={1}
                        style={{
                            width: dp(120),
                            color: live ? colours.danger : colours.muted,
                            fontSize: dp(14),
                            fontWeight: live ? "700" : "500",
                            fontVariant: ["tabular-nums"],
                        }}
                    >
                        {when}
                    </Text>
                    <View style={{ flex: 1, gap: dp(2) }}>
                        <Text numberOfLines={1} style={{ color: colours.text, fontSize: dp(17), fontWeight: "600" }}>
                            {followed ? `★ ${event.title}` : event.title}
                        </Text>
                        <Text numberOfLines={1} style={{ color: colours.muted, fontSize: dp(13) }}>
                            {t.onChannels(event.channels.length)}
                        </Text>
                    </View>
                    <Text
                        style={{
                            color: tunable(event, now) || reminder ? colours.primary : colours.muted,
                            fontSize: dp(14),
                            fontWeight: "600",
                        }}
                    >
                        {tunable(event, now) ? action : `🔔 ${action}`}
                    </Text>
                </>
            )}
        </Focusable>
    );
}

export function EventsScreen({
    model,
    state,
    fixedNow,
    showFocused,
}: {
    model: AppModel;
    state: State;
    fixedNow?: number;
    showFocused?: number;
}) {
    const dp = useDp();
    const [clock, setClock] = React.useState(Date.now());
    // What's on now moves on; the list is built again each minute from what the stick has.
    React.useEffect(() => {
        if (fixedNow !== undefined) return;
        const timer = setInterval(() => {
            setClock(Date.now());
            model.buildEvents();
        }, 60_000);
        return () => clearInterval(timer);
    }, [model, fixedNow]);
    const now = fixedNow ?? clock;
    const entries = React.useMemo(() => sections(state.events, state.follows, now), [state.events, state.follows, now]);
    const load = state.eventsLoad.busy ? state.eventsLoad : state.gridLoad.busy ? state.gridLoad : state.eventsLoad;
    const firstEvent = entries.findIndex((e) => e.kind === "event");
    // Each event's place among the events, for the preview's focused row.
    const ordinal = React.useMemo(() => {
        let n = -1;
        return entries.map((e) => (e.kind === "event" ? ++n : -1));
    }, [entries]);
    return (
        <Screen>
            <TopBar model={model} state={state} tab="live" />
            <View style={{ flex: 1, marginTop: dp(14), gap: dp(10) }}>
                <View style={{ flexDirection: "row", alignItems: "center", gap: dp(14) }}>
                    <Text style={{ color: colours.text, fontSize: dp(24), fontWeight: "600" }}>{t.liveEvents}</Text>
                    <Text numberOfLines={1} style={{ flex: 1, textAlign: "right", color: colours.muted, fontSize: dp(12) }}>
                        {t.eventsHint}
                    </Text>
                </View>
                <Text numberOfLines={1} style={{ color: colours.muted, fontSize: dp(13), lineHeight: dp(22) }}>
                    {state.follows.length ? t.following(state.follows.join(", ")) : t.followNote}
                </Text>
                {load.busy ? (
                    <View style={{ gap: dp(6) }}>
                        <Spinner line={[load.progress?.label ?? t.gettingGuide, load.progress?.eta].filter(Boolean).join(" · ")} />
                        <ProgressBar share={load.progress?.total ? (load.progress.loaded ?? 0) / load.progress.total : null} />
                    </View>
                ) : load.result && !load.result.ok ? (
                    <View style={{ flexDirection: "row", alignItems: "center", gap: dp(14) }}>
                        <Text style={{ flex: 1, color: colours.danger, fontSize: dp(14) }}>{load.result.text}</Text>
                        <Button label={t.tryAgain} onPress={() => void model.loadEventGuide()} />
                    </View>
                ) : null}
                {entries.length ? (
                    <FlatList
                        data={entries}
                        keyExtractor={(e) => e.key}
                        initialNumToRender={12}
                        windowSize={5}
                        renderItem={({ item, index }) => {
                            if (item.kind === "head") {
                                return (
                                    <Text
                                        style={{
                                            color: colours.muted,
                                            fontSize: dp(13),
                                            fontWeight: "700",
                                            marginTop: dp(index ? 10 : 0),
                                            marginBottom: dp(6),
                                        }}
                                    >
                                        {item.text.toUpperCase()}
                                    </Text>
                                );
                            }
                            const e = item.event;
                            return (
                                <EventRow
                                    event={e}
                                    now={now}
                                    followed={state.follows.length > 0 && follows(e, state.follows)}
                                    reminder={state.reminders.some((r) => r.channelId === e.channels[0] && r.start === e.start)}
                                    start={index === firstEvent}
                                    showFocused={showFocused === ordinal[index]}
                                    onPress={() =>
                                        tunable(e, now) ? void model.tuneEvent(e.title, e.channels) : void model.toggleEventReminder(e)
                                    }
                                />
                            );
                        }}
                    />
                ) : !load.busy ? (
                    <Text style={{ color: colours.muted, fontSize: dp(15) }}>{t.noEvents}</Text>
                ) : null}
            </View>
        </Screen>
    );
}
