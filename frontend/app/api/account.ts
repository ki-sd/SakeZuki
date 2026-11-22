import api from "./axios";
export type Account = {
    verified: boolean; balance: number; verificationAvailable: boolean;
    termsVersion: string; privacyVersion: string; termsUrl: string; privacyUrl: string;
    historyDays: number; initialGrant: number;
};
export type History = { id: string; kind: "SAKE" | "FOOD"; inputValue: string; inputLabel: string; completedAtEpoch: number; expiresAtEpoch: number };
export type Ledger = { id: number; referenceId: string; kind: string; delta: number; balanceAfter: number; createdAtEpoch: number };
export const getAccount = async () => (await api.get<Account>("/user/account")).data;
export const getHistory = async (page: number) => (await api.get<History[]>("/user/recommendations", { params: { page } })).data;
export const getLedger = async (page: number) => (await api.get<Ledger[]>("/user/credits/ledger", { params: { page } })).data;
export const getHistoryDetail = async (id: string) => (await api.get("/user/recommendations/" + encodeURIComponent(id))).data;
export const getHistoryReceipt = async (id: string) => {
    const response = await api.get<import("@/types/pairing").Snapshot>("/user/recommendations/" + encodeURIComponent(id));
    return { result: response.data, completedAt: Number(response.headers["x-recommendation-completed-at"]), expiresAt: Number(response.headers["x-recommendation-expires-at"]) };
};
export const matchHistory = async (condition: import("@/types/pairing").RecommendationInput) =>
    (await api.post<{ match: History | null }>("/user/recommendations/match", condition)).data.match;
export const startVerification = async (account: Account) => (await api.post<{ requestId: string; redirectUrl: string }>(
    "/user/verification/start", { termsVersion: account.termsVersion, privacyVersion: account.privacyVersion })).data;
