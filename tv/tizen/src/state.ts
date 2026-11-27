// A tiny shared state for the screens: a value, a way to change it, and a hook that redraws on change.
import { useEffect, useRef, useState } from "preact/hooks";

export interface Store<T> {
    get(): T;
    set(next: T | ((old: T) => T)): void;
    subscribe(listener: (value: T) => void): () => void;
}

export function store<T>(initial: T): Store<T> {
    let value = initial;
    const listeners = new Set<(value: T) => void>();
    return {
        get: () => value,
        set(next) {
            const v = typeof next === "function" ? (next as (old: T) => T)(value) : next;
            if (Object.is(v, value)) return;
            value = v;
            for (const l of [...listeners]) l(v);
        },
        subscribe(listener) {
            listeners.add(listener);
            return () => listeners.delete(listener);
        },
    };
}

/**
 * The store's value, redrawn when it changes. Preact runs effects after the frame is drawn, so a change between the
 * draw and the subscription would otherwise be missed (the app could sit on its spinner after `ready` was set):
 * subscribing checks the value the screen drew against the one now, and redraws if they differ.
 */
export function useStore<T>(s: Store<T>): T {
    const [, redraw] = useState(0);
    const value = s.get();
    const drawn = useRef(value);
    drawn.current = value;
    useEffect(() => {
        const stop = s.subscribe(() => redraw((n) => n + 1));
        if (!Object.is(s.get(), drawn.current)) redraw((n) => n + 1);
        return stop;
    }, [s]);
    return value;
}
