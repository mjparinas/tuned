// The app shell: one model, the screen for its route, and Back going up one level (Back on Live TV leaves the app,
// as Amazon expects). Nothing here waits on the network: boot reads what the stick saved and draws it.
import { useKeplerBackHandler } from "@amazon-devices/react-native-kepler";
import * as React from "react";
import { Text, View } from "react-native";
import { AppModel, type Services, type State } from "./app/model";
import { launchSafely, log } from "./core/log";
import { BrowseScreen } from "./ui/BrowseScreen";
import { EventsScreen } from "./ui/EventsScreen";
import { LiveScreen } from "./ui/LiveScreen";
import { LoadingScreen } from "./ui/LoadingScreen";
import { ManualScreen } from "./ui/ManualScreen";
import { PlayerScreen, type PlayerHandle } from "./ui/PlayerScreen";
import { Button, Screen, Spinner } from "./ui/parts";
import { t } from "./ui/strings";
import { colours, useDp } from "./ui/theme";
import { SearchScreen } from "./ui/SearchScreen";
import { SettingsScreen } from "./ui/SettingsScreen";
import { SignInScreen } from "./ui/SignInScreen";
import { TitleScreen } from "./ui/TitleScreen";
import type { VegaPlayer } from "./platform/VegaPlayer";

export function useModelState(model: AppModel): State {
    return React.useSyncExternalStore(model.subscribe, model.getState, model.getState);
}

/** Catches anything a screen throws, logs it and starts the app again on its home screen (CrashReporter on Android). */
class Recover extends React.Component<{ children: React.ReactNode; onError: () => void }, { failed: number }> {
    state = { failed: 0 };
    static getDerivedStateFromError() {
        return null;
    }
    componentDidCatch(error: unknown) {
        log.error("App", "screen failed", error);
        this.setState((s) => ({ failed: s.failed + 1 }));
        this.props.onError();
    }
    render() {
        return <React.Fragment key={this.state.failed}>{this.props.children}</React.Fragment>;
    }
}

/**
 * A reminder whose programme just started, over whatever is on screen except the player: what and where, with Watch
 * and Not now. It never takes the remote's focus, so nothing being done is interrupted.
 */
export function ReminderBanner({ model, state }: { model: AppModel; state: State }) {
    const dp = useDp();
    const due = state.due;
    if (!due || state.route === "player") return null;
    return (
        <View
            accessibilityLiveRegion="assertive"
            style={{
                position: "absolute",
                left: dp(48),
                right: dp(48),
                bottom: dp(32),
                flexDirection: "row",
                alignItems: "center",
                gap: dp(14),
                padding: dp(16),
                borderRadius: dp(18),
                backgroundColor: colours.card2,
            }}
        >
            <Text numberOfLines={2} style={{ flex: 1, color: colours.text, fontSize: dp(16), fontWeight: "600" }}>
                {t.reminderNow(due.title, due.channelName)}
            </Text>
            <Button label={t.watch} filled onPress={() => void model.watchDue()} />
            <Button label={t.notNow} onPress={() => model.dismissDue()} />
        </View>
    );
}

export function Shell({ model, player }: { model: AppModel; player: VegaPlayer }) {
    const state = useModelState(model);
    return (
        <View style={{ flex: 1 }}>
            <Screens model={model} player={player} state={state} />
            <ReminderBanner model={model} state={state} />
        </View>
    );
}

function Screens({ model, player, state }: { model: AppModel; player: VegaPlayer; state: State }) {
    const playerRef = React.useRef<PlayerHandle>(null);
    const back = useKeplerBackHandler();

    React.useEffect(() => {
        const handler = () => {
            switch (model.state.route) {
                case "player":
                    if (!playerRef.current?.back()) launchSafely("back.player", () => model.stopPlaying());
                    return true;
                case "settings":
                case "account":
                    if (model.state.route === "account") model.go("settings");
                    else model.go(model.state.lineup ? "live" : model.state.login ? "loading" : "signin");
                    return true;
                case "signin":
                    if (!model.state.adding) {
                        back.exitApp();
                        return true;
                    }
                    model.cancelAdding();
                    return true;
                case "loading":
                    if (model.state.playlists.length > 1) model.go("settings");
                    else back.exitApp();
                    return true;
                case "manual":
                    model.go("signin");
                    return true;
                case "movies":
                case "shows":
                case "search":
                case "events":
                    model.go("live");
                    return true;
                case "title":
                    model.closeTitle();
                    return true;
                default:
                    back.exitApp();
                    return true;
            }
        };
        const subscription = back.addEventListener("backPress", handler);
        return () => subscription.remove();
    }, [model, back]);

    switch (state.route) {
        case "boot":
            return (
                <Screen>
                    <Spinner />
                </Screen>
            );
        case "signin":
            return <SignInScreen model={model} mode="playlist" />;
        case "account":
            return <SignInScreen model={model} mode="account" />;
        case "manual":
            return <ManualScreen model={model} />;
        case "loading":
            return <LoadingScreen model={model} state={state} />;
        case "live":
            return <LiveScreen model={model} state={state} />;
        case "events":
            return <EventsScreen model={model} state={state} />;
        case "movies":
        case "shows":
            return <BrowseScreen model={model} state={state} kind={state.route} />;
        case "search":
            return <SearchScreen model={model} state={state} />;
        case "title":
            return <TitleScreen model={model} state={state} />;
        case "player":
            return <PlayerScreen ref={playerRef} model={model} state={state} player={player} />;
        case "settings":
            return <SettingsScreen model={model} state={state} />;
    }
}

export function makeApp(services: Services, player: VegaPlayer) {
    const model = new AppModel(services);
    launchSafely(
        "boot",
        () => model.boot(),
        () => model.go("signin"),
    );
    return function App() {
        return (
            <View style={{ flex: 1, backgroundColor: "#121018" }}>
                <Recover onError={() => model.go(model.state.lineup ? "live" : "signin")}>
                    <Shell model={model} player={player} />
                </Recover>
            </View>
        );
    };
}
