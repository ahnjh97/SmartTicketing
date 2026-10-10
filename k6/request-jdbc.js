import { Counter, Rate } from 'k6/metrics';
const samples = new Counter('jdbc_samples');
const valid = new Rate('jdbc_instrumented');
const sql = new Counter('request_sql');
const rows = new Counter('request_rows');
const ranks = new Counter('rank_sql');
const inventory = new Counter('inventory_rows');

// Counters are per successful logical query, never global server/worker statistics.
export function recordJdbc(response) {
  const names = ['X-Bench-Sql', 'X-Bench-Rows', 'X-Bench-Rank-Sql', 'X-Bench-Inventory-Rows'];
  const values = names.map(name => response.headers[name]);
  const ok = response.headers['X-Bench-Instrumentation'] === 'jdbc-request-v1'
    && values.every(v => v !== undefined && /^\d+$/.test(String(v)));
  valid.add(ok);
  if (ok) {
    samples.add(1); sql.add(Number(values[0])); rows.add(Number(values[1]));
    ranks.add(Number(values[2])); inventory.add(Number(values[3]));
  }
  return ok;
}
