const iso = /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2})(:\d{2})?(\.\d{1,9})?(Z|[+-]\d{2}:\d{2})?$/i;

export function parseSubmissionDate(value: string): number | null {
    const parts = iso.exec(value);
    if (!parts || (parts[3] && !parts[2])) return null;
    const wall = parts[1] + (parts[2] ?? ":00");
    const wallTime = Date.parse(`${wall}Z`);
    if (!Number.isFinite(wallTime) || new Date(wallTime).toISOString().slice(0,19) !== wall) return null;
    const time = Date.parse(wall + (parts[3] ?? "") + (parts[4] ?? "Z"));
    return Number.isFinite(time) ? time : null;
}

export function submissionDate(value: string) {
    const parsed = parseSubmissionDate(value);
    return parsed === null ? "시각 확인 불가" : new Date(parsed).toLocaleString("ko-KR", { timeZone: "Asia/Seoul" });
}
