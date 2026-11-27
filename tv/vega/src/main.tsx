// The stick's wiring: Amazon's storage, player and random source behind the app's interfaces.
import type { Fetch } from "@tuned/shared/platform";
import { xhrHttp } from "./core/http";
import { deviceLanguages } from "./platform/language";
import { KEYS } from "./platform/keys";
import { chooseLanguage, locale } from "./ui/strings";
import { makeApp } from "./App";
import { random } from "./platform/random";
import { deviceStore } from "./platform/store";
import { VegaPlayer } from "./platform/VegaPlayer";

// Every line in the stick's language, before anything is drawn.
chooseLanguage(deviceLanguages());

const player = new VegaPlayer();

/** React Native's `fetch`, for the account and the relay (small JSON answers, so no streaming body is needed). */
const fetch: Fetch = (url, init) => globalThis.fetch(url, init as RequestInit);

/** Background work starts once the first screen has had a few seconds to draw (QuietStart on Android). */
const quietStart = () => new Promise<void>((resolve) => setTimeout(resolve, 3000));

export const App = makeApp(
    {
        store: deviceStore,
        http: xhrHttp(),
        fetch,
        random,
        player,
        deviceName: "Fire TV (Vega OS)",
        quietStart,
        tmdbKey: KEYS.tmdb,
        simklId: KEYS.simkl,
        language: locale(),
    },
    player,
);
