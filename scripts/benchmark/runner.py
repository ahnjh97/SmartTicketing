"""Compare pinned branch snapshots without checking out or modifying the working tree."""
import argparse
import csv
import hashlib
import io
import json
import os
from pathlib import Path
import platform
import secrets
import shutil
import socket
import subprocess
import sys
import tarfile
import threading
import time
import uuid
import zipfile
from datetime import datetime, timezone

from harness import Client, FixtureServer, default_fixtures, summarize, validate_fixtures, validate_response
from live import default_origins, live_scenarios, read_catalog, require_live_settings
from upstream import Ledger, LiveGateway
from harness import catalog_fixtures, original_candidates

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
BRANCHES = ['origin/perf/api-original', 'origin/perf/db-sequential', 'origin/perf/benchmark-500']
FIELDS = ['branch', 'sha', 'scenario', 'round', 'phase', 'iteration', 'utc', 'status',
          'response_ms', 'valid', 'reason', 'theater_count', 'signature', 'search_calls',
          'transit_calls', 'walk_calls', 'upstream_errors', 'candidate_signature']
PATHS = ['/v2/local/search/keyword.json', '/v2/routing/publictraffic', '/v2/routing/walk', 'errors']


def output_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')


def git(*args):
    return subprocess.check_output(['git', '-C', str(REPO), *args], text=True).strip()


def java_executable(env):
    """Resolve the actual JVM, not Oracle's javapath launcher which leaves a child behind."""
    binary = 'java.exe' if os.name == 'nt' else 'java'
    candidate = str(Path(env['JAVA_HOME']) / 'bin' / binary) if env.get('JAVA_HOME') else shutil.which(binary)
    if not candidate:
        raise RuntimeError('JDK 21 is required; set JAVA_HOME to the JDK installation')
    probe = subprocess.run([candidate, '-XshowSettings:properties', '-version'], env=env,
                           capture_output=True, text=True, check=True, timeout=30)
    for line in probe.stderr.splitlines():
        if line.strip().startswith('java.home ='):
            home = Path(line.split('=', 1)[1].strip())
            actual = home / 'bin' / binary
            if actual.is_file():
                env['JAVA_HOME'] = str(home)
                return str(actual)
    raise RuntimeError('Cannot resolve the actual JVM executable; check JAVA_HOME')


def environment(env_file):
    env = os.environ.copy()
    # Explicit configuration replaces application.properties and prevents background import/OAuth.
    for key in list(env):
        if key.startswith('SPRING_') or key in ('JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS'):
            del env[key]
    if env_file:
        for line in Path(env_file).read_text(encoding='utf-8-sig').splitlines():
            line = line.strip()
            if not line or line.startswith('#'):
                continue
            key, separator, value = line.partition('=')
            if separator and key.strip() in ('DB_USERNAME', 'DB_PASSWORD', 'DB_URL', 'KAKAO_MAP_REST_API_KEY',
                                             'BENCH_SOURCE_DB_URL', 'BENCH_SOURCE_DB_USERNAME', 'BENCH_SOURCE_DB_PASSWORD'):
                env[key.strip()] = value.strip().strip('"').strip("'")
    env['BENCH_MYSQL_USER'] = env.get('BOOKING_TEST_MYSQL_USER', env.get('DB_USERNAME', 'root'))
    env['BENCH_MYSQL_PASSWORD'] = env.get('BOOKING_TEST_MYSQL_PASSWORD', env.get('DB_PASSWORD', ''))
    env['BENCH_MYSQL_URL'] = 'jdbc:mysql://127.0.0.1:3306/?serverTimezone=UTC'
    env['BENCH_JWT_SECRET'] = secrets.token_hex(32)
    env['BENCH_SOURCE_DB_URL'] = env.get('BENCH_SOURCE_DB_URL') or env.get('DB_URL', 'jdbc:mysql://127.0.0.1:3306/smart_ticketing?serverTimezone=Asia/Seoul')
    env['BENCH_SOURCE_DB_USERNAME'] = env.get('BENCH_SOURCE_DB_USERNAME') or env.get('DB_USERNAME', 'root')
    env['BENCH_SOURCE_DB_PASSWORD'] = env.get('BENCH_SOURCE_DB_PASSWORD', env.get('DB_PASSWORD', ''))
    return env


def command(args, cwd, log, env):
    with log.open('wb') as stream:
        done = subprocess.run(args, cwd=cwd, env=env, stdout=stream, stderr=subprocess.STDOUT)
    if done.returncode:
        raise RuntimeError(f'Command failed ({done.returncode}); see {log}')


def prepare(out, env, mode='live', branches=None):
    builds = []
    # Resolve ALL refs before exporting, so a moving ref cannot change a later round.
    pinned = [(branch, git('rev-parse', '--verify', branch + '^{commit}')) for branch in (branches or BRANCHES)]
    output_json(out / 'commits.json', dict(pinned))
    for branch, sha in pinned:
        label = branch.rsplit('/', 1)[-1]
        source = out / 'sources' / label
        source.mkdir(parents=True)
        archive = subprocess.check_output(['git', '-C', str(REPO), 'archive', sha,
                                          'src', 'build.gradle', 'settings.gradle', 'gradle', 'gradlew', 'gradlew.bat'])
        with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
            # Do not follow repository symlinks or permit paths outside the export directory.
            for member in tar.getmembers():
                target = (source / member.name).resolve()
                if not target.is_relative_to(source.resolve()) or member.issym() or member.islnk():
                    raise ValueError('Unsafe Git archive entry')
            tar.extractall(source, filter='data')
        java = source / 'src/main/java/smartticketing/service/KakaoMapService.java'
        before = java.read_text(encoding='utf-8')
        needle = '.baseUrl("https://dapi.kakao.com")'
        if before.count(needle) != 1:
            raise ValueError(f'{branch}: expected exactly one upstream URL; review benchmark overlay')
        java.write_text(before.replace(needle,
            '.baseUrl(System.getenv("BENCH_UPSTREAM_URL"))'), encoding='utf-8')
        overlay = source / 'src/main/java/smartticketing/config/NearbyBenchmarkFixture.java'
        overlay.write_text('''package smartticketing.config;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
@Configuration
public class NearbyBenchmarkFixture {
    @Bean ApplicationRunner benchmarkFixture(JdbcTemplate jdbc, ConfigurableApplicationContext context) {
        return args -> {
            for (String sql : Files.readAllLines(Path.of(System.getenv("BENCH_SEED_SQL")))) {
                if (!sql.isBlank()) jdbc.execute(sql);
            }
            Files.writeString(Path.of(System.getenv("BENCH_READY_FILE")), "ready");
            Thread.ofPlatform().daemon().name("benchmark-shutdown").start(() -> {
                try {
                    while (!Files.exists(Path.of(System.getenv("BENCH_STOP_FILE")))) Thread.sleep(100);
                    context.close();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
        };
    }
}
''', encoding='utf-8')
        # Audit the only common source overlay; branch implementations remain otherwise unchanged.
        output_json(source / 'benchmark-overlay.json', {
            'sha': sha, 'upstream_url': 'metered loopback gateway to Kakao' if mode == 'live' else 'explicit stub environment injection',
            'fixture': 'ApplicationRunner inserts identical data, signals readiness and closes context on stop file',
            'service_before_sha256': hashlib.sha256(before.encode()).hexdigest(),
            'service_after_sha256': hashlib.sha256(java.read_bytes()).hexdigest()})
        print(f'[build] {branch} @ {sha[:12]}', flush=True)
        gradle = str(source / ('gradlew.bat' if os.name == 'nt' else 'gradlew'))
        command([gradle, '--no-daemon', 'bootJar'], source, out / f'build-{label}.log', env)
        jars = [p for p in (source / 'build/libs').glob('*.jar') if not p.name.endswith('-plain.jar')]
        if len(jars) != 1:
            raise RuntimeError('Expected exactly one executable JAR')
        builds.append(dict(branch=branch, sha=sha, jar=jars[0]))
    with zipfile.ZipFile(builds[0]['jar']) as jar:
        drivers = [n for n in jar.namelist() if n.startswith('BOOT-INF/lib/mysql-connector-j-')]
        if len(drivers) != 1:
            raise RuntimeError('MySQL driver missing from bootJar')
        (out / 'mysql-driver.jar').write_bytes(jar.read(drivers[0]))
    return builds


def sql_string(value):
    # Hex literals avoid quoting/sql-mode differences in fixture names and addresses.
    return "CONVERT(X'" + str(value).encode('utf-8').hex() + "' USING utf8mb4)"


def seed_sql(scenarios):
    lines = []
    seen = set()
    for s in scenarios:
        for t in s['theaters']:
            if t['id'] in seen:
                continue
            seen.add(t['id'])
            values = [sql_string(t['brand']), sql_string(t['name']), sql_string(t.get('address', s['name'])),
                      sql_string(t['id']), str(float(t['latitude'])), str(float(t['longitude'])), '1']
            lines.append('INSERT INTO theaters (brand,name,address,kakao_place_id,latitude,longitude,is_active) '
                         + 'VALUES (' + ','.join(values) + ');')
    return '\n'.join(lines)


def app_config(schema):
    return f'''server.address=127.0.0.1
server.port=${{BENCH_PORT}}
spring.datasource.url=jdbc:mysql://127.0.0.1:3306/{schema}?serverTimezone=UTC&characterEncoding=UTF-8
spring.datasource.username=${{BENCH_MYSQL_USER}}
spring.datasource.password=${{BENCH_MYSQL_PASSWORD}}
spring.datasource.hikari.maximum-pool-size=10
spring.jpa.hibernate.ddl-auto=update
spring.jpa.show-sql=false
spring.jpa.properties.hibernate.format_sql=false
spring.jpa.open-in-view=false
spring.data.redis.repositories.enabled=false
app.jwt.secret=${{BENCH_JWT_SECRET}}
app.admin.key=benchmark-only
app.frontend-url=http://localhost:5173
kakao.map.rest-api-key=${{BENCH_API_KEY}}
tmdb.auto-import=false
kakao.catalog.auto-import=false
tmdb.base-url=http://127.0.0.1:1
tmdb.access-token=unused
tmdb.image-base-url=http://127.0.0.1:1
tmdb.movie-ids=
booking.seed.enabled=false
'''


def database(action, schema, env, out, folder):
    command([env['BENCH_JAVA'], '-cp', str(out / 'mysql-driver.jar'), str(out / 'harness/Database.java'), action, schema],
            REPO, folder / f'db-{action.lower()}.log', env)


def stop_server(process, stop_file):
    if process is None or process.poll() is not None:
        return
    stop_file.touch()
    try:
        process.wait(timeout=30)
    except subprocess.TimeoutExpired:
        process.kill()  # Popen owns the actual JVM, never a launcher/proxy.
        process.wait(timeout=10)


def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def wait_ready(process, ready, timeout):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError('Server exited during startup; see server.log')
        if ready.exists():
            return
        time.sleep(.25)
    raise RuntimeError('Server readiness timed out; see server.log')


def measure(build, rnd, scenario, args, env, out, writer, raw, stub):
    folder = out / f'round-{rnd}' / build['branch'].rsplit('/', 1)[-1] / str(args.scenarios.index(scenario))
    folder.mkdir(parents=True)
    schema = 'nearby_bench_' + uuid.uuid4().hex
    local = env.copy()
    local['BENCH_PORT'] = str(free_port())
    local['BENCH_SEED_SQL'] = str(out / 'seed.sql')
    local['BENCH_READY_FILE'] = str(folder / 'ready')
    local['BENCH_STOP_FILE'] = str(folder / 'stop')
    config = folder / 'application.properties'
    config.write_text(app_config(schema), encoding='utf-8')
    created, process, client = False, None, None
    output_json(folder / 'run.json', dict(branch=build['branch'], sha=build['sha'], schema=schema,
                                        scenario=scenario['name'], round=rnd))
    try:
        database('CREATE', schema, local, out, folder)
        created = True
        with (folder / 'server.log').open('wb') as log:
            process = subprocess.Popen([local['BENCH_JAVA'], '-Xms512m', '-Xmx512m', '-jar', str(build['jar']),
                                        '--spring.config.location=' + config.as_uri()],
                                       cwd=folder, env=local, stdout=log, stderr=subprocess.STDOUT,
                                       creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
            wait_ready(process, Path(local['BENCH_READY_FILE']), args.startup_timeout)
            client = Client(int(local['BENCH_PORT']), local['BENCH_JWT_SECRET'], args.timeout)
            for phase, count in [('preflight', 1), ('warmup', args.warmup), ('measure', args.runs)]:
                for i in range(1, count + 1):
                    if stub:
                        stub.begin_sample(f'{scenario["name"]}:{rnd}:{phase}:{i}')
                    before = stub.snapshot() if stub else {}
                    status, body, ms, error = client.request(scenario)
                    valid, reason, size, signature = validate_response(
                        status, body, scenario, args.mode, build['branch'] == BRANCHES[0])
                    after = stub.snapshot() if stub else {}
                    calls = [after.get(p, 0) - before.get(p, 0) for p in PATHS] if stub else ['', '', '', '']
                    if stub and args.mode == 'stub':
                        n = len(scenario['theaters'])
                        original_n = len(original_candidates(scenario))
                        expected = [3, original_n, original_n, 0] if build['branch'] == BRANCHES[0] else [0, n, 0, 0]
                        if calls != expected:
                            valid, reason = False, reason or 'upstream_call_mismatch'
                    row = dict(zip(FIELDS, [build['branch'], build['sha'], scenario['name'], rnd, phase, i,
                        datetime.now(timezone.utc).isoformat(), status, round(ms, 4), valid, error or reason,
                        size, signature, *calls]))
                    row['candidate_signature'] = hashlib.sha256(json.dumps(sorted(
                        str(r['kakaoPlaceId']) for r in json.loads(body))).encode()).hexdigest() if valid else ''
                    writer.writerow(row)
                    raw.flush()
                    if stub and stub.halted:
                        raise RuntimeError('Upstream gateway halted: ' + stub.halted)
                    if phase != 'measure' and not valid:
                        raise RuntimeError(f'{phase} failed: {row["reason"]}; raw.csv contains the failure')
                    if phase == 'measure' and (i == 1 or i % 25 == 0 or i == count):
                        print(f'  round={rnd} {build["branch"]} {scenario["name"]} {i}/{count}: {ms:.1f}ms valid={valid}', flush=True)
                    # A timeout can leave upstream work running and contaminate the next sample.
                    if status == 0:
                        raise RuntimeError('Transport failure; stopped rather than overlapping timed-out requests')
    finally:
        if client:
            client.close()
        stop_server(process, Path(local['BENCH_STOP_FILE']))
        if created:
            database('DROP', schema, local, out, folder)


def report(out, complete):
    with (out / 'raw.csv').open(encoding='utf-8', newline='') as f:
        rows = list(csv.DictReader(f))
    summaries = summarize(rows)
    if summaries:
        with (out / 'summary.csv').open('w', encoding='utf-8-sig', newline='') as f:
            writer = csv.DictWriter(f, fieldnames=list(summaries[0]))
            writer.writeheader()
            writer.writerows(summaries)
    measured = [r for r in rows if r['phase'] == 'measure']
    eligible = complete and bool(measured) and all(r['valid'] == 'True' for r in rows)
    meta = json.loads((out / 'environment.json').read_text(encoding='utf-8'))
    lines = ['# Nearby benchmark', '', f'Mode: **{meta["mode"]}**. Complete: {complete}. All measured responses valid: {eligible}.',
             'Latency includes HTTP response body receipt; excludes JSON parsing. Percentiles use nearest rank.',
             ('Live Kakao through a metered local gateway (HTTP timing includes gateway overhead). Small samples are integration evidence, not stable tail latency.'
              if meta['mode'] == 'live' else 'Stub numbers describe simulated delays, not production performance.'), '',
             '| Branch | Scenario | N valid/total | Mean ms | p50 ms | p95 ms | Error % |',
             '|---|---|---:|---:|---:|---:|---:|']
    if meta['mode'] != 'live' and (meta.get('runs', 0) < 100 or meta.get('rounds', 0) < 3):
        lines[2:2] = ['**Exploratory controlled run: report sample sizes and round variability; no production latency claims.**', '']
    for s in summaries:
        if s['round'] == 'all':
            fmt = lambda x: '-' if x is None else f'{x:.2f}'
            lines.append(f'| {s["branch"]} | {s["scenario"]} | {s["valid"]}/{s["requests"]} | '
                         + ' | '.join(fmt(s[k]) for k in ('mean_ms', 'p50_ms', 'p95_ms', 'error_pct')) + ' |')
    lines += ['', '## Round detail and actual upstream attempts', '']
    for item in summaries:
        if item['round'] != 'all':
            lines.append(f'- {item["branch"]} / {item["scenario"]} / round {item["round"]}: n={item["valid"]}, p50={item["p50_ms"]}, p95={item["p95_ms"]}')
    for branch in sorted({r['branch'] for r in rows}):
        part = [r for r in rows if r['branch'] == branch]
        counts = {k: sum(int(r.get(k) or 0) for r in part) for k in ('search_calls', 'transit_calls', 'walk_calls', 'upstream_errors')}
        lines.append(f'- {branch}: {counts} (includes preflight and warmup)')
    if eligible:
        lines += ['', '## Within-scenario improvements', '', 'Original -> sequential includes changed work (no walk/search/upsert).',
                  'Sequential -> parallel compares transit concurrency with common instrumentation. No cross-scenario percentile pooling.', '']
        aggregate = {(s['branch'], s['scenario']): s for s in summaries if s['round'] == 'all'}
        for scenario in sorted({s['scenario'] for s in summaries}):
            for baseline, target in [(BRANCHES[0], BRANCHES[1]), (BRANCHES[1], BRANCHES[2])]:
                if (baseline, scenario) not in aggregate or (target, scenario) not in aggregate:
                    continue
                a, b = aggregate[(baseline, scenario)], aggregate[(target, scenario)]
                candidate_sets = [{r.get('candidate_signature', '') for r in measured
                                   if r['branch'] == branch and r['scenario'] == scenario}
                                  for branch in (baseline, target)]
                if any(len(s) != 1 or '' in s for s in candidate_sets) or candidate_sets[0] != candidate_sets[1]:
                    lines.append(f'- {scenario}: {baseline} -> {target}: candidate sets differ; no improvement percentage.')
                    continue
                if meta['mode'] == 'live' or min(a['valid'], b['valid']) < 100:
                    lines.append(f'- {scenario}: small/live sample; report observations only, no p95 improvement claim.')
                    continue
                reduction = 100 * (1 - b['p95_ms'] / a['p95_ms'])
                lines.append(f'- {scenario}: {baseline} -> {target}: p95 {a["p95_ms"]:.2f} -> {b["p95_ms"]:.2f} ms ({reduction:.2f}% reduction).')
    else:
        lines += ['', 'Incomplete/invalid run: do not publish an improvement percentage. Inspect raw.csv and server logs.']
    (out / 'report.md').write_text('\n'.join(lines) + '\n', encoding='utf-8')
    return eligible


def planned_calls(scenarios, branches, runs, warmup, rounds, live=True):
    multiplier = (1 + warmup + runs) * rounds
    counts = dict(search=0, transit=0, walk=0)
    for scenario in scenarios:
        for branch in branches:
            if branch == BRANCHES[0]:
                n = 45 if live else len(original_candidates(scenario))
                counts['search'] += 3 * multiplier
                counts['walk'] += n * multiplier
            else:
                n = len(scenario['theaters'])
            counts['transit'] += n * multiplier
    return counts


def snapshot_catalog(args, env, out):
    target = out / 'seoul-theaters.csv'
    if args.catalog:
        shutil.copyfile(args.catalog, target)
    else:
        if not env.get('BENCH_SOURCE_DB_URL', '').startswith('jdbc:mysql://'):
            raise ValueError('Set BENCH_SOURCE_DB_URL (or DB_URL) in the env file, or pass --catalog CSV.')
        roots = [Path(env.get('GRADLE_USER_HOME', Path.home() / '.gradle')),
                 Path(env.get('USERPROFILE', str(Path.home()))) / '.gradle']
        drivers = sorted({p for root in roots for p in root.glob('caches/modules-2/files-2.1/com.mysql/mysql-connector-j/*/*/*.jar')
                          if not p.name.endswith(('-sources.jar', '-javadoc.jar'))})
        if not drivers:
            raise RuntimeError('MySQL JDBC driver not cached. Run Gradle bootJar once or supply --catalog CSV.')
        env['BENCH_JAVA'] = java_executable(env)
        command([env['BENCH_JAVA'], '-cp', str(drivers[-1]), str(HERE / 'Snapshot.java'), str(target)],
                REPO, out / 'snapshot.log', env)
    return read_catalog(target)


def main():
    if sys.version_info < (3, 12):
        raise SystemExit('Python 3.12+ is required.')
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--mode', choices=['plan', 'stub', 'live'], default='plan')
    p.add_argument('--comparison', choices=['parallel', 'all'], default='parallel')
    p.add_argument('--runs', type=int)
    p.add_argument('--warmup', type=int)
    p.add_argument('--rounds', type=int)
    p.add_argument('--catalog', type=Path)
    p.add_argument('--fixtures', type=Path, help='Explicit synthetic fixtures, stub mode only')
    p.add_argument('--origins', type=Path)
    p.add_argument('--env-file', type=Path, default=REPO / ('.env.benchmark' if (REPO / '.env.benchmark').exists() else '.env'))
    p.add_argument('--route-ms', type=int, default=100)
    p.add_argument('--search-ms', type=int, default=50)
    p.add_argument('--delay-profile', choices=['fixed', 'variable'], default='variable')
    p.add_argument('--seed', type=int, default=2026)
    p.add_argument('--budget-search', type=int, default=30)
    p.add_argument('--budget-transit', type=int, default=600)
    p.add_argument('--budget-walk', type=int, default=150)
    p.add_argument('--timeout', type=int, default=120)
    p.add_argument('--startup-timeout', type=int, default=120)
    p.add_argument('--prepare-only', action='store_true')
    args = p.parse_args()
    args.runs = args.runs if args.runs is not None else (100 if args.mode == 'stub' else 1)
    args.warmup = args.warmup if args.warmup is not None else (20 if args.mode == 'stub' else 0)
    args.rounds = args.rounds if args.rounds is not None else (3 if args.mode == 'stub' else 1)
    limits = dict(search=args.budget_search, transit=args.budget_transit, walk=args.budget_walk)
    if min(args.runs, args.rounds, args.timeout, args.startup_timeout) < 1 or min(args.warmup, args.route_ms, args.search_ms, *limits.values()) < 0:
        p.error('Invalid counts/timeouts/budgets')
    if args.fixtures and (args.mode != 'stub' or args.catalog or args.origins):
        p.error('--fixtures is an explicit synthetic stub input; cannot combine with catalog/origins')
    env = environment(args.env_file if args.env_file.exists() else None)
    if args.mode == 'live' and not args.prepare_only and not env.get('KAKAO_MAP_REST_API_KEY'):
        p.error('Live mode requires KAKAO_MAP_REST_API_KEY; no fallback is used.')
    branches = BRANCHES if args.comparison == 'all' else BRANCHES[1:]
    out = REPO / 'benchmark-results' / (datetime.now().strftime('%Y%m%d-%H%M%S') + '-' + uuid.uuid4().hex[:6])
    out.mkdir(parents=True)
    print(f'Results: {out}', flush=True)
    metadata = dict(mode=args.mode, comparison=args.comparison, runs=args.runs, warmup=args.warmup, rounds=args.rounds,
                    route_ms=args.route_ms if args.mode == 'stub' else None, search_ms=args.search_ms if args.mode == 'stub' else None,
                    delay_profile=args.delay_profile if args.mode == 'stub' else None, seed=args.seed,
                    os=platform.platform(), cpu=platform.processor(), logical_cpus=os.cpu_count(), python=sys.version,
                    jvm=['-Xms512m', '-Xmx512m'], concurrency=1, started_utc=datetime.now(timezone.utc).isoformat(), status='preparing',
                    harness_sha256={f.name: hashlib.sha256(f.read_bytes()).hexdigest() for f in HERE.iterdir() if f.is_file()})
    stub, complete, ledger = None, False, None
    (out / 'harness').mkdir()
    for f in HERE.iterdir():
        if f.is_file(): shutil.copy2(f, out / 'harness' / f.name)
    try:
        if args.prepare_only:
            env['BENCH_JAVA'] = java_executable(env)
            prepare(out, env, args.mode, branches)
            metadata['status'] = 'prepared'
            return 0
        if args.fixtures:
            args.scenarios = json.loads(args.fixtures.read_text(encoding='utf-8-sig'))
            validate_fixtures(args.scenarios)
            catalog = [t for s in args.scenarios for t in s['theaters']]
            metadata['catalog_source'] = 'explicit synthetic fixtures'
        else:
            catalog = snapshot_catalog(args, env, out)
            origins = json.loads(args.origins.read_text(encoding='utf-8-sig')) if args.origins else default_origins()
            args.scenarios = live_scenarios(origins, catalog)
            if args.mode == 'stub': catalog_fixtures(args.scenarios)
            metadata['catalog_source'] = 'Seoul database snapshot'
            metadata['catalog_sha256'] = hashlib.sha256((out / 'seoul-theaters.csv').read_bytes()).hexdigest()
        metadata['catalog_count'] = len(catalog)
        metadata['route_source'] = 'synthetic controlled response' if args.mode == 'stub' else 'live Kakao'
        estimates = planned_calls(args.scenarios, branches, args.runs, args.warmup, args.rounds, args.mode != 'stub')
        plan = dict(mode=args.mode, branches=branches, origins={s['name']: len(s['theaters']) for s in args.scenarios},
                    runs=args.runs, warmup=args.warmup, rounds=args.rounds, planned_upstream_calls=estimates,
                    budgets=limits, note='Includes preflight/warmup; original live assumes up to 45 candidates before output limit. Benchmark ledger does not include other clients.')
        if args.mode in ('live', 'plan') and env.get('KAKAO_MAP_REST_API_KEY'):
            ledger = Ledger(REPO / 'benchmark-results/quota.sqlite3', env['KAKAO_MAP_REST_API_KEY'], limits)
            plan['remaining_today'] = ledger.remaining()
        allowance = plan.get('remaining_today', limits)
        plan['fits_live_budget'] = all(estimates[k] <= allowance[k] for k in limits)
        output_json(out / 'plan.json', plan)
        output_json(out / 'fixtures.json', args.scenarios)
        print(json.dumps(plan, ensure_ascii=False, indent=2), flush=True)
        if args.mode == 'plan':
            metadata['status'] = 'planned_no_api_calls'
            return 0
        if args.mode == 'live' and not plan['fits_live_budget']:
            raise ValueError('Planned calls exceed remaining benchmark budget. Change origins/counts or deliberately allocate a budget; nothing sent.')
        env['BENCH_JAVA'] = java_executable(env)
        metadata['java'] = subprocess.run([env['BENCH_JAVA'], '-version'], capture_output=True, text=True).stderr
        builds = prepare(out, env, args.mode, branches)
        stub = (FixtureServer(args.scenarios, args.route_ms, args.search_ms, args.delay_profile, args.seed)
                if args.mode == 'stub' else LiveGateway(env['KAKAO_MAP_REST_API_KEY'], ledger, out / 'upstream.csv'))
        threading.Thread(target=stub.serve_forever, daemon=True).start()
        env['BENCH_UPSTREAM_URL'] = f'http://127.0.0.1:{stub.server_port}'
        env['BENCH_API_KEY'] = 'benchmark-gateway'
        (out / 'seed.sql').write_text(seed_sql([dict(name='Seoul catalog', theaters=catalog)]), encoding='utf-8')
        metadata['status'] = 'running'
        with (out / 'raw.csv').open('w', encoding='utf-8', newline='') as raw:
            writer = csv.DictWriter(raw, fieldnames=FIELDS); writer.writeheader(); raw.flush()
            for rnd in range(1, args.rounds + 1):
                offset = (rnd - 1) % len(builds)
                for build in builds[offset:] + builds[:offset]:
                    for scenario in args.scenarios:
                        measure(build, rnd, scenario, args, env, out, writer, raw, stub)
        complete = True
        metadata['status'] = 'completed'
    except (Exception, KeyboardInterrupt) as exc:
        metadata['status'] = 'failed'
        metadata['failure'] = str(exc)
        print(f'FAILED: {exc}', file=sys.stderr)
    finally:
        if stub:
            stub.shutdown(); stub.server_close()
        if ledger: metadata['remaining_today'] = ledger.remaining()
        metadata['finished_utc'] = datetime.now(timezone.utc).isoformat()
        output_json(out / 'environment.json', metadata)
        if (out / 'raw.csv').exists():
            complete = report(out, complete) and complete
        print(f'Artifacts: {out}', flush=True)
    return 0 if complete else 1


if __name__ == '__main__':
    sys.exit(main())
