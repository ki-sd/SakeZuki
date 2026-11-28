"use client";

import { createContext, useContext, useEffect, useLayoutEffect, useRef, useSyncExternalStore } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { authStore, bootstrap, type AuthState } from "@/app/api/auth";

const AuthContext = createContext<AuthState>({ status: "loading", member: null, generation: 0 });

export default function AuthProvider({ children }: { children: React.ReactNode }) {
    const auth = useSyncExternalStore(authStore.subscribe, authStore.snapshot, authStore.serverSnapshot);
    const cache = useQueryClient();
    useLayoutEffect(() => {
        let generation = authStore.snapshot().generation;
        return authStore.subscribe(() => {
            const current = authStore.snapshot().generation;
            if (generation === current) return;
            generation = current;
            const privateQueries = { predicate: ({ queryKey }: { queryKey: readonly unknown[] }) =>
                !["sakeList", "sakeDetail", "sakeSearch", "public-pairing-examples", "signup-policy"].includes(String(queryKey[0])) };
            void cache.cancelQueries(privateQueries);
            cache.removeQueries(privateQueries);
        });
    }, [cache]);
    useEffect(() => { void bootstrap(); }, []);
    return <AuthContext.Provider value={auth}>{children}</AuthContext.Provider>;
}

export function useAuth() { return useContext(AuthContext); }


export function useSessionGuard() {
    const mounted = useRef(false);
    useLayoutEffect(() => {
        mounted.current = true;
        return () => { mounted.current = false; };
    }, []);
    return () => {
        const generation = authStore.snapshot().generation;
        return () => mounted.current && generation === authStore.snapshot().generation;
    };
}
