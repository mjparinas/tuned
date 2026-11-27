// The row along the top of the browse screens: the brand in the upper left (TvBrand on Android), then Live TV, Movies,
// Shows, Search and Settings, the open one filled, and on the right what's refreshing, so a refresh is never silent,
// or what a star just did.
import * as React from "react";
import { Text, View } from "react-native";
import type { AppModel, State } from "../app/model";
import { Brand, Focusable } from "./parts";
import { Avatar } from "./ProfilesScreen";
import { t } from "./strings";
import { colours, useDp } from "./theme";

export type Tab = "live" | "movies" | "shows" | "search" | "settings";

function TabButton({ label, on, onPress }: { label: string; on: boolean; onPress: () => void }) {
    const dp = useDp();
    return (
        <Focusable
            onPress={onPress}
            accessibilityRole="tab"
            accessibilityLabel={label}
            accessibilityState={{ selected: on }}
            style={{
                minHeight: dp(36),
                paddingHorizontal: dp(16),
                borderRadius: dp(18),
                justifyContent: "center",
                backgroundColor: on ? colours.primarySoft : "transparent",
            }}
        >
            {() => (
                <Text style={{ color: on ? colours.onPrimarySoft : colours.text, fontSize: dp(15), fontWeight: on ? "600" : "500" }}>
                    {label}
                </Text>
            )}
        </Focusable>
    );
}

/** The profile in use, while there's more than one: OK opens "Who's watching?" to switch. */
function ProfileBadge({ model, state }: { model: AppModel; state?: State }) {
    const dp = useDp();
    const profile = state && state.profiles.profiles.length > 1 ? model.currentProfile() : null;
    if (!profile) return null;
    return (
        <Focusable
            onPress={() => model.openProfiles()}
            accessibilityRole="button"
            accessibilityLabel={`${t.watchingAs(profile.name)}. ${t.switchProfile}`}
            style={{ padding: dp(2), borderRadius: dp(14) }}
        >
            {() => <Avatar profile={profile} size={dp(32)} />}
        </Focusable>
    );
}

export function TopBar({ model, state, tab, status }: { model: AppModel; state?: State; tab: Tab; status?: React.ReactNode }) {
    const dp = useDp();
    const open = (to: Tab) => {
        if (to === tab) return;
        if (to === "movies" || to === "shows") void model.openBrowse(to);
        else if (to === "search") void model.openSearch();
        else model.go(to);
    };
    return (
        <View style={{ flexDirection: "row", alignItems: "center", gap: dp(20), minHeight: dp(40) }}>
            <Brand />
            <View style={{ flexDirection: "row", gap: dp(6) }} accessibilityRole="tablist">
                <TabButton label={t.liveTv} on={tab === "live"} onPress={() => open("live")} />
                <TabButton label={t.movies} on={tab === "movies"} onPress={() => open("movies")} />
                <TabButton label={t.shows} on={tab === "shows"} onPress={() => open("shows")} />
                <TabButton label={t.search} on={tab === "search"} onPress={() => open("search")} />
                <TabButton label={t.settings} on={tab === "settings"} onPress={() => open("settings")} />
            </View>
            <View style={{ flex: 1, alignItems: "flex-end" }}>
                {state?.notice ? (
                    <Text accessibilityLiveRegion="polite" numberOfLines={1} style={{ color: colours.teal, fontSize: dp(14) }}>
                        {state.notice.text ??
                            (state.notice.on ? t.favouriteAdded(state.notice.name) : t.favouriteRemoved(state.notice.name))}
                    </Text>
                ) : (
                    status
                )}
            </View>
            <ProfileBadge model={model} state={state} />
        </View>
    );
}
