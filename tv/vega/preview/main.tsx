// The preview's scenes: each of the stick's screens with made-up sample data (no real provider, channel or brand,
// as store screenshots require), picked by ?scene=. preview/shoot.mjs draws every one at 1920 × 1080.
import * as React from "react";
import { createRoot } from "react-dom/client";
import { View } from "react-native";
import { AppModel, type State } from "../src/app/model";
import type { Http } from "../src/core/http";
import type { Channel, Lineup } from "../src/core/models";
import { memoryStore } from "../src/core/storage";
import { Shell } from "../src/App";
import { LiveScreen } from "../src/ui/LiveScreen";
import { LoadingScreen } from "../src/ui/LoadingScreen";
import { ManualScreen } from "../src/ui/ManualScreen";
import { PlayerScreen } from "../src/ui/PlayerScreen";
import { SettingsScreen } from "../src/ui/SettingsScreen";
import { SignInScreen } from "../src/ui/SignInScreen";
import { VegaPlayer } from "../src/platform/VegaPlayer";

const offline: Http = {
    text: () => Promise.reject(new Error("preview")),
    getJson: () => Promise.reject(new Error("preview")),
    postJson: () => Promise.reject(new Error("preview")),
};

const CATEGORIES = [
    ["1", "News"],
    ["2", "Sports"],
    ["3", "Kids"],
    ["4", "Films"],
    ["5", "Music"],
    ["6", "Documentaries"],
    ["7", "Local"],
] as const;
const NAMES: Record<string, string[]> = {
    "1": ["Northside News", "Harbour News 24", "City Desk", "World Report", "Morning Wire", "Metro Update"],
    "2": ["Field One", "Field Two", "Rink Live", "Court Side", "Track & Trail", "Goal Line"],
    "3": ["Little Owls", "Cartoon Corner", "Story Time", "Bright Sparks"],
    "4": ["Classic Cinema", "Late Show Films", "Picture House", "Reel Drama"],
    "5": ["Hit Mix", "Jazz Lounge", "Country Roads"],
    "6": ["Planet Wild", "Deep Blue", "History Hall", "Science Now"],
    "7": ["Valley TV", "Lakeside Community", "Coastline"],
};

function sampleLineup(): Lineup {
    const channels: Channel[] = [];
    for (const [id] of CATEGORIES) {
        for (const name of NAMES[id]) {
            const number = channels.length + 101;
            channels.push({
                id: `c${number}`,
                name,
                number,
                categoryId: id,
                logo: null,
                url: `http://provider.example/live/${number}.ts`,
                streamId: number,
            });
        }
    }
    return { categories: CATEGORIES.map(([id, name]) => ({ id, name })), channels, savedAt: Date.now() };
}

function model(patch: Partial<State>): AppModel {
    const m = new AppModel({
        store: memoryStore(),
        http: offline,
        random: (n) => crypto.getRandomValues(new Uint8Array(n)),
        player: new VegaPlayer(),
        deviceName: "Preview",
    });
    m.state = { ...m.state, ...patch };
    return m;
}

const hour = 3600_000;
const at = (h: number, m: number) => new Date(2026, 10, 27, h, m).getTime();
const lineup = sampleLineup();
const account = { token: "t", email: "you@example.com", premium: true, seat: "active" as const, checkedAt: Date.now() };

function Scene({ name }: { name: string }) {
    const player = React.useMemo(() => new VegaPlayer(), []);
    switch (name) {
        case "signin":
            return (
                <SignInScreen
                    model={model({})}
                    mode="playlist"
                    view={{
                        kind: "showing",
                        code: "K7QF-29MD",
                        site: "www.tunedplayer.com/tv",
                        digits: "4821",
                        qr: "https://www.tunedplayer.com/tv#K7QF29MD.BPreviewKeyOnlyNotARealKeyAtAll",
                    }}
                />
            );
        case "signin-offline":
            return <SignInScreen model={model({})} mode="playlist" view={{ kind: "offline" }} />;
        case "manual":
            return <ManualScreen model={model({})} />;
        case "loading": {
            const m = model({
                route: "loading",
                loading: {
                    progress: {
                        step: 3,
                        steps: 3,
                        label: "Downloading channels",
                        loaded: 7.4 * 1024 * 1024,
                        total: 18.2 * 1024 * 1024,
                        eta: "about 1 min left",
                    },
                    error: null,
                },
            });
            return <LoadingScreen model={m} state={m.state} />;
        }
        case "loading-failed": {
            const m = model({
                route: "loading",
                loading: { progress: null, error: "The server didn't accept that username and password." },
            });
            return <LoadingScreen model={m} state={m.state} />;
        }
        case "live": {
            const m = model({
                route: "live",
                lineup,
                channelId: "c101",
                guide: {
                    c101: [{ title: "Evening Headlines", start: at(18, 0), end: at(19, 0) }],
                    c102: [{ title: "The Week Ahead", start: at(18, 30), end: at(19, 30) }],
                },
            });
            return <LiveScreen model={m} state={m.state} focusChannel="c101" />;
        }
        case "live-refreshing": {
            const m = model({
                route: "live",
                lineup,
                category: "2",
                channelId: "c107",
                refresh: { busy: true, progress: { step: 2, steps: 3, label: "Downloading categories" } },
            });
            return <LiveScreen model={m} state={m.state} focusChannel="c107" />;
        }
        case "player": {
            const m = model({
                route: "player",
                lineup,
                channelId: "c101",
                guide: {
                    c101: [
                        { title: "Evening Headlines", start: at(18, 0), end: at(19, 0) },
                        { title: "Weather and Traffic", start: at(19, 0), end: at(19, 0) + hour / 2 },
                    ],
                },
            });
            return <PlayerScreen model={m} state={m.state} player={player} shown={{ controls: true, playback: "playing" }} />;
        }
        case "player-failed": {
            const m = model({ route: "player", lineup, channelId: "c107" });
            return <PlayerScreen model={m} state={m.state} player={player} shown={{ playback: "failed" }} />;
        }
        case "settings": {
            const m = model({ route: "settings", lineup, account });
            return <SettingsScreen model={m} state={m.state} />;
        }
        case "settings-refreshing": {
            const m = model({
                route: "settings",
                lineup,
                refresh: {
                    busy: true,
                    progress: { step: 3, steps: 3, label: "Downloading channels", loaded: 2.1 * 1024 * 1024, total: null },
                },
            });
            return <SettingsScreen model={m} state={m.state} />;
        }
        default: {
            const m = model({});
            void m.boot();
            return <Shell model={m} player={player} />;
        }
    }
}

const scene = new URLSearchParams(location.search).get("scene") ?? "";
createRoot(document.getElementById("root")!).render(
    <View style={{ width: "100vw" as unknown as number, height: "100vh" as unknown as number }}>
        <Scene name={scene} />
    </View>,
);
