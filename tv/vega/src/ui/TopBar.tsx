// The row along the top of the browse screens: the brand in the upper left (TvBrand on Android), then Live TV, Movies,
// Shows and Settings, the open one filled, and on the right what's refreshing, so a refresh is never silent.
import * as React from "react";
import { Text, View } from "react-native";
import type { AppModel } from "../app/model";
import { Brand, Focusable } from "./parts";
import { t } from "./strings";
import { colours, useDp } from "./theme";

export type Tab = "live" | "movies" | "shows" | "settings";

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

export function TopBar({ model, tab, status }: { model: AppModel; tab: Tab; status?: React.ReactNode }) {
    const dp = useDp();
    const open = (to: Tab) => {
        if (to === tab) return;
        if (to === "movies" || to === "shows") void model.openBrowse(to);
        else model.go(to);
    };
    return (
        <View style={{ flexDirection: "row", alignItems: "center", gap: dp(20), minHeight: dp(40) }}>
            <Brand />
            <View style={{ flexDirection: "row", gap: dp(6) }} accessibilityRole="tablist">
                <TabButton label={t.liveTv} on={tab === "live"} onPress={() => open("live")} />
                <TabButton label={t.movies} on={tab === "movies"} onPress={() => open("movies")} />
                <TabButton label={t.shows} on={tab === "shows"} onPress={() => open("shows")} />
                <TabButton label={t.settings} on={tab === "settings"} onPress={() => open("settings")} />
            </View>
            <View style={{ flex: 1, alignItems: "flex-end" }}>{status}</View>
        </View>
    );
}
