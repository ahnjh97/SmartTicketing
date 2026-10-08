import { apiUrl } from './client.js';

let entering;
async function call(action, method) {
    let response;
    try {
        response = await fetch(apiUrl(`/api/admission/${action}`), {
            method, credentials: 'include', headers: { Accept: 'application/json' },
            signal: AbortSignal.timeout(8000), cache: 'no-store',
        });
    } catch { throw new Error('서버에 연결하지 못했습니다.'); }
    const data = await response.json().catch(() => null);
    if (!data) throw new Error('접속 상태를 확인하지 못했습니다.');
    if (!response.ok && data.state !== 'FULL') throw new Error(data.message || '접속 상태를 확인하지 못했습니다.');
    if (!['ADMITTED', 'DISABLED', 'WAITING', 'EXPIRED', 'FULL'].includes(data.state)) throw new Error('접속 안내를 다시 연결하고 있습니다.');
    return data;
}
export const admissionApi = {
    enter() {
        // React StrictMode remounts share one in-flight registration.
        if (!entering) entering = call('enter', 'POST').finally(() => { entering = null; });
        return entering;
    },
    status: () => call('status', 'GET'),
    async leave() {
        if (entering) await entering.catch(() => {});
        return call('leave', 'POST');
    },
};
