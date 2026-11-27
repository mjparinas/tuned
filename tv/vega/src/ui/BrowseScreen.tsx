// Movies and Shows: the tabs along the top, categories down the left and posters beside them, in the order the tab's
// Sort button says (Playlist order by default, as on Android; docs/decisions/playlist.md), with Continue watching first
// when something is half watched. Both are windowed lists, so only the posters
// on screen exist. The saved ones show at once; with none saved yet, the download's steps fill the screen.
import * as React from "react";
import { FlatList, Image, Text, View } from "react-native";
import { CONTINUE, FAVOURITES, type AppModel, type State } from "../app/model";
import type { Category, Title } from "../core/models";
import { CategoryRow } from "./LiveScreen";
import { ProgressLines } from "./LoadingScreen";
import { Button, Focusable, Screen, Spinner } from "./parts";
import { t } from "./strings";
import { TopBar } from "./TopBar";
import { colours, useDp } from "./theme";

const COLUMNS = 5;
const POSTER_W = 110;
const POSTER_H = 165;
const ROW = POSTER_H + 58;

/** The poster, or the name on a card when the provider has no picture (or it doesn't load). */
export function Poster({ title, width, height }: { title: Title; width: number; height: number }) {
    const dp = useDp();
    const [broken, setBroken] = React.useState(false);
    return (
        <View
            style={{
                width,
                height,
                borderRadius: dp(10),
                backgroundColor: colours.card2,
                overflow: "hidden",
                alignItems: "center",
                justifyContent: "center",
            }}
        >
            {title.poster && !broken ? (
                <Image source={{ uri: title.poster }} resizeMode="cover" style={{ width, height }} onError={() => setBroken(true)} />
            ) : (
                <Text
                    numberOfLines={4}
                    style={{ color: colours.muted, fontSize: dp(13), fontWeight: "600", textAlign: "center", padding: dp(8) }}
                >
                    {title.name}
                </Text>
            )}
        </View>
    );
}

function PosterCard({
    title,
    favourite,
    onPress,
    start,
    showFocused,
}: {
    title: Title;
    favourite: boolean;
    onPress: () => void;
    start?: boolean;
    showFocused?: boolean;
}) {
    const dp = useDp();
    const meta = [title.year, title.rating ? t.rating(title.rating) : null].filter(Boolean).join("  ·  ");
    return (
        <Focusable
            start={start}
            showFocused={showFocused}
            onPress={onPress}
            accessibilityRole="button"
            accessibilityLabel={title.year ? `${title.name}, ${title.year}` : title.name}
            style={{ width: dp(POSTER_W + 12), height: dp(ROW - 6), padding: dp(4), marginRight: dp(4), marginBottom: dp(6) }}
            focusedStyle={{ backgroundColor: colours.card2 }}
        >
            {() => (
                <View style={{ gap: dp(4) }}>
                    <View>
                        <Poster title={title} width={dp(POSTER_W)} height={dp(POSTER_H)} />
                        {favourite ? (
                            <Text
                                accessibilityLabel={t.favourites}
                                style={{
                                    position: "absolute",
                                    top: dp(6),
                                    right: dp(6),
                                    color: colours.primary,
                                    fontSize: dp(16),
                                    textShadowColor: "#000",
                                    textShadowRadius: 4,
                                }}
                            >
                                ★
                            </Text>
                        ) : null}
                    </View>
                    <Text numberOfLines={1} style={{ color: colours.text, fontSize: dp(13), fontWeight: "500" }}>
                        {title.name}
                    </Text>
                    {meta ? (
                        <Text numberOfLines={1} style={{ color: colours.muted, fontSize: dp(11) }}>
                            {meta}
                        </Text>
                    ) : null}
                </View>
            )}
        </Focusable>
    );
}

export function BrowseScreen({
    model,
    state,
    kind,
    focusTitle,
}: {
    model: AppModel;
    state: State;
    kind: "movies" | "shows";
    focusTitle?: string;
}) {
    const dp = useDp();
    const library = state.library;
    const load = state.libraryLoad;
    const all = React.useMemo(() => model.shownTitles(kind), [model, library, kind, state.prefs]);
    const categoryList = React.useMemo(() => model.shownCategories(kind), [model, library, kind, state.prefs]);
    const category = kind === "movies" ? state.movieCategory : state.showCategory;
    const order = kind === "movies" ? state.prefs.sortMovies : state.prefs.sortShows;
    const watching = React.useMemo(() => model.continueTitles(kind), [model, all, state.watching]);
    const counts = React.useMemo(() => {
        const out = new Map<string, number>();
        for (const title of all) out.set(title.categoryId, (out.get(title.categoryId) ?? 0) + 1);
        return out;
    }, [all]);
    const titles = React.useMemo(
        () => model.visibleTitles(kind),
        [model, all, kind, category, state.favourites, order, state.watching, order === "rating" ? state.details : null],
    );
    const rows = React.useMemo(() => {
        const out: Title[][] = [];
        for (let i = 0; i < titles.length; i += COLUMNS) out.push(titles.slice(i, i + COLUMNS));
        return out;
    }, [titles]);
    const favouriteCount = React.useMemo(() => all.filter((x) => state.favourites.has(x.id)).length, [all, state.favourites]);
    const categories: (Category | null)[] = [
        ...(watching.length || category === CONTINUE ? [{ id: CONTINUE, name: t.continueWatching }] : []),
        null,
        ...(favouriteCount || category === FAVOURITES ? [{ id: FAVOURITES, name: t.favourites }] : []),
        ...categoryList.filter((c) => counts.has(c.id)),
    ];
    const allName = kind === "movies" ? t.allMovies : t.allShows;
    const heading =
        category === CONTINUE
            ? t.continueWatching
            : category === FAVOURITES
              ? t.favourites
              : category
                ? (categoryList.find((c) => c.id === category)?.name ?? "")
                : allName;
    const count = kind === "movies" ? t.movieCount(titles.length) : t.showCount(titles.length);
    const open = state.title && state.title.kind === (kind === "movies" ? "movie" : "show") ? state.title.id : null;
    const startId = open && titles.some((x) => x.id === open) ? open : titles[0]?.id;
    const startRow = Math.max(0, Math.floor(titles.findIndex((x) => x.id === startId) / COLUMNS));

    const status = load.busy ? (
        library ? (
            <Spinner
                line={
                    load.progress
                        ? t.refreshStep(t.refreshingLibrary, load.progress.step, load.progress.steps, load.progress.label)
                        : t.refreshingLibrary
                }
            />
        ) : null
    ) : load.result && !load.result.ok && library ? (
        <Text numberOfLines={2} style={{ color: colours.danger, fontSize: dp(13), textAlign: "right" }}>
            {t.libraryFailed}
        </Text>
    ) : null;

    let body: React.ReactNode;
    if (!library) {
        const failed = !load.busy && load.result && !load.result.ok;
        body = (
            <View style={{ flex: 1, justifyContent: "center", maxWidth: dp(560), gap: dp(18) }}>
                <Text style={{ color: colours.text, fontSize: dp(26), fontWeight: "600" }}>{t.gettingLibrary}</Text>
                {failed ? (
                    <>
                        <Text style={{ color: colours.danger, fontSize: dp(16), lineHeight: dp(24) }}>{load.result!.text}</Text>
                        <View style={{ flexDirection: "row" }}>
                            <Button label={t.tryAgain} filled start onPress={() => void model.refreshLibrary()} />
                        </View>
                    </>
                ) : state.refresh.busy && !load.busy ? (
                    <ProgressLines progress={state.refresh.progress ?? null} />
                ) : (
                    <ProgressLines progress={load.progress ?? null} />
                )}
            </View>
        );
    } else {
        body = (
            <View style={{ flex: 1, flexDirection: "row", gap: dp(20), marginTop: dp(14) }}>
                <View style={{ width: dp(230) }}>
                    <FlatList
                        data={categories}
                        keyExtractor={(c) => c?.id ?? "all"}
                        renderItem={({ item }) => (
                            <CategoryRow
                                name={item ? item.name : allName}
                                count={
                                    item
                                        ? item.id === FAVOURITES
                                            ? favouriteCount
                                            : item.id === CONTINUE
                                              ? watching.length
                                              : counts.get(item.id)
                                        : all.length
                                }
                                selected={(item?.id ?? null) === category}
                                onPress={() => model.pickBrowseCategory(kind, item?.id ?? null)}
                            />
                        )}
                        initialNumToRender={14}
                        windowSize={5}
                    />
                </View>
                <View style={{ flex: 1 }}>
                    <View style={{ flexDirection: "row", alignItems: "center", gap: dp(12), marginBottom: dp(10) }}>
                        <Text numberOfLines={1} style={{ color: colours.text, fontSize: dp(22), fontWeight: "600", flexShrink: 1 }}>
                            {heading}
                        </Text>
                        <Text style={{ color: colours.muted, fontSize: dp(13), flex: 1 }}>{count}</Text>
                        {category === CONTINUE ? null : (
                            <Button label={t.sortButton(t.sortNames[order])} onPress={() => void model.nextSort(kind)} />
                        )}
                    </View>
                    {rows.length ? (
                        <FlatList
                            key={`${kind}:${category ?? "all"}:${order}`}
                            data={rows}
                            keyExtractor={(row) => row[0].id}
                            getItemLayout={(_, index) => ({ length: dp(ROW), offset: dp(ROW) * index, index })}
                            initialScrollIndex={startRow}
                            initialNumToRender={3}
                            maxToRenderPerBatch={3}
                            windowSize={5}
                            removeClippedSubviews
                            renderItem={({ item: row }) => (
                                <View style={{ flexDirection: "row", height: dp(ROW) }}>
                                    {row.map((title) => (
                                        <PosterCard
                                            key={title.id}
                                            title={title}
                                            favourite={state.favourites.has(title.id)}
                                            start={title.id === startId}
                                            showFocused={title.id === focusTitle}
                                            onPress={() => void model.openTitle(title)}
                                        />
                                    ))}
                                </View>
                            )}
                        />
                    ) : (
                        <Text style={{ color: colours.muted, fontSize: dp(15) }}>{kind === "movies" ? t.noMovies : t.noShows}</Text>
                    )}
                </View>
            </View>
        );
    }

    return (
        <Screen>
            <TopBar model={model} state={state} tab={kind} status={status} />
            {body}
        </Screen>
    );
}
