// A movie's page (Play, or Resume where it was left and From the start) and a show's (its seasons and episodes, with
// the episodes' own names where the provider gives them; Melvin, 2026-11-17: real names over "S1 E1"). The remote
// starts on the likely choice: Resume or Play, or the first episode of the first season.
import * as React from "react";
import { FlatList, ScrollView, Text, View } from "react-native";
import type { AppModel, State } from "../app/model";
import type { Episode, Title } from "../core/models";
import { clockTime } from "../core/progress";
import { Poster } from "./BrowseScreen";
import { Brand, Button, Focusable, Screen, Spinner } from "./parts";
import { t } from "./strings";
import { colours, useDp } from "./theme";

const EPISODE_ROW = 60;

function Meta({ title, category }: { title: Title; category?: string }) {
    const dp = useDp();
    const line = [title.year, title.rating ? t.rating(title.rating) : null, category].filter(Boolean).join("  ·  ");
    return line ? <Text style={{ color: colours.muted, fontSize: dp(15) }}>{line}</Text> : null;
}

function Plot({ text }: { text?: string }) {
    const dp = useDp();
    return text ? (
        <Text numberOfLines={4} style={{ color: colours.text, fontSize: dp(15), lineHeight: dp(23), maxWidth: dp(560) }}>
            {text}
        </Text>
    ) : null;
}

function MoviePage({ model, state, title, category }: { model: AppModel; state: State; title: Title; category?: string }) {
    const dp = useDp();
    const at = state.positions[title.id] ?? 0;
    const item = { id: title.id, title: title.name, url: title.url ?? "" };
    return (
        <View style={{ gap: dp(14), flex: 1 }}>
            <Text style={{ color: colours.text, fontSize: dp(30), fontWeight: "600" }}>{title.name}</Text>
            <Meta title={title} category={category} />
            <Plot text={title.plot} />
            <View style={{ flexDirection: "row", gap: dp(12), marginTop: dp(8) }}>
                {at > 0 ? (
                    <>
                        <Button label={t.resume(clockTime(at))} filled start onPress={() => void model.playVod(item, at)} />
                        <Button label={t.fromStart} onPress={() => void model.playVod(item, 0)} />
                    </>
                ) : (
                    <Button label={t.play} filled start onPress={() => void model.playVod(item, 0)} />
                )}
            </View>
        </View>
    );
}

function EpisodeRow({
    episode,
    at,
    onPress,
    start,
    showFocused,
}: {
    episode: Episode;
    at: number;
    onPress: () => void;
    start?: boolean;
    showFocused?: boolean;
}) {
    const dp = useDp();
    const name = episode.name ?? t.episodeNumber(episode.episode);
    return (
        <Focusable
            start={start}
            showFocused={showFocused}
            onPress={onPress}
            accessibilityRole="button"
            accessibilityLabel={`${t.seasonEpisode(episode.season, episode.episode)}, ${name}`}
            style={{
                height: dp(EPISODE_ROW - 6),
                marginBottom: dp(6),
                paddingHorizontal: dp(14),
                flexDirection: "row",
                alignItems: "center",
                gap: dp(14),
                backgroundColor: colours.card,
            }}
        >
            {() => (
                <>
                    <Text style={{ width: dp(64), color: colours.muted, fontSize: dp(13), fontVariant: ["tabular-nums"] }}>
                        {t.episodeShort(episode.season, episode.episode)}
                    </Text>
                    <View style={{ flex: 1 }}>
                        <Text numberOfLines={1} style={{ color: colours.text, fontSize: dp(16), fontWeight: "500" }}>
                            {name}
                        </Text>
                        {episode.plot ? (
                            <Text numberOfLines={1} style={{ color: colours.muted, fontSize: dp(12) }}>
                                {episode.plot}
                            </Text>
                        ) : null}
                    </View>
                    {at > 0 ? <Text style={{ color: colours.teal, fontSize: dp(12) }}>{t.resume(clockTime(at))}</Text> : null}
                </>
            )}
        </Focusable>
    );
}

function ShowPage({
    model,
    state,
    title,
    category,
    focusEpisode,
}: {
    model: AppModel;
    state: State;
    title: Title;
    category?: string;
    focusEpisode?: string;
}) {
    const dp = useDp();
    const episodes = model.episodesOf(title);
    const load = state.episodesLoad;
    const seasons = React.useMemo(() => [...new Set((episodes ?? []).map((e) => e.season))].sort((a, b) => a - b), [episodes]);
    const [season, setSeason] = React.useState<number | null>(null);
    const shown = season ?? seasons[0] ?? 1;
    const inSeason = React.useMemo(() => (episodes ?? []).filter((e) => e.season === shown), [episodes, shown]);
    const play = (e: Episode) =>
        void model.playVod(
            {
                id: e.id,
                title: title.name,
                subtitle: e.name ? `${t.seasonEpisode(e.season, e.episode)}: ${e.name}` : t.seasonEpisode(e.season, e.episode),
                url: e.url,
            },
            state.positions[e.id] ?? 0,
        );

    let list: React.ReactNode;
    if (!episodes) {
        list =
            load.id === title.id && load.failed ? (
                <View style={{ gap: dp(12) }}>
                    <Text style={{ color: colours.danger, fontSize: dp(15), lineHeight: dp(22) }}>{t.episodesFailed}</Text>
                    <View style={{ flexDirection: "row" }}>
                        <Button label={t.tryAgain} filled start onPress={() => void model.loadEpisodes(title)} />
                    </View>
                </View>
            ) : (
                <Spinner line={t.loadingEpisodes} />
            );
    } else if (!episodes.length) {
        list = <Text style={{ color: colours.muted, fontSize: dp(15) }}>{t.noEpisodes}</Text>;
    } else {
        list = (
            <View style={{ flex: 1, gap: dp(10) }}>
                {seasons.length > 1 ? (
                    <ScrollView
                        horizontal
                        style={{ flexGrow: 0, minHeight: dp(44) }}
                        contentContainerStyle={{ gap: dp(8), paddingVertical: dp(2) }}
                    >
                        {seasons.map((s) => (
                            <Focusable
                                key={s}
                                onPress={() => setSeason(s)}
                                onFocus={() => setSeason(s)}
                                accessibilityRole="tab"
                                accessibilityLabel={t.season(s)}
                                accessibilityState={{ selected: s === shown }}
                                style={{
                                    minHeight: dp(34),
                                    paddingHorizontal: dp(14),
                                    borderRadius: dp(17),
                                    justifyContent: "center",
                                    backgroundColor: s === shown ? colours.primarySoft : colours.card,
                                }}
                            >
                                {() => (
                                    <Text
                                        style={{
                                            color: s === shown ? colours.onPrimarySoft : colours.text,
                                            fontSize: dp(14),
                                            fontWeight: "500",
                                        }}
                                    >
                                        {t.season(s)}
                                    </Text>
                                )}
                            </Focusable>
                        ))}
                    </ScrollView>
                ) : null}
                <FlatList
                    key={String(shown)}
                    data={inSeason}
                    keyExtractor={(e) => e.id}
                    getItemLayout={(_, index) => ({ length: dp(EPISODE_ROW), offset: dp(EPISODE_ROW) * index, index })}
                    initialNumToRender={8}
                    windowSize={5}
                    renderItem={({ item, index }) => (
                        <EpisodeRow
                            episode={item}
                            at={state.positions[item.id] ?? 0}
                            start={index === 0}
                            showFocused={item.id === focusEpisode}
                            onPress={() => play(item)}
                        />
                    )}
                />
            </View>
        );
    }

    return (
        <View style={{ gap: dp(10), flex: 1 }}>
            <Text style={{ color: colours.text, fontSize: dp(28), fontWeight: "600" }}>{title.name}</Text>
            <View style={{ flexDirection: "row", gap: dp(14), alignItems: "baseline" }}>
                <Meta title={title} category={category} />
                {episodes?.length ? (
                    <Text style={{ color: colours.muted, fontSize: dp(15) }}>{t.episodeCount(episodes.length)}</Text>
                ) : null}
            </View>
            <Plot text={title.plot} />
            <View style={{ flex: 1, marginTop: dp(4) }}>{list}</View>
        </View>
    );
}

export function TitleScreen({ model, state, focusEpisode }: { model: AppModel; state: State; focusEpisode?: string }) {
    const dp = useDp();
    const title = state.title;
    if (!title) return null;
    const categories = title.kind === "movie" ? state.library?.movieCategories : state.library?.showCategories;
    const category = categories?.find((c) => c.id === title.categoryId)?.name;
    return (
        <Screen>
            <Brand />
            <View style={{ flex: 1, flexDirection: "row", gap: dp(28), marginTop: dp(18) }}>
                <Poster title={title} width={dp(200)} height={dp(300)} />
                {title.kind === "movie" ? (
                    <MoviePage model={model} state={state} title={title} category={category} />
                ) : (
                    <ShowPage key={title.id} model={model} state={state} title={title} category={category} focusEpisode={focusEpisode} />
                )}
            </View>
        </Screen>
    );
}
