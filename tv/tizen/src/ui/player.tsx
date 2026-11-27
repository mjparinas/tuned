// The player: only the picture until a key is pressed (player.md › get out of the way). OK or Info shows the banner
// (channel, what's on now and next, the time) and the top row's buttons; they hide again after a few seconds. Live:
// up and down or channel up and down change channel, number keys type one, the flip-back key returns to the last.
// Movies: left and right skip 10 seconds, play/pause pauses. Back hides the controls, then stops and closes the stream.
import { useEffect, useRef, useState } from "preact/hooks";
import type { Programme } from "@tuned/shared";
import { kindItems, nowNext, settings, visibleItems } from "../app.ts";
import { focus } from "../focus.ts";
import { dial, flipBack, playback, position, retry, seekBy, selectTrack, step, stop, togglePause, tracks } from "../player/controller.ts";
import { useStore } from "../state.ts";
import { clock, t, timeOfDay } from "../strings.ts";
import { useKeys } from "./nav.ts";
import { Bar, Button, Spinner } from "./parts.tsx";

const HIDE_AFTER = 5000;

export function Player() {
    const p = useStore(playback);
    const numbers = useStore(settings).channelNumbers;
    const [shown, setShown] = useState(true);
    const hideTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
    const [on, setOn] = useState<{ now: Programme | null; next: Programme | null }>({ now: null, next: null });
    const [, tick] = useState(0);
    const root = useRef<HTMLDivElement>(null);

    const show = () => {
        setShown(true);
        clearTimeout(hideTimer.current);
        hideTimer.current = setTimeout(() => setShown(false), HIDE_AFTER);
    };
    useEffect(() => {
        show();
        return () => clearTimeout(hideTimer.current);
    }, [p.item?.id]);
    useEffect(() => {
        let live = true;
        if (p.item?.kind === "live") void nowNext(p.item).then((x) => live && setOn(x));
        else setOn({ now: null, next: null });
        return () => {
            live = false;
        };
    }, [p.item?.id]);
    useEffect(() => {
        const id = setInterval(() => tick((n) => n + 1), 1000);
        return () => clearInterval(id);
    }, []);
    // A failure keeps its message and buttons up, with the remote on Try again.
    useEffect(() => {
        if (p.state === "failed") {
            clearTimeout(hideTimer.current);
            setShown(true);
            requestAnimationFrame(() => focus(root.current?.querySelector<HTMLElement>("[data-start]")));
        }
    }, [p.state]);

    useKeys((press) => {
        const live = p.item?.kind === "live";
        if (press.digit !== null) {
            if (live) void kindItems("live").then((all) => dial(press.digit as number, visibleItems(all)));
            show();
            return true;
        }
        switch (press.action) {
            case "back":
                if (shown && p.state === "playing") {
                    setShown(false);
                    clearTimeout(hideTimer.current);
                } else void stop();
                return true;
            case "stop":
                void stop();
                return true;
            case "channelUp":
            case "channelDown":
                step(press.action === "channelUp" ? 1 : -1);
                return true;
            case "previousChannel":
                flipBack();
                return true;
            case "info":
                if (shown) setShown(false);
                else show();
                return true;
            case "playPause":
            case "play":
            case "pause":
                togglePause();
                show();
                return true;
            case "forward":
            case "rewind":
                seekBy(press.action === "forward" ? 30_000 : -10_000);
                show();
                return true;
            case "up":
            case "down":
                if (!shown && live) {
                    step(press.action === "up" ? 1 : -1);
                    return true;
                }
                show();
                return false;
            case "left":
            case "right":
                if (!shown && !live) {
                    seekBy(press.action === "right" ? 10_000 : -10_000);
                    show();
                    return true;
                }
                show();
                return false;
            case "ok":
                if (!shown) {
                    show();
                    return true;
                }
                show();
                return false;
            default:
                return false;
        }
    });

    if (!p.item) return null;
    const item = p.item;
    const busy = p.state === "opening" || p.buffering;
    const { at, length } = position();
    return (
        <div ref={root} class={shown ? "player shown" : "player"} data-layer>
            {busy ? (
                <div class="player-spinner" aria-label={t.opening}>
                    <Spinner />
                </div>
            ) : null}
            {p.dial ? <div class="dial">{p.dial}</div> : null}
            {p.state === "failed" ? (
                <div class="player-failed">
                    <p>{t.playFailed}</p>
                    <div class="buttons" data-row>
                        <Button start kind="primary" label={t.tryAgain} onClick={() => void retry()} />
                        <Button label={t.back} onClick={() => void stop()} />
                    </div>
                </div>
            ) : null}
            {shown && p.state !== "failed" ? (
                <>
                    <TopRow />
                    <div class="banner">
                        <div class="banner-head">
                            <h2>{item.kind === "live" ? t.channelLine(numbers ? item.number : 0, item.name) : item.name}</h2>
                            <span class="time">{timeOfDay(Date.now())}</span>
                        </div>
                        {item.kind === "live" ? (
                            <>
                                {on.now ? (
                                    <div class="programme">
                                        <span class="when">
                                            {t.nowOn} · {timeOfDay(on.now.start)} – {timeOfDay(on.now.stop)}
                                        </span>{" "}
                                        <strong>{on.now.title}</strong>
                                        <Bar done={Date.now() - on.now.start} total={on.now.stop - on.now.start} />
                                    </div>
                                ) : (
                                    <p class="muted">{t.noGuide}</p>
                                )}
                                {on.next ? (
                                    <div class="programme muted">
                                        {t.nextOn} · {timeOfDay(on.next.start)} {on.next.title}
                                    </div>
                                ) : null}
                            </>
                        ) : (
                            <div class="seek">
                                <span>{clock(at)}</span>
                                <Bar done={at} total={length} />
                                <span>{length ? clock(length) : ""}</span>
                                {p.paused ? <span aria-label="Paused">❚❚</span> : null}
                            </div>
                        )}
                    </div>
                </>
            ) : null}
        </div>
    );
}

/** Audio and captions as buttons in the top row, never behind a menu (Melvin, 2026-11-19). */
function TopRow() {
    const list = tracks();
    const audio = list.filter((x) => x.type === "AUDIO");
    const text = list.filter((x) => x.type === "TEXT");
    const [audioAt, setAudioAt] = useState(0);
    const [textAt, setTextAt] = useState(-1);
    if (audio.length < 2 && !text.length) return null;
    return (
        <div class="top-row" data-row>
            {audio.length > 1 ? (
                <Button
                    label={`${t.audio}: ${audio[audioAt]?.label || t.track(audioAt)}`}
                    onClick={() => {
                        const next = (audioAt + 1) % audio.length;
                        setAudioAt(next);
                        selectTrack("AUDIO", audio[next].index);
                    }}
                />
            ) : null}
            {text.length ? (
                <Button
                    label={`${t.captions}: ${textAt < 0 ? t.captionsOff : text[textAt]?.label || t.track(textAt)}`}
                    onClick={() => {
                        const next = textAt + 1 >= text.length ? -1 : textAt + 1;
                        setTextAt(next);
                        selectTrack("TEXT", next >= 0 ? text[next].index : -1);
                    }}
                />
            ) : null}
        </div>
    );
}
