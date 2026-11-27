// Settings on the TV: the Tuned account (Premium), playlists, Refresh now with its progress and how it ended, channel
// numbers, adult groups, the app log and signing out of everything. Every button answers at once (settings.md).
import { useState } from "preact/hooks";
import {
    account,
    canAddPlaylist,
    currentId,
    nameOf,
    playlists,
    refresh,
    refreshNow,
    removePlaylist,
    saveSettings,
    settings,
    signOutEverything,
    switchPlaylist,
    tunedAccount,
} from "../app.ts";
import { useStore } from "../state.ts";
import { ago, t } from "../strings.ts";
import { open } from "./nav.ts";
import { Button, Screen, Spinner } from "./parts.tsx";
import { StepLine } from "./loading.tsx";

function Toggle(props: { label: string; on: boolean; onChange: (on: boolean) => void }) {
    return (
        <button type="button" class="row toggle" data-focus role="switch" aria-checked={props.on} onClick={() => props.onChange(!props.on)}>
            <span>{props.label}</span>
            <span class={props.on ? "switch on" : "switch"}>{props.on ? t.on : t.off}</span>
        </button>
    );
}

export function Settings(props: { height: number }) {
    const acc = useStore(account);
    const lists = useStore(playlists);
    const using = useStore(currentId);
    const r = useStore(refresh);
    const s = useStore(settings);
    const [confirming, setConfirming] = useState(false);
    const [checking, setChecking] = useState(false);
    const usingList = lists.find((p) => p.id === using);
    return (
        <div class="settings" style={{ height: `${props.height}px` }}>
            <section>
                <h2>{t.account}</h2>
                {acc.signedIn ? (
                    <>
                        <p>
                            {t.accountSignedIn(acc.email)} · <strong>{acc.premium ? t.accountPremium : t.accountFree}</strong>
                        </p>
                        <div class="buttons" data-row>
                            <Button
                                label={t.refreshNow}
                                busy={checking}
                                onClick={() => {
                                    setChecking(true);
                                    void tunedAccount
                                        .refresh()
                                        .then((x) => account.set(x))
                                        .finally(() => setChecking(false));
                                }}
                            />
                        </div>
                    </>
                ) : (
                    <>
                        <p class="muted">{t.accountSignedOut}</p>
                        <div class="buttons" data-row>
                            <Button label={t.accountSignIn} onClick={() => open({ name: "signin", purpose: "account" })} />
                        </div>
                    </>
                )}
            </section>
            <section>
                <h2>{t.playlists}</h2>
                <div class="buttons" data-row>
                    <Button
                        start
                        kind="primary"
                        label={r.running ? t.refreshing : t.refreshNow}
                        busy={r.running}
                        onClick={() => void refreshNow()}
                    />
                </div>
                {r.running && r.step ? <StepLine step={r.step} /> : null}
                {!r.running && r.error ? (
                    <p class="error">
                        {t.refreshFailed} {t.loadFailed[r.error] ?? ""}
                    </p>
                ) : null}
                {!r.running && !r.error && usingList?.refreshedAt ? <p class="muted">{t.upToDate(ago(usingList.refreshedAt))}</p> : null}
                {lists.map((p) => (
                    <div class="row" key={p.id} data-row>
                        <span class="grow">{nameOf(p.login)}</span>
                        {p.id === using ? (
                            <span class="muted">{t.using}</span>
                        ) : (
                            <Button label={t.use} onClick={() => void switchPlaylist(p.id)} />
                        )}
                        <Button label={t.remove} onClick={() => void removePlaylist(p.id)} />
                    </div>
                ))}
                {canAddPlaylist() ? (
                    <div class="buttons" data-row>
                        <Button label={t.addPlaylist} onClick={() => open({ name: "signin", purpose: "playlist" })} />
                    </div>
                ) : (
                    <p class="muted">{t.premiumForMore}</p>
                )}
            </section>
            <section>
                <h2>{t.settings}</h2>
                <Toggle label={t.channelNumbers} on={s.channelNumbers} onChange={(on) => void saveSettings({ channelNumbers: on })} />
                <Toggle label={t.hideAdult} on={s.hideAdult} onChange={(on) => void saveSettings({ hideAdult: on })} />
                <button type="button" class="row" data-focus onClick={() => open({ name: "log" })}>
                    {t.log}
                </button>
                <button type="button" class="row danger" data-focus onClick={() => setConfirming(true)}>
                    {t.signOut}
                </button>
                <p class="muted small">
                    Tuned {typeof __VERSION__ === "string" ? __VERSION__ : ""} · {t.noContent}
                </p>
            </section>
            {confirming ? <ConfirmSignOut onClose={() => setConfirming(false)} /> : null}
        </div>
    );
}

function ConfirmSignOut(props: { onClose: () => void }) {
    const [busy, setBusy] = useState(false);
    return (
        <div class="dialog-backdrop">
            <Screen class="dialog" layer>
                <p>{t.signOutConfirm}</p>
                <div class="buttons" data-row>
                    <Button start label={t.cancel} onClick={props.onClose} />
                    <Button
                        label={t.signOut}
                        busy={busy}
                        onClick={() => {
                            setBusy(true);
                            void signOutEverything().finally(props.onClose);
                        }}
                    />
                </div>
                {busy ? <Spinner small /> : null}
            </Screen>
        </div>
    );
}
