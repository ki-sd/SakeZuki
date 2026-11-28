import axios from "axios";
import api from "./axios";

export type Member = { memberNo: number; email: string; roles: string[] };
type Tokens = { accessToken: string; tokenType: string; expiresIn: number };
export type AuthState = { status: "loading" | "authenticated" | "anonymous"; member: Member | null; generation: number };
type Session = { generation: number; memberNo: number | null };
let state: AuthState = { status: "loading", member: null, generation: 0 };
let accessToken: string | null = null;
let sessionAbort = new AbortController();
let refreshRequest: { generation: number; promise: Promise<void> } | null = null;
let bootstrapRequest: Promise<void> | null = null;
let oauthRequest: { generation: number; promise: Promise<void> } | null = null;
let expiredFrom: number | null = null;
let localLock: Promise<unknown> = Promise.resolve();
const listeners = new Set<() => void>();
const transport = axios.create({ baseURL: "/api/auth", timeout: 10000, withCredentials: true });
const serverState = state;

export function captureSession(): Session {
    return { generation: state.generation, memberNo: state.member?.memberNo ?? null };
}

export function requireSession(session: Session) {
    if (session.generation !== state.generation || session.memberNo !== (state.member?.memberNo ?? null))
        throw new SessionEnded();
}

export class SessionEnded extends Error {
    constructor() { super("인증 세션이 변경되었습니다."); }
}

declare module "axios" {
    interface AxiosRequestConfig {
        _authSession?: Session;
        _authRetried?: boolean;
        _authCallerSignal?: AbortSignal;
        _authAnonymous?: boolean;
        _authRelease?: () => void;
    }
}

function publish(member: Member | null, generation = state.generation) {
    state = { status: member ? "authenticated" : "anonymous", member, generation };
    listeners.forEach(listener => listener());
}

function endSession(expired = false) {
    expiredFrom = expired ? state.generation : null;
    accessToken = null;
    const previous = sessionAbort;
    sessionAbort = new AbortController();
    publish(null, state.generation + 1);
    previous.abort();
    return state.generation;
}

function requireGeneration(generation: number) {
    if (generation !== state.generation) throw new SessionEnded();
}

export const authStore = {
    subscribe(listener: () => void) { listeners.add(listener); return () => { listeners.delete(listener); }; },
    snapshot: () => state,
    serverSnapshot: () => serverState,
};

async function locked<T>(operation: () => Promise<T>): Promise<T> {
    if (typeof navigator !== "undefined" && navigator.locks)
        return navigator.locks.request("sakezuki-auth", operation);
    const next = localLock.then(operation, operation);
    localLock = next.catch(() => undefined);
    return next;
}

// Keep the cookie-changing transport under the lock even if its UI session is invalidated.
async function post<T>(path: string, data?: unknown, check?: () => void): Promise<T> {
    check?.();
    const csrf = (await transport.get<{ token: string; headerName: string }>("/csrf")).data;
    check?.();
    return (await transport.post<T>(path, data, { headers: { [csrf.headerName]: csrf.token } })).data;
}

async function accept(tokens: Tokens, session: Session) {
    requireGeneration(session.generation);
    const candidate = tokens.accessToken;
    const member = (await transport.get<Member>("/api/user/me", {
        baseURL: "/", headers: { Authorization: `Bearer ${candidate}` },
    })).data;
    requireGeneration(session.generation);
    if (!Number.isSafeInteger(member.memberNo) || member.memberNo < 1 ||
        (session.memberNo !== null && session.memberNo !== member.memberNo)) throw new SessionEnded();
    accessToken = candidate;
    publish(member);
}

export function refresh(session = captureSession()): Promise<void> {
    requireSession(session);
    if (refreshRequest?.generation === session.generation) return refreshRequest.promise;
    const promise = locked(async () => {
        try {
            await accept(await post<Tokens>("/refresh", undefined, () => requireSession(session)), session);
        } catch (error) {
            if (state.generation === session.generation) endSession(!(error instanceof SessionEnded));
            throw error;
        }
    }).finally(() => {
        if (refreshRequest?.promise === promise) refreshRequest = null;
    });
    refreshRequest = { generation: session.generation, promise };
    return promise;
}

export function bootstrap(): Promise<void> {
    if (!bootstrapRequest) {
        if (state.generation !== 0 || ["/oauth/callback", "/signup/social"].includes(window.location.pathname)) {
            if (state.status === "loading") publish(null);
            bootstrapRequest = Promise.resolve();
        } else bootstrapRequest = refresh().catch(() => undefined);
    }
    return bootstrapRequest;
}

function authenticate(path: string, data?: unknown) {
    const generation = endSession();
    const session = captureSession();
    return locked(async () => {
        await accept(await post<Tokens>(path, data, () => requireGeneration(generation)), session);
    });
}

export function login(email: string, password: string) {
    return authenticate("/login", { email, password });
}

export function completeOAuth() {
    if (oauthRequest?.generation === state.generation) return oauthRequest.promise;
    const promise = authenticate("/social/token");
    oauthRequest = { generation: state.generation, promise };
    return promise;
}

export function socialSignup(data: { address1: string; address2: string; phone: string; termsVersion: string; privacyVersion: string }) {
    return authenticate("/social/signup", data);
}

export function logout() {
    endSession();
    return locked(async () => { await post("/logout"); });
}

export async function signupInfo() {
    const session = captureSession();
    const { data } = await transport.get<{ provider: string; email: string; name: string }>("/social/signup-info");
    requireSession(session);
    return data;
}
export async function signupPolicy() {
    return (await transport.get<{ termsVersion: string; privacyVersion: string; termsUrl: string; privacyUrl: string; enabled: boolean }>("/signup-policy")).data;
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
    // 인코딩된 구분자와 점 경로까지 정규화해 로그인 복귀 루프·외부 이동 해석 차이를 줄인다.
    if (/%(?:2f|5c|00|0a|0d)/i.test(value)) return "/sake";
    try {
        const url = new URL(value, "https://sakezuki.invalid");
        const path = decodeURIComponent(url.pathname).replace(/\/+$/, "");
        if (url.origin !== "https://sakezuki.invalid" || ["/login", "/oauth/callback", "/signup/social"].includes(path)) return "/sake";
        return url.pathname + url.search + url.hash;
    } catch { return "/sake"; }
}

export function finishDestination() {
    const result = safeReturnTo(sessionStorage.getItem("authReturnTo"));
    sessionStorage.removeItem("authReturnTo");
    return result;
}

function publicRead(config: { method?: string; url?: string }) {
    return config.method === "get" &&
        /^\/(?:sake\/(?:list|search|[0-9]+)|examples\/recommendations(?:\/[a-zA-Z0-9-]+)?)$/.test(config.url ?? "");
}

function requireOrigin(config: import("axios").AxiosRequestConfig) {
    const origin = config._authSession;
    if (!origin || origin.generation !== state.generation || config.signal?.aborted || config._authCallerSignal?.aborted) throw new SessionEnded();
    // An anonymous public read may finish after initial session restoration in the same generation.
    if (origin.memberNo !== null || !publicRead(config)) requireSession(origin);
}

function bindRequestSignal(config: import("axios").AxiosRequestConfig) {
    config._authRelease?.();
    config._authRelease = undefined;
    const caller = config._authCallerSignal;
    const session = sessionAbort.signal;
    if (!caller) return session;
    if (typeof AbortSignal.any === "function") return AbortSignal.any([caller,session]);

    const merged = new AbortController();
    const release = () => {
        caller.removeEventListener("abort",abort);
        session.removeEventListener("abort",abort);
    };
    const abort = () => { release(); merged.abort(); };
    if (caller.aborted || session.aborted) abort();
    else {
        caller.addEventListener("abort",abort,{ once: true });
        session.addEventListener("abort",abort,{ once: true });
        config._authRelease = release;
    }
    return merged.signal;
}

api.interceptors.request.use(config => {
    if (!config._authSession) {
        config._authSession = captureSession();
        config._authCallerSignal = config.signal as AbortSignal | undefined;
    }
    requireOrigin(config);
    config.signal = bindRequestSignal(config);
    if (accessToken && config._authSession.memberNo !== null && !config._authAnonymous)
        config.headers.set("Authorization", `Bearer ${accessToken}`);
    else config.headers.delete("Authorization");
    return config;
}, error => { throw error; }, { synchronous: true });

api.interceptors.response.use(response => {
    response.config._authRelease?.();
    requireOrigin(response.config);
    return response;
}, async error => {
    const config = error.config as import("axios").InternalAxiosRequestConfig | undefined;
    if (!config) throw error;
    config._authRelease?.();
    requireOrigin(config);
    const origin = config._authSession!;
    const bearer = config.headers.get("Authorization");
    if (axios.isCancel(error) || error.response?.status !== 401 || !bearer || config._authRetried) throw error;
    config._authRetried = true;
    if (bearer === `Bearer ${accessToken}`) {
        try { await refresh(origin); }
        catch (refreshError) {
            if (expiredFrom === origin.generation && state.generation === origin.generation + 1 &&
                !config._authCallerSignal?.aborted && publicRead(config)) {
                return api({ ...config, signal: config._authCallerSignal, _authSession: captureSession(), _authAnonymous: true });
            }
            throw refreshError;
        }
    }
    requireOrigin(config);
    if (!accessToken) throw error;
    return api(config);
});
