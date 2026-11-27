// The stick's own random source (Hermes has no crypto.getRandomValues), through Amazon's expo-crypto.
import { getRandomBytes } from "@amazon-devices/expo-crypto";

export function random(n: number): Uint8Array {
    const g = globalThis as { crypto?: { getRandomValues?: (a: Uint8Array) => Uint8Array } };
    if (g.crypto?.getRandomValues) return g.crypto.getRandomValues(new Uint8Array(n));
    return getRandomBytes(n);
}
