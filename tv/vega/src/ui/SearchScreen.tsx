// Search across the playlist's channels, movies and shows, as on Android (core/Search): accents and punctuation don't
// matter, and every word typed must start a word of the name. Typing uses the stick's own on-screen keyboard; the
// results update a moment after each letter. A channel plays at once; a movie or show opens its page.
import * as React from "react";
import { FlatList, Text, TextInput, View } from "react-native";
import type { AppModel, Found, State } from "../app/model";
import { Poster } from "./BrowseScreen";
import { Focusable, Screen } from "./parts";
import { t } from "./strings";
import { TopBar } from "./TopBar";
import { colours, useDp } from "./theme";

const ROW = 64;

function ResultRow({ found, onPress, showFocused }: { found: Found; onPress: () => void; showFocused?: boolean }) {
    const dp = useDp();
    const name = found.kind === "live" ? found.channel.name : found.title.name;
    const kind = found.kind === "live" ? t.liveTv : found.kind === "movie" ? t.movies : t.shows;
    const extra = found.kind === "live" ? String(found.channel.number) : found.title.year ? String(found.title.year) : "";
    return (
        <Focusable
            onPress={onPress}
            showFocused={showFocused}
            accessibilityRole="button"
            accessibilityLabel={`${name}, ${kind}`}
            style={{
                height: dp(ROW - 6),
                marginBottom: dp(6),
                paddingHorizontal: dp(12),
                flexDirection: "row",
                alignItems: "center",
                gap: dp(14),
                backgroundColor: colours.card,
            }}
        >
            {() => (
                <>
                    {found.kind === "live" ? (
                        <View
                            style={{
                                width: dp(34),
                                height: dp(50),
                                borderRadius: dp(6),
                                backgroundColor: colours.card2,
                                alignItems: "center",
                                justifyContent: "center",
                            }}
                        >
                            <Text style={{ color: colours.muted, fontSize: dp(11), fontWeight: "600" }}>
                                {name.slice(0, 3).toUpperCase()}
                            </Text>
                        </View>
                    ) : (
                        <Poster title={found.title} width={dp(34)} height={dp(50)} />
                    )}
                    <Text numberOfLines={1} style={{ flex: 1, color: colours.text, fontSize: dp(16), fontWeight: "500" }}>
                        {name}
                    </Text>
                    {extra ? <Text style={{ color: colours.muted, fontSize: dp(13), fontVariant: ["tabular-nums"] }}>{extra}</Text> : null}
                    <Text style={{ width: dp(80), textAlign: "right", color: colours.primary, fontSize: dp(12) }}>{kind}</Text>
                </>
            )}
        </Focusable>
    );
}

export function SearchScreen({ model, state, focusFirst }: { model: AppModel; state: State; focusFirst?: boolean }) {
    const dp = useDp();
    const [typed, setTyped] = React.useState(state.query);
    React.useEffect(() => {
        const timer = setTimeout(() => model.setQuery(typed), 250);
        return () => clearTimeout(timer);
    }, [model, typed]);
    const results = React.useMemo(() => model.results(), [model, state.query, state.lineup, state.library]);
    const [focused, setFocused] = React.useState(false);
    return (
        <Screen>
            <TopBar model={model} state={state} tab="search" />
            <View style={{ flex: 1, marginTop: dp(16), gap: dp(14) }}>
                <TextInput
                    value={typed}
                    onChangeText={setTyped}
                    placeholder={t.searchHint}
                    placeholderTextColor={colours.muted}
                    accessibilityLabel={t.search}
                    autoFocus={!state.query}
                    hasTVPreferredFocus={!state.query}
                    onFocus={() => setFocused(true)}
                    onBlur={() => setFocused(false)}
                    returnKeyType="search"
                    style={{
                        height: dp(48),
                        borderRadius: dp(12),
                        paddingHorizontal: dp(16),
                        backgroundColor: colours.card,
                        color: colours.text,
                        fontSize: dp(17),
                        borderWidth: dp(2),
                        borderColor: focused ? colours.focus : "transparent",
                        maxWidth: dp(640),
                    }}
                />
                {state.query.trim() && !results.length ? (
                    <Text style={{ color: colours.muted, fontSize: dp(15) }}>{t.noResults(state.query.trim())}</Text>
                ) : null}
                <FlatList
                    data={results}
                    keyExtractor={(f) => (f.kind === "live" ? `c:${f.channel.id}` : `${f.kind}:${f.title.id}`)}
                    getItemLayout={(_, index) => ({ length: dp(ROW), offset: dp(ROW) * index, index })}
                    initialNumToRender={8}
                    windowSize={5}
                    removeClippedSubviews
                    renderItem={({ item, index }) => (
                        <ResultRow
                            found={item}
                            showFocused={focusFirst && index === 0}
                            onPress={() => (item.kind === "live" ? void model.play(item.channel) : void model.openTitle(item.title))}
                        />
                    )}
                />
            </View>
        </Screen>
    );
}
