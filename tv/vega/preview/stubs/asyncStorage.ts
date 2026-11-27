const map = new Map<string, string>();
export default {
    getItem: async (k: string) => map.get(k) ?? null,
    setItem: async (k: string, v: string) => void map.set(k, v),
    multiRemove: async (keys: string[]) => keys.forEach((k) => map.delete(k)),
    getAllKeys: async () => [...map.keys()],
};
