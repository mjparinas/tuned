// Stand-ins for Amazon's W3C media player in the browser: the "video" is a still frame, so screenshots show the
// player's controls over a picture without a real stream.
import * as React from "react";
import { View } from "react-native";

export class VideoPlayer {
    autoplay = false;
    src = "";
    currentTime = 0;
    async initialize() {}
    async deinitialize() {}
    setSurfaceHandle() {}
    clearSurfaceHandle() {}
    addEventListener() {}
    pause() {}
    play() {}
}

export function KeplerVideoSurfaceView(props: { style?: object }) {
    return (
        <View
            style={[
                props.style,
                {
                    backgroundImage:
                        "radial-gradient(ellipse at 30% 40%, #3a5b7a 0%, transparent 55%), radial-gradient(ellipse at 75% 65%, #7a4a2e 0%, transparent 50%), linear-gradient(160deg, #1d2a38 0%, #0b0f14 100%)",
                } as object,
            ]}
        />
    );
}

export const TextDecoder = globalThis.TextDecoder;
