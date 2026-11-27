// The grid guide on the stick (docs/decisions/vega.md, playlist.md › Grid guide): channels down the side, two hours
// across, a line at the current time, and the focused programme's details above. Each programme is its own focusable
// box, so the stick's own focus moves between them; one beyond either edge of the window is laid out out of sight, so
// left and right step on to it and the window follows (`reveal`). OK on what's on now plays the channel; on a past
// programme of a channel that keeps recordings it plays the recording (catch-up); on one still to come it sets or clears
// a reminder. Rows are a windowed list, and an Xtream server's guide is read for the rows on screen only.
import { clampStart, earliest, HOUR, latest, overlapping, replayable, reveal, slotStart, ticks } from "@tuned/shared/guide";
import * as React from "react";
import { FlatList, Image, Text, View, type ViewToken } from "react-native";
import { GUIDE_DAYS, type AppModel, type State } from "../app/model";
import type { Channel, GuideProgramme } from "../core/models";
import { detailLine } from "../core/progress";
import { Focusable, Spinner } from "./parts";
import { locale, t } from "./strings";
import { colours, useDp } from "./theme";

const CHANNEL_W = 210;
const ROW = 60;
const HEAD = 34;
/** Two hours across, as Android's TV guide shows. */
const VISIBLE = 2 * HOUR;

const clock = (ms: number) => {
    try {
        return new Date(ms).toLocaleTimeString(locale(), { hour: "numeric", minute: "2-digit" });
    } catch {
        return new Date(ms).toTimeString().slice(0, 5);
    }
};

/** "Today", or the day in the stick's language ("Sat 29"). */
function dayName(ms: number, now: number): string {
    if (new Date(ms).toDateString() === new Date(now).toDateString()) return t.today;
    try {
        return new Date(ms).toLocaleDateString(locale(), { weekday: "short", day: "numeric" });
    } catch {
        return new Date(ms).toDateString().slice(0, 10);
    }
}

interface Focus {
    channel: Channel;
    programme: GuideProgramme | null;
}

export function GuideGrid({
    model,
    state,
    channels,
    showFocused,
    now: fixedNow,
}: {
    model: AppModel;
    state: State;
    channels: Channel[];
    /** For the preview: a programme shown focused, by channel id and start. */
    showFocused?: { channelId: string; start: number };
    /** For the preview: a fixed time. */
    now?: number;
}) {
    const dp = useDp();
    const [clockNow, setNow] = React.useState(fixedNow ?? Date.now());
    const now = fixedNow ?? clockNow;
    const [viewStart, setViewStart] = React.useState(slotStart(now));
    const [width, setWidth] = React.useState(0);
    const [focus, setFocus] = React.useState<Focus | null>(null);
    const [message, setMessage] = React.useState<string | null>(null);

    React.useEffect(() => {
        if (fixedNow) return;
        const timer = setInterval(() => setNow(Date.now()), 30_000);
        return () => clearInterval(timer);
    }, [fixedNow]);

    const longest = React.useMemo(() => channels.reduce((m, c) => Math.max(m, c.catchUp?.days ?? 0), 0), [channels]);
    const first = earliest(now, longest);
    const last = latest(now, GUIDE_DAYS);
    const timeline = Math.max(1, width - dp(CHANNEL_W));
    const perMs = timeline / VISIBLE;
    const viewEnd = viewStart + VISIBLE;
    const x = (ms: number) => (ms - viewStart) * perMs;

    // The rows on screen, and a few either side, get their guide.
    const onViewable = React.useRef(({ viewableItems }: { viewableItems: ViewToken[] }) => {
        const indexes = viewableItems.map((v) => v.index ?? 0);
        if (!indexes.length) return;
        const from = Math.max(0, Math.min(...indexes) - 4);
        const to = Math.max(...indexes) + 5;
        void model.loadGridRows(channelsRef.current.slice(from, to));
    }).current;
    const channelsRef = React.useRef(channels);
    channelsRef.current = channels;
    React.useEffect(() => {
        void model.loadGridRows(channels.slice(0, 12));
    }, [model, channels]);

    const focused = (channel: Channel, p: GuideProgramme | null) => {
        setFocus({ channel, programme: p });
        setMessage(null);
        if (p) setViewStart((v) => clampStart(reveal(p.start, p.stop, v, VISIBLE), first, last, VISIBLE));
    };

    const pick = async (channel: Channel, p: GuideProgramme | null) => {
        const at = Date.now();
        if (!p || (p.start <= at && p.stop > at)) return void model.play(channel);
        if (p.start > at) return void model.toggleReminder(channel, p);
        if (channel.catchUp && replayable(p.start, at, channel.catchUp.days)) {
            const ok = await model.playReplay(channel, p).catch(() => false);
            if (!ok) setMessage(t.replayFailed);
        }
    };

    const shownFocus =
        focus ??
        (showFocused
            ? (() => {
                  const channel = channels.find((c) => c.id === showFocused.channelId);
                  const programme = channel ? (state.grid[channel.id] ?? []).find((p) => p.start === showFocused.start) : null;
                  return channel ? { channel, programme: programme ?? null } : null;
              })()
            : null);

    const load = state.gridLoad;
    return (
        <View style={{ flex: 1 }} onLayout={(e) => setWidth(e.nativeEvent.layout.width)}>
            <Details state={state} focus={shownFocus} now={now} message={message} />
            {load.busy ? (
                <Spinner line={[t.gettingGuide, load.progress ? detailLine(load.progress) : null].filter(Boolean).join(" · ")} />
            ) : load.result && !load.result.ok ? (
                <Text style={{ color: colours.danger, fontSize: dp(13) }}>{load.result.text}</Text>
            ) : null}
            <View style={{ flexDirection: "row", height: dp(HEAD), alignItems: "center" }}>
                <Text style={{ width: dp(CHANNEL_W), color: colours.muted, fontSize: dp(13), fontWeight: "600" }}>
                    {now >= viewStart && now < viewEnd ? t.today : dayName(viewStart, now)}
                </Text>
                <View style={{ flex: 1, height: dp(HEAD), overflow: "hidden" }}>
                    {ticks(viewStart, viewEnd - 1).map((tick) =>
                        tick >= viewStart ? (
                            <Text
                                key={tick}
                                style={{ position: "absolute", left: x(tick), top: dp(8), color: colours.muted, fontSize: dp(12) }}
                            >
                                {clock(tick)}
                            </Text>
                        ) : null,
                    )}
                </View>
            </View>
            <FlatList
                data={channels}
                keyExtractor={(c) => c.id}
                getItemLayout={(_, index) => ({ length: dp(ROW), offset: dp(ROW) * index, index })}
                initialNumToRender={8}
                maxToRenderPerBatch={8}
                windowSize={5}
                onViewableItemsChanged={onViewable}
                viewabilityConfig={{ itemVisiblePercentThreshold: 10 }}
                renderItem={({ item, index }) => (
                    <GridRow
                        channel={item}
                        programmes={state.grid[item.id]}
                        reminders={state.reminders}
                        viewStart={viewStart}
                        viewEnd={viewEnd}
                        x={x}
                        timeline={timeline}
                        now={now}
                        start={index === 0}
                        showFocused={showFocused?.channelId === item.id ? showFocused.start : undefined}
                        onFocus={(p) => focused(item, p)}
                        onPress={(p) => void pick(item, p)}
                    />
                )}
            />
        </View>
    );
}

function GridRow({
    channel,
    programmes,
    reminders,
    viewStart,
    viewEnd,
    x,
    timeline,
    now,
    start,
    showFocused,
    onFocus,
    onPress,
}: {
    channel: Channel;
    programmes: GuideProgramme[] | undefined;
    reminders: State["reminders"];
    viewStart: number;
    viewEnd: number;
    x: (ms: number) => number;
    timeline: number;
    now: number;
    start: boolean;
    showFocused?: number;
    onFocus: (p: GuideProgramme | null) => void;
    onPress: (p: GuideProgramme | null) => void;
}) {
    const dp = useDp();
    // One programme beyond each edge is laid out out of sight, so left and right can step on to it.
    const list = programmes ?? [];
    const inView = overlapping(list, viewStart, viewEnd);
    const before = list.filter((p) => p.stop <= viewStart).slice(-1);
    const after = list.filter((p) => p.start >= viewEnd).slice(0, 1);
    const cells = [...before, ...inView, ...after];
    const nowPick = inView.find((p) => p.start <= now && p.stop > now) ?? inView[0];
    return (
        <View style={{ flexDirection: "row", height: dp(ROW), alignItems: "center" }}>
            <View style={{ width: dp(CHANNEL_W), flexDirection: "row", alignItems: "center", gap: dp(10), paddingRight: dp(10) }}>
                {channel.logo ? (
                    <Image source={{ uri: channel.logo }} resizeMode="contain" style={{ width: dp(40), height: dp(30) }} />
                ) : null}
                <Text numberOfLines={1} style={{ flex: 1, color: colours.text, fontSize: dp(14) }}>
                    {channel.name}
                </Text>
            </View>
            <View style={{ width: timeline, height: dp(ROW - 6), overflow: "hidden" }}>
                {programmes === undefined ? (
                    <View style={{ position: "absolute", left: 0, top: 0, bottom: 0, justifyContent: "center" }}>
                        <Text style={{ color: colours.muted, fontSize: dp(12) }}>…</Text>
                    </View>
                ) : !list.length ? (
                    <Cell
                        left={0}
                        width={timeline - dp(4)}
                        title={t.noGuide}
                        muted
                        start={start}
                        onFocus={() => onFocus(null)}
                        onPress={() => onPress(null)}
                    />
                ) : (
                    cells.map((p) => {
                        // In the window: cut at its edges, so a title never starts out of sight. Beyond it: a sliver
                        // out of sight on that side, there only for the remote to step on to.
                        const shown = p.stop > viewStart && p.start < viewEnd;
                        const left = shown ? x(Math.max(p.start, viewStart)) : p.stop <= viewStart ? -dp(40) : timeline + dp(8);
                        const right = shown ? x(Math.min(p.stop, viewEnd)) : left + dp(30);
                        const past = p.stop <= now;
                        const replay = past && !!channel.catchUp && replayable(p.start, now, channel.catchUp.days);
                        const reminded = reminders.some((r) => r.channelId === channel.id && r.start === p.start);
                        return (
                            <Cell
                                key={p.start}
                                left={left}
                                width={Math.max(dp(8), right - left - dp(4))}
                                title={p.title}
                                past={past && !replay}
                                live={p.start <= now && p.stop > now}
                                mark={replay ? "↺" : reminded ? "⏰" : undefined}
                                markLabel={replay ? t.catchUp : reminded ? t.reminderSetHint : undefined}
                                start={start && p === nowPick}
                                showFocused={showFocused === p.start}
                                onFocus={() => onFocus(p)}
                                onPress={() => onPress(p)}
                            />
                        );
                    })
                )}
                {now >= viewStart && now < viewEnd ? (
                    <View
                        pointerEvents="none"
                        style={{ position: "absolute", left: x(now), top: 0, bottom: 0, width: dp(2), backgroundColor: colours.primary }}
                    />
                ) : null}
            </View>
        </View>
    );
}

function Cell({
    left,
    width,
    title,
    past,
    live,
    muted,
    mark,
    markLabel,
    start,
    showFocused,
    onFocus,
    onPress,
}: {
    left: number;
    width: number;
    title: string;
    past?: boolean;
    live?: boolean;
    muted?: boolean;
    mark?: string;
    markLabel?: string;
    start?: boolean;
    showFocused?: boolean;
    onFocus: () => void;
    onPress: () => void;
}) {
    const dp = useDp();
    return (
        <Focusable
            start={start}
            showFocused={showFocused}
            onFocus={onFocus}
            onPress={onPress}
            accessibilityRole="button"
            accessibilityLabel={markLabel ? `${title}, ${markLabel}` : title}
            style={{
                position: "absolute",
                left,
                top: 0,
                bottom: 0,
                width,
                borderRadius: dp(8),
                paddingHorizontal: dp(8),
                justifyContent: "center",
                backgroundColor: live ? colours.card2 : colours.card,
            }}
            focusedStyle={{ backgroundColor: colours.primarySoft }}
        >
            {(on) => (
                <Text
                    numberOfLines={1}
                    style={{
                        color: on ? colours.onPrimarySoft : past || muted ? colours.muted : colours.text,
                        fontSize: dp(13),
                        fontWeight: live ? "600" : "400",
                    }}
                >
                    {mark ? `${mark} ` : ""}
                    {title}
                </Text>
            )}
        </Focusable>
    );
}

/** The focused programme above the grid: its channel and times, title, description, and what OK does with it. */
function Details({ state, focus, now, message }: { state: State; focus: Focus | null; now: number; message: string | null }) {
    const dp = useDp();
    const box = { height: dp(112), gap: dp(4), marginBottom: dp(6) } as const;
    if (!focus) return <View style={box} />;
    const { channel, programme: p } = focus;
    let action: string;
    if (!p || (p.start <= now && p.stop > now)) action = t.watchLiveHint;
    else if (p.start > now)
        action = state.reminders.some((r) => r.channelId === channel.id && r.start === p.start) ? t.reminderSetHint : t.remindHint;
    else if (channel.catchUp && replayable(p.start, now, channel.catchUp.days)) action = t.replayHint;
    else action = t.aired;
    return (
        <View style={box} accessibilityLiveRegion="polite">
            <Text numberOfLines={1} style={{ color: colours.muted, fontSize: dp(13) }}>
                {p ? `${channel.name} · ${dayName(p.start, now)} ${clock(p.start)} – ${clock(p.stop)}` : channel.name}
            </Text>
            <Text numberOfLines={1} style={{ color: colours.text, fontSize: dp(20), fontWeight: "600" }}>
                {p?.title ?? channel.name}
            </Text>
            {p?.desc ? (
                <Text numberOfLines={2} style={{ color: colours.muted, fontSize: dp(13), lineHeight: dp(18) }}>
                    {p.desc}
                </Text>
            ) : null}
            <Text style={{ color: message ? colours.danger : colours.teal, fontSize: dp(13) }}>{message ?? action}</Text>
        </View>
    );
}
