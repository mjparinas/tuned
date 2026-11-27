// Live TV: the tabs along the top, categories down the left, channels beside them, both windowed lists so only the
// rows on screen exist (speed first). The remote starts on the channel last watched, or the first one; OK plays it. A refresh shows its
// step in the corner while the saved channels stay usable.
import * as React from "react";
import { FlatList, Image, Text, View } from "react-native";
import { FAVOURITES, type AppModel, type State } from "../app/model";
import type { Category, Channel } from "../core/models";
import { GuideGrid } from "./GuideGrid";
import { Button, Focusable, Screen, Spinner } from "./parts";
import { TopBar } from "./TopBar";
import { t } from "./strings";
import { colours, useDp } from "./theme";

const ROW = 52;

export function CategoryRow({
    name,
    count,
    selected,
    onPress,
    showFocused,
}: {
    name: string;
    count?: number;
    selected: boolean;
    onPress: () => void;
    showFocused?: boolean;
}) {
    const dp = useDp();
    return (
        <Focusable
            onPress={onPress}
            showFocused={showFocused}
            accessibilityRole="button"
            accessibilityLabel={name}
            accessibilityState={{ selected }}
            style={{
                minHeight: dp(40),
                paddingHorizontal: dp(12),
                justifyContent: "center",
                marginBottom: dp(2),
                backgroundColor: selected ? colours.primarySoft : "transparent",
            }}
        >
            {() => (
                <View style={{ flexDirection: "row", alignItems: "center", gap: dp(8) }}>
                    <Text
                        numberOfLines={1}
                        style={{
                            flex: 1,
                            color: selected ? colours.onPrimarySoft : colours.text,
                            fontSize: dp(14),
                            fontWeight: selected ? "600" : "400",
                        }}
                    >
                        {name}
                    </Text>
                    {count !== undefined ? <Text style={{ color: colours.muted, fontSize: dp(12) }}>{count}</Text> : null}
                </View>
            )}
        </Focusable>
    );
}

function ChannelRow({
    channel,
    now,
    onPress,
    onLongPress,
    favourite,
    start,
    showFocused,
}: {
    channel: Channel;
    now?: string;
    onPress: () => void;
    onLongPress: () => void;
    favourite: boolean;
    start?: boolean;
    showFocused?: boolean;
}) {
    const dp = useDp();
    return (
        <Focusable
            start={start}
            showFocused={showFocused}
            onPress={onPress}
            onLongPress={onLongPress}
            accessibilityRole="button"
            accessibilityLabel={favourite ? `${channel.number} ${channel.name}, ${t.favourites}` : `${channel.number} ${channel.name}`}
            style={{
                height: dp(ROW - 4),
                marginBottom: dp(4),
                paddingHorizontal: dp(12),
                flexDirection: "row",
                alignItems: "center",
                gap: dp(14),
                backgroundColor: colours.card,
            }}
        >
            {() => (
                <>
                    <Text style={{ width: dp(44), color: colours.muted, fontSize: dp(14), fontVariant: ["tabular-nums"] }}>
                        {channel.number}
                    </Text>
                    <View
                        style={{
                            width: dp(56),
                            height: dp(32),
                            borderRadius: dp(6),
                            backgroundColor: colours.card2,
                            alignItems: "center",
                            justifyContent: "center",
                            overflow: "hidden",
                        }}
                    >
                        {channel.logo ? (
                            <Image source={{ uri: channel.logo }} resizeMode="contain" style={{ width: dp(52), height: dp(28) }} />
                        ) : (
                            <Text style={{ color: colours.muted, fontSize: dp(12), fontWeight: "600" }}>
                                {channel.name.slice(0, 3).toUpperCase()}
                            </Text>
                        )}
                    </View>
                    <View style={{ flex: 1 }}>
                        <Text numberOfLines={1} style={{ color: colours.text, fontSize: dp(16), fontWeight: "500" }}>
                            {channel.name}
                        </Text>
                        {now ? (
                            <Text numberOfLines={1} style={{ color: colours.muted, fontSize: dp(12) }}>
                                {now}
                            </Text>
                        ) : null}
                    </View>
                    {favourite ? <Text style={{ color: colours.primary, fontSize: dp(16) }}>★</Text> : null}
                </>
            )}
        </Focusable>
    );
}

export function LiveScreen({
    model,
    state,
    focusChannel,
    focusProgramme,
    fixedNow,
}: {
    model: AppModel;
    state: State;
    focusChannel?: string;
    /** For the preview's guide grid. */
    focusProgramme?: { channelId: string; start: number };
    fixedNow?: number;
}) {
    const dp = useDp();
    const lineup = state.lineup;
    // Hidden channels and categories (Settings › Content) are left out everywhere, counts included.
    const shown = React.useMemo(() => model.shownChannels(), [model, lineup, state.prefs]);
    const shownCategories = React.useMemo(() => model.shownCategories("live"), [model, lineup, state.prefs]);
    const counts = React.useMemo(() => {
        const out = new Map<string, number>();
        for (const c of shown) out.set(c.categoryId, (out.get(c.categoryId) ?? 0) + 1);
        return out;
    }, [shown]);
    const channels = React.useMemo(() => model.visibleChannels(), [model, shown, state.category, state.favourites]);
    const startId = state.channelId && channels.some((c) => c.id === state.channelId) ? state.channelId : channels[0]?.id;
    const startIndex = Math.max(
        0,
        channels.findIndex((c) => c.id === startId),
    );
    const favouriteCount = React.useMemo(() => shown.filter((c) => state.favourites.has(c.id)).length, [shown, state.favourites]);
    const categories: (Category | null)[] = [
        null,
        ...(favouriteCount || state.category === FAVOURITES ? [{ id: FAVOURITES, name: t.favourites }] : []),
        ...shownCategories.filter((c) => counts.has(c.id)),
    ];
    const title =
        state.category === FAVOURITES
            ? t.favourites
            : state.category
              ? (lineup?.categories.find((c) => c.id === state.category)?.name ?? "")
              : t.allChannels;
    const nowTitle = (c: Channel) => state.guide[c.id]?.[0]?.title;
    // Live events first, as Android's chip is, when the playlist has sports or pay-per-view channels.
    const hasSports = React.useMemo(() => model.sportsChannels().length > 0, [model, shown]);

    return (
        <Screen>
            <TopBar
                model={model}
                state={state}
                tab="live"
                status={
                    state.refresh.busy ? (
                        <Spinner
                            line={
                                state.refresh.progress
                                    ? t.refreshStep(
                                          t.refreshing,
                                          state.refresh.progress.step,
                                          state.refresh.progress.steps,
                                          state.refresh.progress.label,
                                      )
                                    : t.refreshing
                            }
                        />
                    ) : state.refresh.result && !state.refresh.result.ok ? (
                        <Text numberOfLines={2} style={{ color: colours.danger, fontSize: dp(13), textAlign: "right" }}>
                            {t.refreshFailed}
                        </Text>
                    ) : null
                }
            />
            <View style={{ flex: 1, flexDirection: "row", gap: dp(20), marginTop: dp(14) }}>
                <View style={{ width: dp(230) }}>
                    {hasSports ? <CategoryRow name={t.liveEvents} selected={false} onPress={() => void model.openEvents()} /> : null}
                    <FlatList
                        data={categories}
                        keyExtractor={(c) => c?.id ?? "all"}
                        renderItem={({ item }) => (
                            <CategoryRow
                                name={item ? item.name : t.allChannels}
                                count={item ? (item.id === FAVOURITES ? favouriteCount : counts.get(item.id)) : shown.length}
                                selected={(item?.id ?? null) === state.category}
                                onPress={() => model.pickCategory(item?.id ?? null)}
                            />
                        )}
                        initialNumToRender={14}
                        windowSize={5}
                    />
                </View>
                <View style={{ flex: 1 }}>
                    <View style={{ flexDirection: "row", alignItems: "center", gap: dp(12), marginBottom: dp(10) }}>
                        <Text numberOfLines={1} style={{ color: colours.text, fontSize: dp(22), fontWeight: "600", flexShrink: 1 }}>
                            {title}
                        </Text>
                        <Text style={{ color: colours.muted, fontSize: dp(13) }}>{t.channels(channels.length)}</Text>
                        <Text numberOfLines={1} style={{ flex: 1, textAlign: "right", color: colours.muted, fontSize: dp(12) }}>
                            {state.prefs.liveGrid ? t.reminderOpenNote : t.favouriteHint}
                        </Text>
                        <Button
                            label={state.prefs.liveGrid ? t.listView : t.guideView}
                            onPress={() => void model.setPrefs({ liveGrid: !state.prefs.liveGrid })}
                        />
                    </View>
                    {channels.length && state.prefs.liveGrid ? (
                        <GuideGrid model={model} state={state} channels={channels} showFocused={focusProgramme} now={fixedNow} />
                    ) : channels.length ? (
                        <FlatList
                            data={channels}
                            keyExtractor={(c) => c.id}
                            getItemLayout={(_, index) => ({ length: dp(ROW), offset: dp(ROW) * index, index })}
                            initialScrollIndex={startIndex}
                            initialNumToRender={12}
                            maxToRenderPerBatch={12}
                            windowSize={5}
                            removeClippedSubviews
                            renderItem={({ item }) => (
                                <ChannelRow
                                    channel={item}
                                    now={nowTitle(item)}
                                    start={item.id === startId}
                                    showFocused={item.id === focusChannel}
                                    favourite={state.favourites.has(item.id)}
                                    onPress={() => void model.play(item)}
                                    onLongPress={() => void model.toggleFavourite(item.id, item.name)}
                                />
                            )}
                        />
                    ) : (
                        <Text style={{ color: colours.muted, fontSize: dp(15) }}>{t.noChannels}</Text>
                    )}
                </View>
            </View>
        </Screen>
    );
}
