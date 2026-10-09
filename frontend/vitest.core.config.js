import { defineConfig, mergeConfig } from 'vitest/config';
import viteConfig from './vite.config.js';

// Keep a small set of user journeys; npm test still runs every regression test.
const coreTests = [
    'guest direct access to a member page reaches login',
    'normal login retrieves the member and opens home',
    '401 during a member API call clears authentication and redirects the mounted page',
    'real route connects audience, seats, hold, reload, payment failure/retry and whole cancellation',
    'network failure retries the same hold identity without showing premature success',
    'expired restored reservation offers restart and never offers payment',
    'foreign reservation restoration shows the server ownership error without details',
    'movie smart creates three independent zone candidates once and persists the plan URL',
    'paying the side retains the center queue, then center can be paid and only the side cancelled',
    'uncertain registration retries with the same idempotency key without optimistic success',
    'paused and holding are distinct and server allocation refreshes the shared reservation flow',
    'group cancellation is explicit and leaves terminal rows visible',
];

export default defineConfig(env => mergeConfig(viteConfig(env), {
    test: {
        environment: 'jsdom',
        include: [
            'tests/routes.integration.test.jsx',
            'tests/manual-booking.integration.test.jsx',
            'tests/smart-booking.integration.test.jsx',
            'tests/waiting.integration.test.jsx',
            'tests/admission.integration.test.jsx',
        ],
        testNamePattern: new RegExp(`^(${coreTests.join('|')}|site admission .*)$`),
    },
}));
