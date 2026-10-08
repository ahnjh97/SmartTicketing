// One read at a time; focus/visibility bursts share the same scheduled refresh.
export function startVisiblePolling(read, delay, initialDelay = 0) {
    let active = true, running = false, timer, controller, resume = false;
    const visible = () => document.visibilityState !== 'hidden';
    const schedule = wait => {
        clearTimeout(timer);
        if (active && visible() && wait !== null) timer = setTimeout(poll, wait);
    };
    async function poll() {
        if (!active || !visible() || running) return;
        running = true;
        controller = new AbortController();
        // A read may choose its next delay; null pauses timers but keeps focus refreshes.
        let nextDelay = delay;
        try {
            const requestedDelay = await read(controller.signal);
            if (requestedDelay !== undefined) nextDelay = requestedDelay;
        }
        finally {
            running = false;
            schedule(resume ? 0 : nextDelay);
            resume = false;
        }
    }
    const refresh = () => {
        clearTimeout(timer);
        if (!visible()) { controller?.abort(); return; }
        if (running) { resume = true; return; }
        schedule(0);
    };
    window.addEventListener('focus', refresh);
    document.addEventListener('visibilitychange', refresh);
    if (initialDelay) schedule(initialDelay);
    else if (visible()) void poll();
    return () => {
        active = false; clearTimeout(timer); controller?.abort();
        window.removeEventListener('focus', refresh);
        document.removeEventListener('visibilitychange', refresh);
    };
}
