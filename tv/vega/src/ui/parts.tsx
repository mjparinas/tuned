// The pieces every screen shares: the focusable button and row with the accent outline (docs/decisions/tv.md), the
// brand in the upper left, the screen frame inside the overscan margin with its glow filling the whole screen, the
// spinner with its line, the progress bar, and a code that never wraps (AGENTS.md: OneLineCode).
import * as React from "react";
import {
    ActivityIndicator,
    Image,
    Pressable,
    StyleSheet,
    Text,
    View,
    type PressableProps,
    type StyleProp,
    type ViewStyle,
} from "react-native";
import MARK from "../../assets/brand.png";
import { colours, SAFE, useDp } from "./theme";

/** The violet glow in the upper left, as on Android's TV screens; a gradient, so it fades rather than ending in an edge. */
const GLOW = {
    experimental_backgroundImage: `radial-gradient(circle at 12% 0%, ${colours.glow} 0%, transparent 55%)`,
    backgroundImage: `radial-gradient(circle at 12% 0%, ${colours.glow} 0%, transparent 55%)`,
} as unknown as ViewStyle;

export function Screen({ children, glow = true }: { children: React.ReactNode; glow?: boolean }) {
    const dp = useDp();
    return (
        <View style={[styles.fill, { backgroundColor: colours.bg }]}>
            {glow ? <View pointerEvents="none" style={[StyleSheet.absoluteFill, GLOW]} /> : null}
            <View style={[styles.fill, { paddingHorizontal: dp(SAFE.side + 24), paddingVertical: dp(SAFE.top + 12) }]}>{children}</View>
        </View>
    );
}

export function Brand() {
    const dp = useDp();
    return (
        <View style={[styles.row, { gap: dp(10) }]} accessibilityRole="header" accessibilityLabel="Tuned">
            <Image source={MARK} style={{ width: dp(28), height: dp(28), borderRadius: dp(7) }} />
            <Text style={{ color: colours.text, fontSize: dp(18), fontWeight: "600" }}>Tuned</Text>
        </View>
    );
}

type FocusableProps = Omit<PressableProps, "style" | "children"> & {
    style?: StyleProp<ViewStyle>;
    focusedStyle?: StyleProp<ViewStyle>;
    children: (focused: boolean) => React.ReactNode;
    /** The remote starts here (TvFocus on Android). */
    start?: boolean;
    /** Shown focused without a remote, for the preview's screenshots. */
    showFocused?: boolean;
};

export const Focusable = React.forwardRef<View, FocusableProps>(function Focusable(
    { style, focusedStyle, children, start, showFocused, onFocus, onBlur, ...rest },
    ref,
) {
    const dp = useDp();
    const [focused, setFocused] = React.useState(false);
    const on = focused || !!showFocused;
    return (
        <Pressable
            ref={ref}
            focusable
            hasTVPreferredFocus={start}
            onFocus={(e) => {
                setFocused(true);
                onFocus?.(e);
            }}
            onBlur={(e) => {
                setFocused(false);
                onBlur?.(e);
            }}
            style={[
                { borderRadius: dp(12), borderWidth: dp(2), borderColor: "transparent" },
                style,
                on ? [{ borderColor: colours.focus, backgroundColor: colours.card2 }, focusedStyle] : null,
            ]}
            {...rest}
        >
            {children(on)}
        </Pressable>
    );
});

/** A full, round button, as on Android: filled for the likely choice, outlined for the rest. */
export function Button({
    label,
    onPress,
    filled,
    start,
    busy,
    showFocused,
}: {
    label: string;
    onPress: () => void;
    filled?: boolean;
    start?: boolean;
    busy?: boolean;
    showFocused?: boolean;
}) {
    const dp = useDp();
    return (
        <Focusable
            start={start}
            showFocused={showFocused}
            onPress={busy ? undefined : onPress}
            accessibilityRole="button"
            accessibilityLabel={label}
            accessibilityState={{ busy: !!busy }}
            style={{
                minHeight: dp(44),
                paddingHorizontal: dp(22),
                borderRadius: dp(22),
                justifyContent: "center",
                alignItems: "center",
                flexDirection: "row",
                gap: dp(10),
                backgroundColor: filled ? colours.primary : colours.card2,
            }}
            focusedStyle={{ backgroundColor: filled ? "#ffffff" : colours.line }}
        >
            {() => (
                <>
                    {busy ? <ActivityIndicator size="small" color={filled ? colours.onPrimary : colours.primary} /> : null}
                    <Text style={{ color: filled ? colours.onPrimary : colours.text, fontSize: dp(15), fontWeight: "600" }}>{label}</Text>
                </>
            )}
        </Focusable>
    );
}

export function Spinner({ line }: { line?: string }) {
    const dp = useDp();
    return (
        <View style={[styles.row, { gap: dp(12) }]} accessibilityLiveRegion="polite">
            <ActivityIndicator size="small" color={colours.primary} />
            {line ? <Text style={{ color: colours.muted, fontSize: dp(14) }}>{line}</Text> : null}
        </View>
    );
}

/** A bar for a known share (0 to 1), or a short moving stripe when it isn't known. */
export function ProgressBar({ share }: { share: number | null }) {
    const dp = useDp();
    return (
        <View
            style={{ height: dp(6), borderRadius: dp(3), backgroundColor: colours.card2, overflow: "hidden" }}
            accessibilityRole="progressbar"
        >
            <View
                style={{
                    height: "100%",
                    width: share === null ? "18%" : `${Math.round(Math.min(1, Math.max(0, share)) * 100)}%`,
                    backgroundColor: colours.primary,
                    borderRadius: dp(3),
                }}
            />
        </View>
    );
}

/** A code to type elsewhere: one line, never wrapped; it shrinks to fit instead (Melvin, 2026-11-20). */
export function OneLineCode({ code, size }: { code: string; size: number }) {
    return (
        <Text
            numberOfLines={1}
            adjustsFontSizeToFit
            style={{ color: colours.text, fontSize: size, fontWeight: "700", letterSpacing: size * 0.08, fontVariant: ["tabular-nums"] }}
        >
            {code}
        </Text>
    );
}

export const styles = StyleSheet.create({
    fill: { flex: 1 },
    row: { flexDirection: "row", alignItems: "center" },
});
