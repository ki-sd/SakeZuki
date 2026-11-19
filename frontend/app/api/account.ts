import api from "./axios";
export type Account = {
    verified: boolean; balance: number; verificationAvailable: boolean;
    termsVersion: string; privacyVersion: string; termsUrl: string; privacyUrl: string;
    historyDays: number; initialGrant: number;
};
export type History = { id: string; kind: "SAKE" | "FOOD"; inputValue: string; completedAtEpoch: number; expiresAtEpoch: number };
export type Ledger = { id: number; referenceId: string; kind: string; delta: number; balanceAfter: number; createdAtEpoch: number };
export const getAccount = async () => (await api.get<Account>("/user/account")).data;
export const getHistory = async (page: number) => (await api.get<History[]>("/user/recommendations", { params: { page } })).data;
export const getLedger = async (page: number) => (await api.get<Ledger[]>("/user/credits/ledger", { params: { page } })).data;
export const getHistoryDetail = async (id: string) => (await api.get("/user/recommendations/" + encodeURIComponent(id))).data;
export const startVerification = async (account: Account) => (await api.post<{ requestId: string; redirectUrl: string }>(
    "/user/verification/start", { termsVersion: account.termsVersion, privacyVersion: account.privacyVersion })).data;
