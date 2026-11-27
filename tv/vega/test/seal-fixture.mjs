// Seals a payload with the phone page's own seal.js, so the stick's opener is tested against the real sender.
// Usage: node seal-fixture.mjs <code> <tv key, base64url> <json>  → prints the box in base64url.
import { b64, pack, seal, unb64 } from "../../../devices/public/tv/seal.js";

const [code, key, json] = process.argv.slice(2);
process.stdout.write(b64(await seal(pack(json), code, unb64(key))));
