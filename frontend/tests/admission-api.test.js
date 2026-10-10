import { test } from 'node:test';
import assert from 'node:assert/strict';
import { admissionApi } from '../src/api/admission.js';

test('duplicate entry shares a request and leave waits for registration', async () => {
    const original = globalThis.fetch;
    const calls = [];
    let finish;
    globalThis.fetch = async (url, options) => {
        calls.push([url, options]);
        if (url.endsWith('/enter')) await new Promise(resolve => { finish = resolve; });
        return {ok:true,json:async () => ({state:url.endsWith('/leave')?'EXPIRED':'WAITING'})};
    };
    try {
        const first=admissionApi.enter(); const second=admissionApi.enter();
        assert.equal(first,second);
        const leave=admissionApi.leave();
        assert.equal(calls.length,1);
        finish(); await first; await leave;
        assert.equal(calls.length,2);
        assert.ok(calls[1][0].endsWith('/leave'));
        assert.equal(calls[0][1].credentials,'include');
    } finally { globalThis.fetch = original; }
});
