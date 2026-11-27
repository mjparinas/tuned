// The app's screens: the welcome (sign in with a phone) until there's a playlist, its first download, then Home.
// Screens opened from there stack above it and keep their place underneath; the player sits on top of everything.
import { useLayoutEffect, useRef } from "preact/hooks";
import { currentId, playlists, ready } from "../app.ts";
import { focus } from "../focus.ts";
import { playback } from "../player/controller.ts";
import { useStore } from "../state.ts";
import { FilmPage, ShowPage } from "./details.tsx";
import { FirstLoad } from "./loading.tsx";
import { Home } from "./home.tsx";
import { LogScreen } from "./log.tsx";
import { routes, type Route } from "./nav.ts";
import { Spinner } from "./parts.tsx";
import { Player } from "./player.tsx";
import { Manual, SignIn } from "./signin.tsx";

function screenFor(route: Route) {
    switch (route.name) {
        case "home":
            return <Home tab={route.tab} />;
        case "movie":
            return <FilmPage item={route.item} />;
        case "show":
            return <ShowPage item={route.item} />;
        case "signin":
            return <SignIn purpose={route.purpose} />;
        case "manual":
            return <Manual />;
        case "log":
            return <LogScreen />;
    }
}

export function App() {
    const isReady = useStore(ready);
    const lists = useStore(playlists);
    const id = useStore(currentId);
    const stack = useStore(routes);
    const p = useStore(playback);
    const playing = p.state !== "idle";
    const using = lists.find((x) => x.id === id);

    const base = !lists.length ? <SignIn purpose="playlist" welcome /> : using && !using.gen ? <FirstLoad /> : screenFor(stack[0]);
    const screens = [base, ...stack.slice(1).map(screenFor)];
    const depth = screens.length + (playing ? 1 : 0);

    // Each layer remembers where the remote was; closing the one above puts it back there.
    const remembered = useRef<(HTMLElement | null)[]>([]);
    const last = useRef(depth);
    useLayoutEffect(() => {
        if (depth > last.current) remembered.current[last.current - 1] = document.activeElement as HTMLElement | null;
        else if (depth < last.current) {
            const el = remembered.current[depth - 1];
            if (el?.isConnected) focus(el);
        }
        last.current = depth;
        document.body.classList.toggle("playing", playing);
    }, [depth, playing]);

    if (!isReady)
        return (
            <div class="screen centre">
                <Spinner />
            </div>
        );
    return (
        <>
            {screens.map((s, i) => (
                <div
                    key={i}
                    class="layer"
                    data-layer={i === screens.length - 1 && !playing ? "" : undefined}
                    style={{ visibility: i === screens.length - 1 && !playing ? "visible" : "hidden" }}
                    aria-hidden={i === screens.length - 1 && !playing ? undefined : "true"}
                >
                    {s}
                </div>
            ))}
            {playing ? <Player /> : null}
        </>
    );
}
