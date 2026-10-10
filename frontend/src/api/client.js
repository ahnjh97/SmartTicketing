import { getAccessToken, expireSession } from "../auth/session.js";

export const API_BASE_URL = (import.meta.env?.VITE_API_BASE_URL ?? "")
    .trim()
    .replace(/\/+$/, "");

export class ApiError extends Error {
    constructor(message, status, code) {
        super(message);
        this.name = "ApiError";
        this.status = status;
        this.code = code;
    }
}

export function apiUrl(path, query) {
    const params = new URLSearchParams();
    for (const [key, value] of Object.entries(query ?? {})) {
        if (value !== undefined && value !== null) {
            params.set(key, String(value));
        }
    }
    const search = params.toString();
    return `${API_BASE_URL}${path}${search ? `?${search}` : ""}`;
}

export async function request(path, {
    method = "GET",
    body,
    query,
    authenticated = true,
    token = getAccessToken(),
    signal,
    idempotencyKey,
    timeoutMs = 15000,
} = {}) {
    if (authenticated && !token) {
        throw new ApiError("로그인 정보가 없습니다.", 401);
    }

    const headers = { Accept: "application/json" };
    if (idempotencyKey) headers["Idempotency-Key"] = idempotencyKey;
    if (authenticated) headers.Authorization = `Bearer ${token}`;
    if (body !== undefined) headers["Content-Type"] = "application/json";

    const controller = new AbortController();
    const abort = () => controller.abort(signal?.reason);
    if (signal?.aborted) abort();
    else signal?.addEventListener("abort", abort, { once: true });
    let timedOut = false;
    const timer = setTimeout(() => { timedOut = true; controller.abort(); }, timeoutMs);
    try {
        const response = await fetch(apiUrl(path, query), {
            method,
            headers,
            credentials: "include",
            signal: controller.signal,
            ...(body !== undefined ? { body: JSON.stringify(body) } : {}),
        });

        if (response.status === 204) return null;

        const text = await response.text();
        let data;
        try {
            data = text ? JSON.parse(text) : null;
        } catch {
            if (response.ok) {
                throw new ApiError("서버 응답을 처리할 수 없습니다.", response.status);
            }
        }

        if (!response.ok) {
            if (data?.code === 'ADMISSION_REQUIRED' || data?.code === 'ADMISSION_UNAVAILABLE') {
                window.dispatchEvent(new Event('admission-required'));
            }
            if (response.status === 401 && authenticated) expireSession(token);
            const fallback = response.status === 401
                ? "로그인이 만료되었습니다. 다시 로그인해주세요."
                : `요청에 실패했습니다. (${response.status})`;
            throw new ApiError(data?.message || data?.detail || fallback, response.status, data?.code);
        }
        return data;
    } catch (error) {
        // No automatic mutation retry: retain the idempotency key and recover committed state.
        if (timedOut) throw new ApiError("응답이 지연되고 있습니다. 처리 결과를 확인한 뒤 다시 시도해주세요.", 0, "REQUEST_TIMEOUT");
        throw error;
    } finally {
        clearTimeout(timer);
        signal?.removeEventListener("abort", abort);
    }
}
