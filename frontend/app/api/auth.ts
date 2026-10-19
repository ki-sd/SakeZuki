import axios from "axios";
import api from "./axios";

export type Member = { memberNo: number; email: string; roles: string[] };
type Tokens = { accessToken: string; tokenType: string; expiresIn: number };
export type AuthState = { status: "loading" | "authenticated" | "anonymous"; member: Member | null };
let state: AuthState = { status: "loading", member: null };
let accessToken: string | null = null;
let refreshRequest: Promise<void> | null = null;
let bootstrapRequest: Promise<void> | null = null;
let oauthRequest: Promise<void> | null = null;
const listeners = new Set<() => void>();
const transport = axios.create({ baseURL: "/api/auth", timeout: 10000, withCredentials: true });
const serverState: AuthState = { status: "loading", member: null };

function publish(member: Member | null) {
    state = { status: member ? "authenticated" : "anonymous", member };
    listeners.forEach(listener => listener());
}

export const authStore = {
    subscribe(listener: () => void) { listeners.add(listener); return () => { listeners.delete(listener); }; },
    snapshot: () => state,
    serverSnapshot: () => serverState,
};

async function locked<T>(operation: () => Promise<T>): Promise<T> {
    // Serialize cookie rotation across tabs as well as within this page.
    if (typeof navigator !== "undefined" && navigator.locks)
        return navigator.locks.request("sakezuki-auth", operation);
    return operation();
}

async function post<T>(path: string, data?: unknown): Promise<T> {
    const csrf = (await transport.get<{ token: string; headerName: string }>("/csrf")).data;
    return (await transport.post<T>(path, data, { headers: { [csrf.headerName]: csrf.token } })).data;
}

async function accept(tokens: Tokens) {
    accessToken = tokens.accessToken;
    try {
        const member = (await transport.get<Member>("/api/user/me", { baseURL: "/", headers: { Authorization: `Bearer ${accessToken}` } })).data;
        publish(member);
    } catch (error) { accessToken = null; publish(null); throw error; }
}

export function refresh(): Promise<void> {
    if (!refreshRequest) {
        refreshRequest = locked(async () => {
            try { await accept(await post<Tokens>("/refresh")); }
            catch (error) { accessToken = null; publish(null); throw error; }
        }).finally(() => { refreshRequest = null; });
    }
    return refreshRequest;
}

export function bootstrap(): Promise<void> {
    if (!bootstrapRequest) {
        // A refresh would discard the pending OAuth handshake session on these pages.
        if (["/oauth/callback", "/signup/social"].includes(window.location.pathname)) {
            publish(null);
            bootstrapRequest = Promise.resolve();
        } else bootstrapRequest = refresh().catch(() => undefined);
    }
    return bootstrapRequest;
}

export async function login(email: string, password: string) {
    await locked(async () => { await accept(await post<Tokens>("/login", { email, password })); });
}

export function completeOAuth() {
    // React Strict Mode mounts effects twice; exchange the one-time state only once.
    if (!oauthRequest) oauthRequest = locked(async () => { await accept(await post<Tokens>("/social/token")); });
    return oauthRequest;
}

export async function socialSignup(data: { address1: string; address2: string; phone: string }) {
    await locked(async () => { await accept(await post<Tokens>("/social/signup", data)); });
}

export async function logout() {
    await locked(async () => {
        await post("/logout");
        accessToken = null;
        publish(null);
    });
}

export async function signupInfo() {
    return (await transport.get<{ provider: string; email: string; name: string }>("/social/signup-info")).data;
}

export function errorMessage(error: unknown) {
    if (axios.isAxiosError(error)) {
        if (typeof error.response?.data === "string") return error.response.data;
        if (typeof error.response?.data?.message === "string") return error.response.data.message;
        if (!error.response) return "서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.";
    }
    return "요청을 처리하지 못했습니다. 다시 시도해 주세요.";
}

export function safeReturnTo(value: string | null) {
    if (!value || !value.startsWith("/") || value.startsWith("//") || /[\\\s]/.test(value)) return "/sake";
    const path = value.split(/[?#]/)[0];
    if (["/login", "/oauth/callback", "/signup/social"].includes(path)) return "/sake";
    return value;
}

export function finishDestination() {
    const result = safeReturnTo(sessionStorage.getItem("authReturnTo"));
    sessionStorage.removeItem("authReturnTo");
    return result;
}

api.interceptors.request.use(config => {
    if (accessToken) config.headers.set("Authorization", `Bearer ${accessToken}`);
    return config;
});

api.interceptors.response.use(response => response, async error => {
    const config = error.config;
    if (!config || error.response?.status !== 401 || !config.headers?.Authorization || config._authRetried) return Promise.reject(error);
    config._authRetried = true;
    // Other requests may already have renewed the token while this response was in flight.
    if (config.headers.Authorization === `Bearer ${accessToken}`) {
        try { await refresh(); }
        catch (refreshError) {
            // These APIs are public; an expired login must not prevent browsing or pairing.
            if (/^\/(sake|recommend)(\/|$)/.test(config.url ?? "")) {
                delete config.headers.Authorization;
                return api(config);
            }
            return Promise.reject(refreshError);
        }
    }
    if (!accessToken) return Promise.reject(error);
    config.headers.Authorization = `Bearer ${accessToken}`;
    return api(config);
});
