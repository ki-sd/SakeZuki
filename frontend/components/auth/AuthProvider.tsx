"use client";

import { createContext, useContext, useEffect, useSyncExternalStore } from "react";
import { authStore, bootstrap, type AuthState } from "@/app/api/auth";

const AuthContext = createContext<AuthState>({ status: "loading", member: null });

export default function AuthProvider({ children }: { children: React.ReactNode }) {
    const auth = useSyncExternalStore(authStore.subscribe, authStore.snapshot, authStore.serverSnapshot);
    useEffect(() => { void bootstrap(); }, []);
    return <AuthContext.Provider value={auth}>{children}</AuthContext.Provider>;
}

export function useAuth() { return useContext(AuthContext); }
