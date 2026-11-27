// Stand-ins for @amazon-devices/react-native-kepler in the browser: the arrow keys and Enter act as the remote,
// Escape as Back.
import * as React from "react";
import { View } from "react-native";

export type HWEvent = { eventType: string; eventKeyAction?: number };

const KEYS: Record<string, string> = { ArrowUp: "up", ArrowDown: "down", ArrowLeft: "left", ArrowRight: "right", Enter: "select" };

export function useTVEventHandler(handle: (event: HWEvent) => void): void {
    const ref = React.useRef(handle);
    ref.current = handle;
    React.useEffect(() => {
        const listener = (e: KeyboardEvent) => {
            const type = KEYS[e.key] ?? (/^\d$/.test(e.key) ? `num_${e.key}` : null);
            if (type) ref.current({ eventType: type, eventKeyAction: 0 });
        };
        window.addEventListener("keydown", listener);
        return () => window.removeEventListener("keydown", listener);
    }, []);
}

export function useKeplerBackHandler() {
    return {
        exitApp: () => console.info("exitApp"),
        addEventListener: (_: string, handler: () => boolean | void) => {
            const listener = (e: KeyboardEvent) => e.key === "Escape" && handler();
            window.addEventListener("keydown", listener);
            return { remove: () => window.removeEventListener("keydown", listener) };
        },
    };
}

export const TVFocusGuideView = View;
