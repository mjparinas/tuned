// Settings › Trakt and Simkl (docs/decisions/trakt.md, simkl.md): connect each with a code typed on a phone, as on
// Android, turn scrobbling off without disconnecting, or disconnect. The code dialog asks the service every few
// seconds, slower when told to, until the code is entered, refused or runs out, and always says which.
import { useEffect, useState } from "preact/hooks";
import type { SignInCode } from "@tuned/shared/trakt";
import { focus } from "../focus.ts";
import { log } from "../log.ts";
import { readServices, services, serviceStates, type Service } from "../services.ts";
import { useStore } from "../state.ts";
import { t } from "../strings.ts";
import { useKeys } from "./nav.ts";
import { Button, Screen, Spinner } from "./parts.tsx";

export function Services() {
    const states = useStore(serviceStates);
    const [signingIn, setSigningIn] = useState<Service | null>(null);
    const [busy, setBusy] = useState<string | null>(null);
    if (!services.length) return null;
    return (
        <section>
            <h2>{t.servicesTitle}</h2>
            <p class="muted">{t.servicesAbout}</p>
            {services.map((s) => {
                const state = states[s.name];
                if (!state?.signedIn)
                    return (
                        <div class="buttons" data-row data-service={s.name} key={s.name}>
                            <Button label={t.serviceConnect(s.name)} onClick={() => setSigningIn(s)} />
                        </div>
                    );
                return (
                    <div key={s.name} data-service={s.name}>
                        <div class="row" data-row>
                            <span class="grow">{state.user ? t.serviceSignedInAs(s.name, state.user) : t.serviceSignedIn(s.name)}</span>
                            <Button
                                label={t.disconnect}
                                busy={busy === s.name}
                                onClick={() => {
                                    setBusy(s.name);
                                    void s.client
                                        .signOut()
                                        .catch((e: unknown) => log(s.name, `Disconnect failed: ${String(e)}`))
                                        .then(readServices)
                                        .finally(() => setBusy(null));
                                }}
                            />
                        </div>
                        <button
                            type="button"
                            class="row toggle"
                            data-focus
                            role="switch"
                            aria-checked={state.scrobble}
                            onClick={() => void s.client.setScrobble(!state.scrobble).then(readServices)}
                        >
                            <span>
                                {t.scrobble} · {s.name}
                            </span>
                            <span class={state.scrobble ? "switch on" : "switch"}>{state.scrobble ? t.on : t.off}</span>
                        </button>
                    </div>
                );
            })}
            {signingIn ? (
                <ServiceSignIn
                    service={signingIn}
                    onClose={() => {
                        const name = signingIn.name;
                        setSigningIn(null);
                        // The remote goes back to the service it was on, connected or not.
                        requestAnimationFrame(() => {
                            const back = document.querySelector<HTMLElement>(`[data-service="${name}"] [data-focus]`);
                            if (back) focus(back);
                        });
                    }}
                />
            ) : null}
        </section>
    );
}

type Stage =
    | { kind: "getting" }
    | { kind: "code"; code: SignInCode }
    | { kind: "expired" | "denied" | "offline" }
    | { kind: "done"; user: string | null };

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

function ServiceSignIn(props: { service: Service; onClose: () => void }) {
    const { service } = props;
    const [stage, setStage] = useState<Stage>({ kind: "getting" });
    const [attempt, setAttempt] = useState(0);

    useKeys((press) => {
        if (press.action !== "back") return false;
        props.onClose();
        return true;
    });

    useEffect(() => {
        let closed = false;
        setStage({ kind: "getting" });
        void (async () => {
            try {
                const code = await service.client.startSignIn();
                if (closed) return;
                if (!code) return setStage({ kind: "offline" });
                setStage({ kind: "code", code });
                let wait = code.intervalMs;
                while (!closed) {
                    await sleep(wait);
                    if (closed) return;
                    const answer = await service.client.pollSignIn(code);
                    if (closed) return;
                    if (answer === "waiting") continue;
                    if (answer === "slow down") {
                        wait = service.slowDown(wait);
                        continue;
                    }
                    if (answer === "expired" || answer === "denied") return setStage({ kind: answer });
                    await readServices();
                    return setStage({ kind: "done", user: answer.user });
                }
            } catch (error) {
                log(service.name, `Sign-in failed: ${String((error as Error)?.message ?? error)}`);
                if (!closed) setStage({ kind: "offline" });
            }
        })();
        return () => {
            closed = true;
        };
    }, [attempt]);

    const again = <Button start kind="primary" label={t.tryAgain} onClick={() => setAttempt((a) => a + 1)} />;
    return (
        <div class="dialog-backdrop">
            <Screen class="dialog service" layer key={`${stage.kind}-${attempt}`}>
                <h2>{t.serviceConnect(service.name)}</h2>
                {stage.kind === "getting" ? (
                    <p>
                        <Spinner small /> {t.gettingCode}
                    </p>
                ) : stage.kind === "code" ? (
                    <>
                        <p>{t.serviceGo(stage.code.verificationUrl.replace(/^https?:\/\//, ""))}</p>
                        <p class="code" aria-label={stage.code.userCode.split("").join(" ")}>
                            {stage.code.userCode}
                        </p>
                        <p class="muted">
                            <Spinner small /> {t.serviceWaiting}
                        </p>
                    </>
                ) : stage.kind === "done" ? (
                    <p>{stage.user ? t.serviceSignedInAs(service.name, stage.user) : t.serviceSignedIn(service.name)}</p>
                ) : (
                    <p class="error">
                        {stage.kind === "expired"
                            ? t.serviceExpired
                            : stage.kind === "denied"
                              ? t.serviceDenied(service.name)
                              : t.serviceOffline(service.name)}
                    </p>
                )}
                <div class="buttons" data-row>
                    {stage.kind === "expired" || stage.kind === "denied" || stage.kind === "offline" ? again : null}
                    <Button
                        start={stage.kind !== "expired" && stage.kind !== "denied" && stage.kind !== "offline"}
                        kind={stage.kind === "done" ? "primary" : "plain"}
                        label={stage.kind === "done" ? t.done : t.cancel}
                        onClick={props.onClose}
                    />
                </div>
            </Screen>
        </div>
    );
}
