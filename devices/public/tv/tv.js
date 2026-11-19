// The phone page for signing in on a TV (docs/decisions/onboarding.md). It gets the TV's key from the QR link (or
// from the relay, checked with four digits), seals the login with seal.js and posts only the sealed box.
import { b64, checkDigits, normalizeCode, pack, parseFragment, seal, unb64 } from "/tv/seal.js";

const $ = (id) => document.getElementById(id);
const status = $("status");
let target = null;
let kind = "xtream";

const messages = {
    "bad code": "That doesn't look like the code on your TV. It's eight letters and numbers.",
    "not found": "That code has run out. Your TV shows a new one: scan it again.",
    "already sent": "Something was already sent to this TV. If it didn't arrive, scan the new code on your TV.",
    "too many": "Too many tries. Try again in an hour.",
    "bad request": "That didn't work. Scan the code on your TV again.",
    unavailable: "Something went wrong on our side. Try again in a moment, or type it on the TV.",
};

async function ask(path, body) {
    try {
        const response = await fetch(path, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(body),
        });
        return await response.json();
    } catch {
        return { status: "unavailable" };
    }
}

function show(id) {
    for (const card of ["enter", "confirm", "login"]) $(card).hidden = card !== id;
}

function ready(found) {
    target = found;
    show("login");
    // The website signs the TV in to the person's Tuned account, which restores its backup (docs/decisions/accounts.md).
    $("useAccount").href = `/account?tv=${encodeURIComponent(found.code)}`;
    // The app sends everything (playlists, settings, favourites), so offer it on Android phones.
    if (/Android/i.test(navigator.userAgent)) {
        $("openApp").href = `tuned://tv#${found.code}.${b64(found.key)}`;
        $("openApp").hidden = false;
    }
}

function pickKind(next) {
    kind = next;
    $("tabXtream").setAttribute("aria-selected", String(kind === "xtream"));
    $("tabLink").setAttribute("aria-selected", String(kind === "link"));
    $("xtream").hidden = kind !== "xtream";
    $("link").hidden = kind !== "link";
}

const withScheme = (value) => (/^[a-z]+:\/\//i.test(value) ? value : `http://${value}`);

/** A pasted get.php (or player_api.php) link is an Xtream login: fill in the three boxes from it, as the app does. */
function readXtream(value) {
    try {
        const url = new URL(withScheme(value.trim()));
        const file = url.pathname.split("/").pop().toLowerCase();
        if (!["get.php", "player_api.php", "xmltv.php", "panel_api.php"].includes(file)) return false;
        const username = url.searchParams.get("username");
        const password = url.searchParams.get("password");
        if (!username || !password) return false;
        $("server").value = url.origin + url.pathname.slice(0, url.pathname.lastIndexOf("/"));
        $("username").value = username;
        $("password").value = password;
        pickKind("xtream");
        return true;
    } catch {
        return false;
    }
}

function playlist() {
    const name = $("name").value.trim();
    if (kind === "xtream") {
        const server = $("server").value.trim().replace(/\/+$/, "");
        const username = $("username").value.trim();
        const password = $("password").value;
        if (!server || !username || !password) return null;
        return { server: withScheme(server), username, password, name };
    }
    const link = $("playlist").value.trim();
    if (!link) return null;
    return { server: "", username: "", password: "", playlistUrl: withScheme(link), guideUrl: $("guide").value.trim(), name };
}

async function start() {
    const found = parseFragment(location.hash);
    if (found) {
        // The key came straight from the TV through the QR code, so there's nothing to check.
        ready(found);
        history.replaceState(null, "", location.pathname);
        return;
    }
    show("enter");
}

$("codeForm").onsubmit = async (event) => {
    event.preventDefault();
    const code = normalizeCode($("code").value);
    if (!code) {
        status.textContent = messages["bad code"];
        return;
    }
    status.textContent = "Finding your TV…";
    const answer = await ask("/api/tv/key", { code });
    if (answer.status !== "ok") {
        status.textContent = messages[answer.status] ?? messages.unavailable;
        return;
    }
    status.textContent = "";
    const key = unb64(answer.key);
    $("digits").textContent = await checkDigits(key);
    show("confirm");
    $("matches").onclick = () => ready({ code, key });
};

$("tabXtream").onclick = () => pickKind("xtream");
$("tabLink").onclick = () => pickKind("link");
for (const id of ["server", "playlist"]) $(id).addEventListener("input", (event) => readXtream(event.target.value));

$("send").onsubmit = async (event) => {
    event.preventDefault();
    const login = playlist();
    if (!login) {
        status.textContent = kind === "xtream" ? "Fill in the server, username and password." : "Fill in the playlist link.";
        return;
    }
    $("sendButton").disabled = true;
    status.textContent = "Sending…";
    try {
        const plain = pack(JSON.stringify({ version: 1, playlists: [login] }));
        const box = b64(await seal(plain, target.code, target.key));
        const answer = await ask("/api/tv/send", { code: target.code, box });
        if (answer.status === "ok") {
            status.textContent = "Sent. Check your TV.";
            $("login").hidden = true;
            return;
        }
        status.textContent = messages[answer.status] ?? messages.unavailable;
    } catch {
        // A key that isn't a real one fails to import here.
        status.textContent = messages["bad request"];
    }
    $("sendButton").disabled = false;
};

start();
