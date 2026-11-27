// Logins are kept encrypted until sign-out (security.md): AES-GCM under a key WebCrypto made on this TV and won't
// export, kept in IndexedDB. Without WebCrypto (an old harness) they're kept as they are, still never logged.

const DB = "tuned-keys";

function keyStore(): Promise<IDBDatabase | null> {
    return new Promise((resolve) => {
        try {
            const r = indexedDB.open(DB, 1);
            r.onupgradeneeded = () => r.result.createObjectStore("keys");
            r.onsuccess = () => resolve(r.result);
            r.onerror = () => resolve(null);
        } catch {
            resolve(null);
        }
    });
}

let key: Promise<CryptoKey | null> | null = null;

function theKey(): Promise<CryptoKey | null> {
    key ??= (async () => {
        const subtle = globalThis.crypto?.subtle;
        const db = await keyStore();
        if (!subtle || !db) return null;
        const kept = await new Promise<CryptoKey | undefined>((resolve) => {
            const r = db.transaction("keys").objectStore("keys").get("login");
            r.onsuccess = () => resolve(r.result as CryptoKey | undefined);
            r.onerror = () => resolve(undefined);
        });
        if (kept) return kept;
        const made = await subtle.generateKey({ name: "AES-GCM", length: 256 }, false, ["encrypt", "decrypt"]);
        await new Promise<void>((resolve) => {
            const tx = db.transaction("keys", "readwrite");
            tx.objectStore("keys").put(made, "login");
            tx.oncomplete = () => resolve();
            tx.onerror = () => resolve();
        });
        return made;
    })().catch(() => null);
    return key;
}

const toB64 = (bytes: Uint8Array) => btoa(String.fromCharCode(...bytes));
const fromB64 = (text: string) => Uint8Array.from(atob(text), (c) => c.charCodeAt(0));

export async function seal(text: string): Promise<string> {
    const k = await theKey();
    if (!k) return `plain:${text}`;
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const sealed = new Uint8Array(await crypto.subtle.encrypt({ name: "AES-GCM", iv }, k, new TextEncoder().encode(text)));
    return `v1:${toB64(iv)}:${toB64(sealed)}`;
}

export async function unseal(value: string | null): Promise<string | null> {
    if (!value) return null;
    if (value.startsWith("plain:")) return value.slice(6);
    const [version, iv, body] = value.split(":");
    const k = await theKey();
    if (version !== "v1" || !k || !iv || !body) return null;
    try {
        return new TextDecoder().decode(await crypto.subtle.decrypt({ name: "AES-GCM", iv: fromB64(iv) }, k, fromB64(body)));
    } catch {
        return null;
    }
}

/** Sign out forgets the key too, so nothing sealed with it can be read again. */
export async function forgetKey(): Promise<void> {
    key = null;
    const db = await keyStore();
    if (!db) return;
    await new Promise<void>((resolve) => {
        const tx = db.transaction("keys", "readwrite");
        tx.objectStore("keys").clear();
        tx.oncomplete = () => resolve();
        tx.onerror = () => resolve();
    });
}
