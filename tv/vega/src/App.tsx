// The app shell: one model, the screen for its route, and Back going up one level (Back on Live TV leaves the app,
// as Amazon expects). Nothing here waits on the network: boot reads what the stick saved and draws it.
import { useKeplerBackHandler } from "@amazon-devices/react-native-kepler";
import * as React from "react";
import { View } from "react-native";
import { AppModel, type Services, type State } from "./app/model";
import { launchSafely, log } from "./core/log";
import { BrowseScreen } from "./ui/BrowseScreen";
import { LiveScreen } from "./ui/LiveScreen";
import { LoadingScreen } from "./ui/LoadingScreen";
import { ManualScreen } from "./ui/ManualScreen";
import { PlayerScreen, type PlayerHandle } from "./ui/PlayerScreen";
import { Screen, Spinner } from "./ui/parts";
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

export function Shell({ model, player }: { model: AppModel; player: VegaPlayer }) {
    const state = useModelState(model);
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
                    model.go(model.state.lineup ? (model.state.route === "account" ? "settings" : "live") : "signin");
                    return true;
                case "manual":
                    model.go("signin");
                    return true;
                case "movies":
                case "shows":
                    model.go("live");
                    return true;
                case "title":
                    model.go(model.state.title?.kind === "show" ? "shows" : "movies");
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
        case "movies":
        case "shows":
            return <BrowseScreen model={model} state={state} kind={state.route} />;
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
