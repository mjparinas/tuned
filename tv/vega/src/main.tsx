// The stick's wiring: Amazon's storage, player and random source behind the app's interfaces.
import { xhrHttp } from "./core/http";
import { makeApp } from "./App";
import { random } from "./platform/random";
import { deviceStore } from "./platform/store";
import { VegaPlayer } from "./platform/VegaPlayer";

const player = new VegaPlayer();

/** Background work starts once the first screen has had a few seconds to draw (QuietStart on Android). */
const quietStart = () => new Promise<void>((resolve) => setTimeout(resolve, 3000));

export const App = makeApp({ store: deviceStore, http: xhrHttp(), random, player, deviceName: "Fire TV (Vega OS)", quietStart }, player);
