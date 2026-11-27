// "Add to list" (docs/decisions/lists.md): hold OK on a channel, or the button on a movie's page. Favourites first, then
// your own lists, each ticked when the channel or movie is in it, and New list to make one with it already inside,
// typed with the stick's keyboard. Lists are Premium and live in the Tuned account, so a free or signed-out stick
// shows Favourites alone and says why. Each tick says it's saving until the account has it, and why if it didn't stick.
// Settings › Your lists deletes a list; renaming stays on the phone or website, as on Android TV.
import * as React from "react";
import { ScrollView, Text, TextInput, View } from "react-native";
import { MAX_LIST_NAME } from "@tuned/shared/lists";
import type { AppModel, State } from "../app/model";
import { Brand, Button, Focusable, Screen, Spinner } from "./parts";
import { t } from "./strings";
import { colours, useDp } from "./theme";

function Tick({
    label,
    on,
    busy,
    start,
    showFocused,
    onPress,
}: {
    label: string;
    on: boolean;
    busy: boolean;
    start?: boolean;
    showFocused?: boolean;
    onPress: () => void;
}) {
    const dp = useDp();
    return (
        <Focusable
            start={start}
            showFocused={showFocused}
            onPress={busy ? undefined : onPress}
            accessibilityRole="checkbox"
            accessibilityLabel={label}
            accessibilityState={{ checked: on, busy }}
            style={{
                minHeight: dp(52),
                paddingHorizontal: dp(16),
                flexDirection: "row",
                alignItems: "center",
                gap: dp(12),
                backgroundColor: colours.card,
                marginBottom: dp(6),
            }}
        >
            {() => (
                <>
                    <Text numberOfLines={1} style={{ flex: 1, color: colours.text, fontSize: dp(16), fontWeight: "600" }}>
                        {label}
                    </Text>
                    {busy ? (
                        <Spinner />
                    ) : (
                        <View
                            style={{
                                width: dp(24),
                                height: dp(24),
                                borderRadius: dp(6),
                                borderWidth: dp(2),
                                borderColor: on ? colours.primary : colours.muted,
                                backgroundColor: on ? colours.primary : "transparent",
                                alignItems: "center",
                                justifyContent: "center",
                            }}
                        >
                            {on ? <Text style={{ color: colours.onPrimary, fontSize: dp(15), fontWeight: "800" }}>✓</Text> : null}
                        </View>
                    )}
                </>
            )}
        </Focusable>
    );
}

export function AddToList({
    model,
    state,
    showFocused,
    naming: startNaming,
}: {
    model: AppModel;
    state: State;
    /** For the preview: the row shown focused (0 is Favourites), and the name field open. */
    showFocused?: number;
    naming?: string;
}) {
    const dp = useDp();
    const item = state.listFor;
    const [naming, setNaming] = React.useState(startNaming !== undefined);
    const [name, setName] = React.useState(startNaming ?? "");
    // The remote's Back closes the name field first, then the sheet.
    React.useEffect(() => {
        model.onBack = () => {
            if (naming) setNaming(false);
            else model.closeAddToList();
            return true;
        };
        return () => {
            model.onBack = null;
        };
    }, [model, naming]);
    if (!item) return null;
    const lists = model.myLists();
    const android = model.androidIdOf(item.id);
    const usable = model.canUseLists() && !!android;
    const why = !state.account || !model.currentProfile() ? t.listsSignIn : !usable ? t.listsPremium : null;
    const save = state.listSave;
    const line = save.busy ? t.savingToAccount : save.problem ? t.listProblem[save.problem] : null;
    return (
        <Screen>
            <Brand />
            <View style={{ flex: 1, alignItems: "center", marginTop: dp(24) }}>
                <View style={{ width: dp(520), flex: 1, gap: dp(12) }}>
                    <Text numberOfLines={2} accessibilityRole="header" style={{ color: colours.text, fontSize: dp(24), fontWeight: "600" }}>
                        {t.addToListTitle(item.name)}
                    </Text>
                    {naming ? (
                        <View style={{ gap: dp(12) }}>
                            <TextInput
                                accessibilityLabel={t.listName}
                                hasTVPreferredFocus
                                value={name}
                                maxLength={MAX_LIST_NAME}
                                placeholder={t.listName}
                                placeholderTextColor={colours.muted}
                                onChangeText={setName}
                                style={{
                                    minHeight: dp(48),
                                    borderRadius: dp(14),
                                    borderWidth: dp(2),
                                    borderColor: colours.focus,
                                    paddingHorizontal: dp(14),
                                    color: colours.text,
                                    fontSize: dp(16),
                                    backgroundColor: colours.card,
                                }}
                            />
                            <View style={{ flexDirection: "row", gap: dp(12) }}>
                                <Button
                                    label={t.create}
                                    filled
                                    busy={save.busy === "new"}
                                    onPress={() => {
                                        if (!name.trim()) return;
                                        void model.newList(name).then((ok) => {
                                            if (!ok) return;
                                            setNaming(false);
                                            setName("");
                                        });
                                    }}
                                />
                                <Button label={t.back} onPress={() => setNaming(false)} />
                            </View>
                        </View>
                    ) : (
                        <ScrollView style={{ flex: 1 }}>
                            <Tick
                                label={`★  ${t.favourites}`}
                                on={state.favourites.has(item.id)}
                                busy={save.busy === "favourite"}
                                start
                                showFocused={showFocused === 0}
                                onPress={() => void model.toggleFavourite(item.id, item.name)}
                            />
                            {usable
                                ? lists.map((l, i) => {
                                      const inside = !!android && l.items.includes(android);
                                      return (
                                          <Tick
                                              key={l.id}
                                              label={l.name}
                                              on={inside}
                                              busy={save.busy === l.id}
                                              showFocused={showFocused === i + 1}
                                              onPress={() => void model.setInList(l.id, !inside)}
                                          />
                                      );
                                  })
                                : null}
                            {why ? (
                                <Text style={{ color: colours.muted, fontSize: dp(14), marginTop: dp(6) }}>{why}</Text>
                            ) : !lists.length ? (
                                <Text style={{ color: colours.muted, fontSize: dp(14), marginTop: dp(6) }}>{t.noListsYet}</Text>
                            ) : null}
                            <View style={{ flexDirection: "row", gap: dp(12), marginTop: dp(14) }}>
                                {usable ? <Button label={`+  ${t.newList}`} onPress={() => setNaming(true)} /> : null}
                                <Button label={t.done} onPress={() => model.closeAddToList()} />
                            </View>
                        </ScrollView>
                    )}
                    <Text
                        accessibilityLiveRegion="polite"
                        style={{ minHeight: dp(20), color: save.problem && !save.busy ? colours.danger : colours.muted, fontSize: dp(14) }}
                    >
                        {line ?? ""}
                    </Text>
                </View>
            </View>
        </Screen>
    );
}

/** Settings › Your lists: the profile's lists with how much is in each, Delete pressed twice. */
export function ManageLists({ model, state }: { model: AppModel; state: State }) {
    const dp = useDp();
    const [asked, setAsked] = React.useState<string | null>(null);
    if (!model.canUseLists()) return null;
    const lists = model.myLists();
    const save = state.listSave;
    return (
        <View style={{ gap: dp(8) }}>
            {!lists.length ? <Text style={{ color: colours.muted, fontSize: dp(14) }}>{t.noListsYet}</Text> : null}
            {lists.map((l) => (
                <View key={l.id} style={{ flexDirection: "row", alignItems: "center", gap: dp(12), minHeight: dp(48) }}>
                    <Text numberOfLines={1} style={{ flex: 1, color: colours.text, fontSize: dp(15), fontWeight: "600" }}>
                        {l.name}
                    </Text>
                    <Text style={{ color: colours.muted, fontSize: dp(13) }}>{t.listCount(l.items.length)}</Text>
                    <Button
                        label={asked === l.id ? t.deleteListSure : t.deleteList}
                        busy={save.busy === l.id}
                        onPress={() => {
                            if (asked !== l.id) return setAsked(l.id);
                            setAsked(null);
                            void model.deleteList(l.id);
                        }}
                    />
                </View>
            ))}
            {save.problem && !save.busy && !state.listFor ? (
                <Text accessibilityLiveRegion="polite" style={{ color: colours.danger, fontSize: dp(14) }}>
                    {t.listProblem[save.problem]}
                </Text>
            ) : null}
            <Text style={{ color: colours.muted, fontSize: dp(13) }}>{t.listsOnPhone}</Text>
        </View>
    );
}
