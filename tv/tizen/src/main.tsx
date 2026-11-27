// Tuned for Samsung TVs. Starts from what's kept on the TV (nothing waits on the network), then hands every key
// press to the screen on top: its own handler first, then moving focus, OK, and Back.
import { render } from "preact";
import { start } from "./app.ts";
import { focus, focusables, keepFocus, move, remoteMoved } from "./focus.ts";
import { isTyping, readKey } from "./keys.ts";
import { log } from "./log.ts";
import { language, rightToLeft } from "./strings.ts";
import { AvPlayPlayer } from "./player/avplay.ts";
import { connections, playback, playerEvents, usePlayer } from "./player/controller.ts";
import { VideoPlayer } from "./player/html5.ts";
import { exitApp, onTv, registerKeys } from "./tizen.ts";
import { App } from "./ui/app.tsx";
import { back, handleKey } from "./ui/nav.ts";
import "./styles.css";

// The picture: on a TV, Samsung's AVPlay draws on its own plane behind the page, where this <object> sits; in a
// desktop browser (development and the e2e test) a <video> stands in.
const surface = document.getElementById("surface") as HTMLElement;
const av = window.webapis?.avplay;
if (onTv() && av) {
    const object = document.createElement("object");
    object.type = "application/avplayer";
    object.className = "surface";
    surface.appendChild(object);
    usePlayer(new AvPlayPlayer(av, playerEvents));
} else {
    const video = document.createElement("video");
    video.className = "surface";
    video.playsInline = true;
    surface.appendChild(video);
    usePlayer(new VideoPlayer(video, playerEvents));
}

registerKeys();

document.addEventListener("keydown", (event) => {
    const typing = isTyping(event.target);
    const press = readKey(event);
    // In a text box the keys type; up and down move on, and Back puts the TV's keyboard away.
    if (typing) {
        if (press.action === "back") {
            event.preventDefault();
            (event.target as HTMLElement).blur();
            focus(event.target as HTMLElement);
            return;
        }
        if (press.action !== "up" && press.action !== "down") return;
    }
    if (press.action === null && press.digit === null) return;
    event.preventDefault();
    remoteMoved();
    try {
        if (handleKey(press)) return;
        switch (press.action) {
            case "up":
            case "down":
            case "left":
            case "right":
                move(press.action);
                break;
            case "ok": {
                const el = document.activeElement as HTMLElement | null;
                if (el && focusables().includes(el)) el.click();
                else focus(focusables()[0]);
                break;
            }
            case "back":
                if (!back()) exitApp();
                break;
            default:
        }
    } catch (error) {
        log("Keys", String((error as Error)?.message ?? error));
    }
});

// For the e2e test (test/e2e.mjs): how many stream connections were ever open at once.
(window as unknown as { __tuned: unknown }).__tuned = { connections, playback: () => playback.get() };

// The TV's language, and Arabic mirrored, as on Android (docs/decisions/languages.md).
document.documentElement.lang = language;
document.documentElement.dir = rightToLeft ? "rtl" : "ltr";

const root = document.getElementById("app") as HTMLElement;
render(<App />, root);
keepFocus(root);
void start();
