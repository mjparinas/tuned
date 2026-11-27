// Adding a playlist, and signing in to the Tuned account, with a phone: a code and its QR code on the TV, the phone
// sends the login (sealed so only this TV can open it) or signs the TV in (onboarding.md › TV sign-in). Typing a login
// with the remote is the other way in. Free is one playlist; more is Premium through the account (premium.md).
import { useEffect, useRef, useState } from "preact/hooks";
import { fromLink, m3uLogin, xtreamLogin, type SignInCode } from "@tuned/shared";
import { account, addPlaylist, canAddPlaylist, playlists, tunedAccount } from "../app.ts";
import { log } from "../log.ts";
import { t } from "../strings.ts";
import { back, open } from "./nav.ts";
import { Brand, Button, Qr, Screen, Spinner } from "./parts.tsx";

const POLL = 3000;

export function SignIn(props: { purpose: "playlist" | "account"; welcome?: boolean }) {
    const [code, setCode] = useState<SignInCode | null>(null);
    const [failed, setFailed] = useState(false);
    const [arrived, setArrived] = useState<string | null>(null);
    const [attempt, setAttempt] = useState(0);
    const [now, setNow] = useState(Date.now());
    const alive = useRef(true);

    useEffect(() => {
        alive.current = true;
        let timer: ReturnType<typeof setTimeout> | undefined;
        setCode(null);
        setFailed(false);
        const poll = async (c: SignInCode) => {
            if (!alive.current) return;
            const taken = await tunedAccount.take(c);
            if (!alive.current) return;
            if (taken.kind === "waiting" || taken.kind === "unreachable") {
                timer = setTimeout(() => void poll(c), POLL);
                return;
            }
            if (taken.kind === "gone") {
                setAttempt((a) => a + 1);
                return;
            }
            account.set(await tunedAccount.state());
            if (taken.kind === "signed in") {
                setArrived(t.signedInPremium);
                void tunedAccount.refresh().then((x) => account.set(x));
                if (!props.welcome) setTimeout(() => back(), 1200);
                return;
            }
            setArrived(t.arrived);
            // Free keeps one playlist; with Premium every one the phone sent is added.
            const wanted = account.get().premium ? taken.playlists : taken.playlists.slice(0, playlists.get().length ? 0 : 1);
            for (const login of wanted) await addPlaylist(login);
            if (!props.welcome) back();
        };
        void tunedAccount.newCode().then((c) => {
            if (!alive.current) return;
            if (!c) {
                setFailed(true);
                return;
            }
            setCode(c);
            void poll(c);
        });
        const tick = setInterval(() => setNow(Date.now()), 15_000);
        return () => {
            alive.current = false;
            clearTimeout(timer);
            clearInterval(tick);
        };
    }, [attempt]);

    const minutes = code ? Math.max(1, Math.ceil((code.expiresAt - now) / 60_000)) : 0;
    return (
        <Screen class="signin">
            <Brand />
            <div class="signin-body">
                <div class="signin-text">
                    <h1>{props.purpose === "account" ? t.accountSignIn : props.welcome ? t.welcome : t.signInTitle}</h1>
                    <p>{t.signInWhat}</p>
                    {arrived ? (
                        <p class="ok">
                            <Spinner small /> {arrived}
                        </p>
                    ) : failed ? (
                        <>
                            <p class="error">{t.relayOffline}</p>
                            <div class="buttons" data-row>
                                <Button start kind="primary" label={t.tryAgain} onClick={() => setAttempt((a) => a + 1)} />
                            </div>
                        </>
                    ) : code ? (
                        <>
                            <p>
                                {t.signInStep1} <strong>tunedplayer.com/tv</strong> {t.signInStep2}
                            </p>
                            <p class="code" aria-label={code.code.split("").join(" ")}>
                                {code.display}
                            </p>
                            <p class="muted">{t.signInDigits(code.digits)}</p>
                            <p class="muted">{t.codeExpiresIn(minutes)}</p>
                        </>
                    ) : (
                        <p>
                            <Spinner small /> {t.gettingCode}
                        </p>
                    )}
                    {props.purpose === "playlist" ? (
                        <div class="buttons" data-row>
                            <Button start={!failed} label={t.typeInstead} onClick={() => open({ name: "manual" })} />
                        </div>
                    ) : null}
                    <p class="muted small">{t.noContent}</p>
                </div>
                {code && !arrived ? <Qr text={code.link} size={420} /> : null}
            </div>
        </Screen>
    );
}

/** Typing an Xtream login or a playlist link with the remote and the TV's keyboard. */
export function Manual() {
    const [kind, setKind] = useState<"xtream" | "link">("xtream");
    const [fields, setFields] = useState({ server: "", username: "", password: "", url: "", guide: "" });
    const [problem, setProblem] = useState<string | null>(null);
    const [busy, setBusy] = useState(false);
    const field = (name: keyof typeof fields, label: string, type: "text" | "url" | "password" = "text") => (
        <label class="field-row">
            <span>{label}</span>
            <input
                class="field"
                data-focus
                type={type as "text"}
                autocomplete="off"
                autocapitalize="off"
                spellcheck={false}
                value={fields[name]}
                onInput={(e) => setFields({ ...fields, [name]: (e.currentTarget as HTMLInputElement).value })}
            />
        </label>
    );
    const submit = () => {
        const login =
            kind === "xtream"
                ? xtreamLogin(fields.server, fields.username, fields.password)
                : (fromLink(fields.url, fields.guide) ?? m3uLogin(fields.url, fields.guide));
        if (!login) {
            setProblem(kind === "xtream" ? t.fillXtream : t.fillLink);
            return;
        }
        if (!canAddPlaylist()) {
            setProblem(t.premiumForMore);
            return;
        }
        setBusy(true);
        void addPlaylist(login)
            .then(() => {
                // Back to where adding started: Settings, or the first screen, which now shows the download.
                back();
                if (playlists.get().length > 1) back();
            })
            .catch((error: unknown) => {
                log("Manual", String((error as Error)?.message ?? error));
                setBusy(false);
            });
    };
    return (
        <Screen class="manual">
            <Brand />
            <h1>{t.typeInstead}</h1>
            <div class="groups" data-row role="tablist">
                {(["xtream", "link"] as const).map((k) => (
                    <button
                        key={k}
                        type="button"
                        role="tab"
                        aria-selected={kind === k}
                        class={kind === k ? "chip picked" : "chip"}
                        data-focus
                        data-start={k === "xtream" ? "" : undefined}
                        onClick={() => setKind(k)}
                    >
                        {k === "xtream" ? t.xtream : t.link}
                    </button>
                ))}
            </div>
            {kind === "xtream" ? (
                <>
                    {field("server", t.server, "url")}
                    {field("username", t.username)}
                    {field("password", t.password, "password")}
                </>
            ) : (
                <>
                    {field("url", t.playlistUrl, "url")}
                    {field("guide", t.guideUrl, "url")}
                </>
            )}
            {problem ? <p class="error">{problem}</p> : null}
            <div class="buttons" data-row>
                <Button kind="primary" label={t.add} busy={busy} onClick={submit} />
                <Button label={t.back} onClick={() => back()} />
            </div>
        </Screen>
    );
}
