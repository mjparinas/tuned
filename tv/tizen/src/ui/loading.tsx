// The first download of a playlist: each step named and numbered, with real bytes of the full size (or bytes so far
// when the size isn't known) and, once it can tell, about how long is left (AGENTS.md › Waiting is never a mystery).
// A failure says why in words, with Try again and Change login.
import { useEffect, useRef, useState } from "preact/hooks";
import { core, type Step } from "@tuned/shared";
import { current, refresh, refreshNow, removePlaylist } from "../app.ts";
import { useStore } from "../state.ts";
import { bytes, t } from "../strings.ts";
import { Bar, Brand, Button, Screen, Spinner } from "./parts.tsx";

export function StepLine(props: { step: Step }) {
    const started = useRef({ id: "", at: Date.now() });
    if (started.current.id !== `${props.step.id}${props.step.number}`)
        started.current = { id: `${props.step.id}${props.step.number}`, at: Date.now() };
    const [now, setNow] = useState(Date.now());
    useEffect(() => {
        const id = setInterval(() => setNow(Date.now()), 1000);
        return () => clearInterval(id);
    }, []);
    const p = props.step.progress;
    const eta = p && p.total > 0 ? core.remaining(p.done, p.total, started.current.at, now) : null;
    const amount = p ? (p.total > 0 ? `${bytes(p.done)} of ${bytes(p.total)}` : bytes(p.done)) : "";
    return (
        <div class="step">
            <div class="step-head">
                <span class="muted">{t.stepOf(props.step.number, props.step.count)}</span>
                <strong>{t.steps[props.step.id] ?? ""}</strong>
            </div>
            <Bar done={p?.done ?? 0} total={p?.total ?? 0} />
            <div class="muted">{[amount, eta ? t.left(eta) : ""].filter(Boolean).join(" · ")}</div>
        </div>
    );
}

export function FirstLoad() {
    const r = useStore(refresh);
    // A first download that never started (the app closed during it) starts again.
    useEffect(() => {
        if (!refresh.get().running && !refresh.get().error) void refreshNow();
    }, []);
    return (
        <Screen class="centre">
            <Brand />
            <h1>{t.loadingTitle}</h1>
            {r.error ? (
                <>
                    <p class="error">{t.loadFailed[r.error] ?? t.loadFailed.unreachable}</p>
                    <div class="buttons" data-row>
                        <Button start kind="primary" label={t.tryAgain} onClick={() => void refreshNow()} />
                        <Button
                            label={t.changeLogin}
                            onClick={() => {
                                const p = current();
                                if (p) void removePlaylist(p.id);
                            }}
                        />
                    </div>
                </>
            ) : r.step ? (
                <StepLine step={r.step} />
            ) : (
                <Spinner />
            )}
            <p class="muted small">{t.noContent}</p>
        </Screen>
    );
}
