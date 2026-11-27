// The player (docs/decisions/player.md): only video until a key. OK shows the channel, now and next, which hide
// themselves after a few seconds; up and down change channel while they're hidden; number keys jump to a channel;
// Back hides them, then closes the player. A movie or episode shows its name and where it is instead: OK pauses and
// plays, left and right skip 10 seconds back and forward, and where it was left is kept. A stream that won't start
// says so in words, with Try again.
import { KeplerVideoSurfaceView } from "@amazon-devices/react-native-w3cmedia";
import { useTVEventHandler, type HWEvent } from "@amazon-devices/react-native-kepler";
import * as React from "react";
import { Text, View } from "react-native";
import type { AppModel, State } from "../app/model";
import type { PlaybackState, VegaPlayer } from "../platform/VegaPlayer";
import { Button, ProgressBar, Spinner } from "./parts";
import { t } from "./strings";
import { colours, SAFE, useDp } from "./theme";
import { clockTime } from "../core/progress";

const HIDE_MS = 5000;
const DIGITS_MS = 1500;
const SKIP_S = 10;
const KEEP_MS = 15_000;

const clock = (ms: number) => new Date(ms).toLocaleTimeString("en-CA", { hour: "numeric", minute: "2-digit" });

export interface PlayerHandle {
    /** Back: true when it was used to hide the controls, so the player stays open. */
    back(): boolean;
}

export const PlayerScreen = React.forwardRef<
    PlayerHandle,
    {
        model: AppModel;
        state: State;
        player: VegaPlayer;
        shown?: { controls?: boolean; playback?: PlaybackState; position?: { at: number; length: number }; paused?: boolean };
    }
>(function PlayerScreen({ model, state, player, shown }, ref) {
    const dp = useDp();
    const [playback, setPlayback] = React.useState<PlaybackState>(shown?.playback ?? "starting");
    const [controls, setControls] = React.useState(!!shown?.controls);
    const [digits, setDigits] = React.useState("");
    const hideTimer = React.useRef<ReturnType<typeof setTimeout> | null>(null);
    const digitTimer = React.useRef<ReturnType<typeof setTimeout> | null>(null);
    const vod = state.vod;
    const channel = vod ? null : (state.lineup?.channels.find((c) => c.id === state.channelId) ?? null);
    const guide = channel ? (state.guide[channel.id] ?? []) : [];
    const [position, setPosition] = React.useState(shown?.position ?? { at: 0, length: 0 });
    const [paused, setPaused] = React.useState(!!shown?.paused);

    // A movie's time moves while its controls show, and where it is gets kept every few seconds.
    React.useEffect(() => {
        if (shown || !vod) return;
        const tick = setInterval(() => setPosition(player.position()), 1000);
        const keep = setInterval(() => void model.rememberPosition().catch(() => undefined), KEEP_MS);
        return () => {
            clearInterval(tick);
            clearInterval(keep);
        };
    }, [player, model, vod, shown]);

    React.useEffect(() => {
        if (shown) return;
        player.onState = setPlayback;
        return () => {
            player.onState = () => {};
        };
    }, [player, shown]);

    const showControls = React.useCallback(() => {
        setControls(true);
        if (hideTimer.current) clearTimeout(hideTimer.current);
        hideTimer.current = setTimeout(() => setControls(false), HIDE_MS);
    }, []);

    React.useEffect(
        () => () => {
            if (hideTimer.current) clearTimeout(hideTimer.current);
            if (digitTimer.current) clearTimeout(digitTimer.current);
        },
        [],
    );

    React.useImperativeHandle(ref, () => ({
        back() {
            if (!controls) return false;
            setControls(false);
            return true;
        },
    }));

    useTVEventHandler((event: HWEvent) => {
        if (shown || event.eventKeyAction === 1) return;
        const type = event.eventType;
        if (vod) {
            if (type === "select" || type === "play_pause" || type === "playpause") {
                if (controls || type !== "select") setPaused(player.togglePause());
                showControls();
            } else if (type === "left" || type === "rewind") {
                player.seekBy(-SKIP_S);
                showControls();
            } else if (type === "right" || type === "fast_forward") {
                player.seekBy(SKIP_S);
                showControls();
            } else if (type === "up" || type === "down" || type === "menu" || type === "info") showControls();
            return;
        }
        if (/^num_\d$/.test(type)) {
            const next = (digits + type.slice(4)).slice(-4);
            setDigits(next);
            if (digitTimer.current) clearTimeout(digitTimer.current);
            digitTimer.current = setTimeout(() => {
                setDigits("");
                const found = state.lineup?.channels.find((c) => c.number === Number(next));
                if (found) void model.play(found);
            }, DIGITS_MS);
            return;
        }
        if (!controls && (type === "up" || type === "channel_up")) return void model.zap(-1);
        if (!controls && (type === "down" || type === "channel_down")) return void model.zap(1);
        if (type === "select" || type === "menu" || type === "info" || type === "left" || type === "right") showControls();
    });

    return (
        <View style={{ flex: 1, backgroundColor: "#000" }}>
            <KeplerVideoSurfaceView
                style={{ position: "absolute", top: 0, left: 0, right: 0, bottom: 0 }}
                onSurfaceViewCreated={(handle: string) => player.setSurface(handle)}
                onSurfaceViewDestroyed={() => player.setSurface(null)}
            />
            {playback === "starting" || playback === "buffering" ? (
                <View
                    style={{ position: "absolute", top: 0, left: 0, right: 0, bottom: 0, alignItems: "center", justifyContent: "center" }}
                >
                    <Spinner line={playback === "starting" ? t.starting : t.buffering} />
                </View>
            ) : null}
            {playback === "failed" ? (
                <View
                    style={{
                        position: "absolute",
                        top: 0,
                        left: 0,
                        right: 0,
                        bottom: 0,
                        alignItems: "center",
                        justifyContent: "center",
                        backgroundColor: colours.scrim,
                        gap: dp(16),
                        padding: dp(48),
                    }}
                >
                    <Text style={{ color: colours.text, fontSize: dp(18), textAlign: "center", maxWidth: dp(520), lineHeight: dp(26) }}>
                        {vod ? t.movieDidntStart : t.didntStart}
                    </Text>
                    <View style={{ flexDirection: "row", gap: dp(12) }}>
                        <Button
                            label={t.tryAgain}
                            filled
                            start
                            onPress={() =>
                                vod ? void model.playVod(vod, state.positions[vod.id] ?? 0) : channel && void model.play(channel)
                            }
                        />
                        <Button label={t.back} onPress={() => void model.stopPlaying()} />
                    </View>
                </View>
            ) : null}
            {digits ? (
                <Text
                    style={{
                        position: "absolute",
                        top: dp(SAFE.top + 12),
                        right: dp(SAFE.side + 24),
                        color: colours.text,
                        fontSize: dp(40),
                        fontWeight: "700",
                        fontVariant: ["tabular-nums"],
                    }}
                >
                    {digits}
                </Text>
            ) : null}
            {controls && channel ? (
                <View
                    style={{
                        position: "absolute",
                        left: 0,
                        right: 0,
                        bottom: 0,
                        paddingHorizontal: dp(SAFE.side + 24),
                        paddingBottom: dp(SAFE.top + 16),
                        paddingTop: dp(28),
                        backgroundColor: colours.scrim,
                        gap: dp(8),
                    }}
                >
                    <View style={{ flexDirection: "row", alignItems: "baseline", gap: dp(14) }}>
                        <Text style={{ color: colours.muted, fontSize: dp(18), fontVariant: ["tabular-nums"] }}>{channel.number}</Text>
                        <Text numberOfLines={1} style={{ color: colours.text, fontSize: dp(24), fontWeight: "600", flexShrink: 1 }}>
                            {channel.name}
                        </Text>
                    </View>
                    {guide.slice(0, 2).map((p, i) => (
                        <Text
                            key={`${p.start}`}
                            numberOfLines={1}
                            style={{ color: i === 0 ? colours.text : colours.muted, fontSize: dp(15) }}
                        >
                            {`${i === 0 ? t.now : t.next}  ${clock(p.start)}–${clock(p.end)}  ${p.title}`}
                        </Text>
                    ))}
                </View>
            ) : null}
            {controls && vod ? (
                <View
                    style={{
                        position: "absolute",
                        left: 0,
                        right: 0,
                        bottom: 0,
                        paddingHorizontal: dp(SAFE.side + 24),
                        paddingBottom: dp(SAFE.top + 16),
                        paddingTop: dp(28),
                        backgroundColor: colours.scrim,
                        gap: dp(10),
                    }}
                >
                    <View style={{ flexDirection: "row", alignItems: "baseline", gap: dp(14) }}>
                        <Text numberOfLines={1} style={{ color: colours.text, fontSize: dp(24), fontWeight: "600", flexShrink: 1 }}>
                            {vod.title}
                        </Text>
                        {paused ? <Text style={{ color: colours.primary, fontSize: dp(15), fontWeight: "600" }}>{t.paused}</Text> : null}
                    </View>
                    {vod.subtitle ? (
                        <Text numberOfLines={1} style={{ color: colours.muted, fontSize: dp(15) }}>
                            {vod.subtitle}
                        </Text>
                    ) : null}
                    {position.length > 0 ? (
                        <View style={{ flexDirection: "row", alignItems: "center", gap: dp(14) }}>
                            <Text style={{ color: colours.text, fontSize: dp(14), fontVariant: ["tabular-nums"] }}>
                                {clockTime(position.at)}
                            </Text>
                            <View style={{ flex: 1 }}>
                                <ProgressBar share={position.at / position.length} />
                            </View>
                            <Text style={{ color: colours.muted, fontSize: dp(14), fontVariant: ["tabular-nums"] }}>
                                {clockTime(position.length)}
                            </Text>
                        </View>
                    ) : null}
                </View>
            ) : null}
        </View>
    );
});
