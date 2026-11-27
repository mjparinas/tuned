// What Hermes lacks and Amazon's media library supplies (docs/decisions/vega.md). Imported first, from index.js.
import { TextDecoder as VegaTextDecoder } from "@amazon-devices/react-native-w3cmedia";

const g = globalThis as Record<string, unknown>;
if (typeof g.TextDecoder === "undefined" && VegaTextDecoder) g.TextDecoder = VegaTextDecoder;
