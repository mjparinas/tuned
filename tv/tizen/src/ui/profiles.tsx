// "Who's watching?" (profiles.md): the account's profiles in a row, the remote on the first, as on Android TV. A
// profile with its own PIN asks for it; leaving a kids profile for a grown-up one asks for the parental PIN. Profiles
// are added and changed on the phone or the website; the TV shows them.
import { useState } from "preact/hooks";
import { focus } from "../focus.ts";
import { PinThrottle, avatarGlyph, lockFor, profileGradient, verifyPin, type Profile } from "@tuned/shared/profiles";
import { currentProfile, pbkdf2, profiles, useProfile } from "../app.ts";
import { log } from "../log.ts";
import { useStore } from "../state.ts";
import { t } from "../strings.ts";
import { back, setTab, useKeys } from "./nav.ts";
import { Brand, Button, Screen, Spinner } from "./parts.tsx";

/** One guard for every PIN on the TV, so switching between profile and parental PINs doesn't reset it. */
export const pinGuard = new PinThrottle();

export function Avatar(props: { profile: Profile; size: number }) {
    const [from, to] = profileGradient(props.profile);
    const glyph = avatarGlyph(props.profile);
    return (
        <span
            class="avatar"
            aria-hidden="true"
            style={{
                width: `${props.size}px`,
                height: `${props.size}px`,
                fontSize: `${Math.round((props.size * (glyph ? 55 : 45)) / 100)}px`,
                background: `linear-gradient(135deg, ${from}, ${to})`,
            }}
        >
            {glyph ?? (props.profile.name.trim()[0] ?? "?").toUpperCase()}
        </span>
    );
}

export function WhoIsWatching(props: { closable?: boolean }) {
    const { profiles: list, parentalPin } = useStore(profiles);
    const [asking, setAsking] = useState<{ profile: Profile; lock: "profile" | "parental" } | null>(null);
    const using = currentProfile();
    const pick = (p: Profile) => {
        const lock = p.id === using?.id ? "none" : lockFor(using, p, parentalPin);
        if (lock === "none") return void done(p);
        setAsking({ profile: p, lock });
    };
    const done = (p: Profile) => {
        void useProfile(p.id);
        // A new profile starts on Live TV, as opening the app does.
        if (props.closable) back();
        setTab("live");
    };
    return (
        <Screen class="profiles centre">
            <Brand />
            <h1>{t.whosWatching}</h1>
            <div class="profile-row" data-row>
                {list.map((p, i) => (
                    <button
                        key={p.id}
                        type="button"
                        class={p.id === using?.id ? "profile picked" : "profile"}
                        data-focus
                        data-start={(using ? p.id === using.id : i === 0) ? "" : undefined}
                        aria-label={[p.name, p.kids ? t.kidsProfile : "", p.pinHash ? t.pinLocked : ""].filter(Boolean).join(", ")}
                        onClick={() => pick(p)}
                    >
                        <Avatar profile={p} size={168} />
                        <span class="name">{p.name}</span>
                        <span class="muted small">
                            {[p.kids ? t.kidsProfile : "", p.pinHash ? `🔒 ${t.pinLocked}` : ""].filter(Boolean).join(" · ") || " "}
                        </span>
                    </button>
                ))}
            </div>
            <p class="muted">{t.profilesOnPhone}</p>
            {asking ? (
                <PinPad
                    title={asking.lock === "parental" ? t.parentalPinFor(asking.profile.name) : t.pinFor(asking.profile.name)}
                    hash={asking.lock === "parental" ? parentalPin : asking.profile.pinHash}
                    onCancel={() => setAsking(null)}
                    onOk={() => {
                        setAsking(null);
                        done(asking.profile);
                    }}
                />
            ) : null}
        </Screen>
    );
}

/**
 * Four to eight digits, typed with the remote's number keys or the pad on screen, checked against the hash the phone
 * made. Five wrong tries wait 30 seconds (`PinThrottle`). Checking says so while it runs: 120,000 rounds take a moment.
 */
export function PinPad(props: { title: string; hash: string | null; onOk: () => void; onCancel: () => void }) {
    const [pin, setPin] = useState("");
    const [busy, setBusy] = useState(false);
    const [problem, setProblem] = useState<string | null>(null);

    const check = async (entered: string) => {
        if (busy || entered.length < 4) return;
        if (pinGuard.waiting()) return void setProblem(t.pinWait);
        setBusy(true);
        const ok = await verifyPin(pbkdf2, entered, props.hash).catch((error: unknown) => {
            log("Profiles", String((error as Error)?.message ?? error));
            return false;
        });
        setBusy(false);
        const answer = pinGuard.record(ok);
        if (answer === "ok") return props.onOk();
        setPin("");
        setProblem(answer === "wait" ? t.pinWait : t.pinWrong);
    };
    const type = (d: number) => {
        if (busy || pin.length >= 8) return;
        setProblem(null);
        setPin(pin + d);
    };

    useKeys((press) => {
        if (press.digit !== null) {
            type(press.digit);
            // Typed with the number keys, the remote moves to OK once there are enough digits, so OK checks it.
            if (pin.length + 1 >= 4)
                requestAnimationFrame(() => {
                    const ok = document.getElementById("pin-ok");
                    if (ok) focus(ok);
                });
            return true;
        }
        if (press.action === "back") {
            props.onCancel();
            return true;
        }
        return false;
    });

    return (
        <div class="dialog-backdrop">
            <Screen class="dialog pin" layer>
                <h2>{props.title}</h2>
                <div class="pin-dots" aria-label={t.pinTyped(pin.length)}>
                    {Array.from({ length: Math.max(4, pin.length) }, (_, i) => (
                        <span key={i} class={i < pin.length ? "dot on" : "dot"} />
                    ))}
                </div>
                <p class={problem ? "error" : "muted"} aria-live="polite">
                    {busy ? (
                        <>
                            <Spinner small /> {t.pinChecking}
                        </>
                    ) : (
                        (problem ?? t.pinHint)
                    )}
                </p>
                <div class="pin-keys">
                    {[1, 2, 3, 4, 5, 6, 7, 8, 9].map((d) => (
                        <Button key={d} start={d === 1} label={String(d)} onClick={() => type(d)} />
                    ))}
                    <Button label="⌫" ariaLabel={t.pinDelete} onClick={() => setPin(pin.slice(0, -1))} />
                    <Button label="0" onClick={() => type(0)} />
                    <Button id="pin-ok" kind="primary" label={t.ok} busy={busy} disabled={pin.length < 4} onClick={() => void check(pin)} />
                </div>
                <div class="buttons" data-row>
                    <Button label={t.cancel} onClick={props.onCancel} />
                </div>
            </Screen>
        </div>
    );
}
