import { useCallback, useRef, useState } from "react";

export default function useAsyncAction() {
    const [error, setError] = useState("");
    const [pending, setPending] = useState(false);
    const running = useRef(false);

    const run = useCallback(async (action) => {
        if (running.current) return;
        running.current = true;
        setPending(true);
        setError("");
        try {
            await action();
        } catch (error) {
            setError(error.message || "요청 처리 중 오류가 발생했습니다.");
        } finally {
            running.current = false;
            setPending(false);
        }
    }, []);

    return { error, setError, pending, run };
}
