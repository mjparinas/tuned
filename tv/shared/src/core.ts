// tv/core's Kotlin rules (the same ones Android runs), as TypeScript sees them.
import { Entry, M3uReader, TunedCore, Xtream } from "tuned-core";

export { Entry, M3uReader, Xtream };

/** Sorting, regions, search text, copies of a channel, the guide's half hours and the rest (tv/core TunedCore.kt). */
export const core = TunedCore.getInstance();
