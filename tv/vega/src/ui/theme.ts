// Tuned's dark TV look (docs/decisions/branding.md): the app's Material 3 dark colours, the accent outline on whatever
// the remote is on, and the overscan margin (12 dp sides, 16 dp top and bottom; docs/decisions/tv.md). Sizes are in
// Android TV's 960 × 540 dp and scaled to the screen, so the stick and the preview look the same.
import { useWindowDimensions } from "react-native";

export const colours = {
    bg: "#121018",
    glow: "rgba(109, 74, 255, 0.22)",
    card: "#1e1c25",
    card2: "#29262f",
    line: "#34313a",
    text: "#e6e1ea",
    muted: "#cac4d0",
    primary: "#c9beff",
    onPrimary: "#2b10a0",
    primarySoft: "#4325d6",
    onPrimarySoft: "#e6deff",
    violet: "#6d4aff",
    teal: "#5fdcc3",
    danger: "#ffb4ab",
    focus: "#c9beff",
    scrim: "rgba(14, 11, 26, 0.78)",
} as const;

/** dp to the screen's units: 1 dp is 1/960 of the width. */
export function useDp(): (dp: number) => number {
    const { width } = useWindowDimensions();
    const scale = (width || 960) / 960;
    return (dp: number) => Math.round(dp * scale * 100) / 100;
}

export const SAFE = { side: 12, top: 16 } as const;
