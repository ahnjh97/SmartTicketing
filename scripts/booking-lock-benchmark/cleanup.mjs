import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';

const path = resolve(process.argv[2] || '.local/booking-lock-benchmark/manifest.json');
const manifest = JSON.parse(await readFile(path, 'utf8'));
const request = async (user, groupId) => {
  const response = await fetch(`${manifest.baseUrl}/api/booking-groups/${groupId}/cancel`, {
    method:'POST',
    headers:{Authorization:`Bearer ${user.token}`, 'Idempotency-Key':`lock-cleanup-${groupId}`},
    signal:AbortSignal.timeout(30000),
  });
  const body=await response.text();
  if (!response.ok) throw new Error(`group ${groupId}: HTTP ${response.status}: ${body.slice(0,300)}`);
};
let failed=0;
for (const user of manifest.users || []) {
  try { await request(user, user.groupId); console.log(`cancelled group ${user.groupId}`); }
  catch (error) { failed++; console.error(error.message); }
}
if (failed) {
  console.error(`${failed} group(s) could not be cancelled. Do not start the next comparison until checked.`);
  process.exitCode=1;
} else console.log('All benchmark groups cancelled. Existing user bookings were not touched.');
