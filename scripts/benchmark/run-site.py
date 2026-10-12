#!/usr/bin/env python3
"""Deployment Compose based, disposable WSL DB/Redis/overall comparisons.

Application images can be supplied unchanged. Otherwise each revision is built
with its production Dockerfiles. Credentials, fixtures and TLS certificates are
local-only; the production environment and volumes are never loaded or modified.
"""
import argparse
import datetime
import hashlib
import html
import http.cookiejar
import importlib.util
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import ssl
import statistics
import subprocess
import tarfile
import time
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[2]
SECRET = 'local-admission-test-secret-012345678901234567890123456789'


def command(args, *, log=None, env=None, check=True, cwd=None, input=None):
    result = subprocess.run(args, text=True, input=input, cwd=cwd, env=env,
                            stdout=log or subprocess.PIPE, stderr=subprocess.STDOUT)
    if check and result.returncode:
        raise RuntimeError(f'{args[0]} exited {result.returncode}; ' + ('see build/services logs' if log else (result.stdout or '')[-2500:]))
    return (result.stdout or '').strip()


def git(*args):
    return command(['git', '-c', f'safe.directory={ROOT}', '-C', str(ROOT), *args])


def comparison_cases(kind):
    """(label, ref index, Redis mode); Redis comparison must reuse one commit."""
    return {
        'db': [('before-off', 0, 'off'), ('final-off', 1, 'off')],
        'redis': [('final-off', 0, 'off'), ('final-on', 0, 'on')],
        'overall': [('before-off', 0, 'off'), ('final-on', 1, 'on')],
        'all': [('before-off', 0, 'off'), ('final-off', 1, 'off'), ('final-on', 1, 'on')],
    }[kind]


def performance_summary(runs, smoke):
    """Descriptive paired observations, not a significance test or capacity claim."""
    if smoke:
        return {'status':'기능 확인 전용 — 성능 비교 제외', 'comparisons':[]}
    comparisons = []
    for users in sorted({r.get('users',0) for r in runs}):
        for before,after in [('before-off','final-off'),('final-off','final-on'),('before-off','final-on')]:
            groups = [[r for r in runs if r.get('users') == users and r['label']==label] for label in (before,after)]
            if not all(groups): continue
            item = {'users':users, 'comparison':before+' → '+after, 'status':'비교 보류', 'endpoints':{}}
            comparisons.append(item)
            if any(len(g)<4 for g in groups):
                item['reason'] = '모드별 독립 반복 4회 미만'; continue
            if any(r['status']!='passed' or r.get('warmupSeconds',0)<30 or
                   r.get('measurementWindow',{}).get('durationSeconds',0)<60 or
                   r.get('metrics',{}).get('failedChecks',1)>0 or
                   r.get('metrics',{}).get('admissionErrors',1)>0 or
                   r.get('metrics',{}).get('allPhaseErrors')!=0 for g in groups for r in g):
                item['reason'] = '실패가 있거나 예열 30초/측정 60초 조건 미달'; continue
            left,right = [{r['repeat']:r for r in g} for g in groups]
            if left.keys()!=right.keys() or len(left)!=len(groups[0]) or len(right)!=len(groups[1]):
                item['reason']='짝지은 반복 누락 또는 중복'; continue
            if before=='final-off' and any(left[k].get('images')!=right[k].get('images') for k in left):
                item['reason']='Redis 비교 애플리케이션 이미지 불일치'; continue
            if before=='final-off' and any(str(left[k].get('cacheEnabled')).lower()!='false' or
                                           str(right[k].get('cacheEnabled')).lower()!='true' for k in left):
                item['reason']='Redis OFF/ON 설정 미확인'; continue
            keys = set.intersection(*(set(r['metrics'].get('endpoints',{})) for g in groups for r in g))
            for endpoint in sorted(keys):
                values = [r['metrics']['endpoints'][endpoint] for g in groups for r in g]
                if min(v['samples'] for v in values)<200: continue
                ratios = [(right[k]['metrics']['endpoints'][endpoint]['p95Ms']/
                           left[k]['metrics']['endpoints'][endpoint]['p95Ms']-1)*100 for k in left
                          if left[k]['metrics']['endpoints'][endpoint]['p95Ms']>0]
                if len(ratios)!=len(left): continue
                item['endpoints'][endpoint] = {'pairedP95ChangesPercent':ratios,
                    'medianChangePercent':statistics.median(ratios), 'rangePercent':[min(ratios),max(ratios)],
                    'observation':'반복마다 방향 다름 — 결론 보류' if min(ratios)<=0<=max(ratios) else
                                  '모든 반복에서 지연 감소 관측' if max(ratios)<0 else '모든 반복에서 지연 증가 관측'}
            item['status'] = 'API별 반복 관측치 — 통계적 유의성/운영 성능 보장 아님' if item['endpoints'] else '비교 보류'
            if not item['endpoints']: item['reason']='모든 반복에서 API별 표본 200개 조건을 충족한 항목 없음'
    return {'status':'짝지은 반복 결과' if comparisons else '단독 측정 — 비교 구성 없음', 'comparisons':comparisons,
            'scope':'동일 WSL 자원을 공유하는 고정 사용자 실험. SQL은 워커 포함 참고값. 혼합 p95와 SQL 총량으로 개선율을 계산하지 않음.'}


def capacity_summary(runs, levels, repeats, p95_limit, smoke):
    if smoke or len(levels) < 2 or repeats < 4:
        return {'status':'not-measured', 'reason':'Requires a non-smoke load sweep with at least four repetitions'}
    summary = {}
    for label in {run['label'] for run in runs}:
        candidates = []
        for users in levels:
            group = [r for r in runs if r['label'] == label and r['users'] == users]
            if len(group) != repeats or not all(
                r['status'] == 'passed' and r.get('metrics',{}).get('p95Ms') is not None
                and r['metrics']['p95Ms'] <= p95_limit
                and r.get('warmupSeconds',0) >= 30
                and r.get('measurementWindow',{}).get('durationSeconds',0) >= 60
                and r['metrics'].get('requests',0) >= 200
                and r['metrics'].get('failedChecks',1) == 0 and r['metrics'].get('admissionErrors',1) == 0
                and r['metrics'].get('successfulBusinessRequestsPerSecond') is not None for r in group):
                continue
            candidates.append({'users':users, 'successfulRequestsPerSecond':statistics.median(
                r['metrics']['successfulBusinessRequestsPerSecond'] for r in group)})
        summary[label] = {'bestObservedPassingRate':max(candidates, key=lambda r:r['successfulRequestsPerSecond']) if candidates else None,
                          'highestTestedLoadPassed':bool(candidates and candidates[-1]['users'] == max(levels))}
    return {'status':'bounded-observation', 'p95LimitMs':p95_limit, 'userLevels':levels, 'variants':summary,
            'note':'Highest observed passing rate in the selected range, not proven absolute capacity. Includes load-generator/WSL limits.'}


def capacity_html(summary):
    if summary['status'] == 'not-measured':
        return '<p>측정 안 됨: 스모크가 아닌 단계별 부하와 단계별 4회 이상 반복이 필요합니다.</p>'
    rows = []
    for label, value in summary['variants'].items():
        best = value['bestObservedPassingRate']
        cells = [label, str(best['users']) if best else '—',
                 f"{best['successfulRequestsPerSecond']:.2f}" if best else '—',
                 '최상위 부하도 통과 — 한계 미확인' if value['highestTestedLoadPassed'] else
                 ('통과 단계 중 최고 관측치' if best else '통과 단계 없음')]
        rows.append('<tr>'+''.join('<td>'+html.escape(cell)+'</td>' for cell in cells)+'</tr>')
    return (f'<p>업무 p95 기준: {summary["p95LimitMs"]}ms 이하. 지정 범위 내 관측치이며 절대 최대 처리량은 아닙니다.</p>'
            '<table border="1" cellpadding="8"><tr><th>구성</th><th>사용자</th><th>최고 관측 통과 요청/s</th><th>해석</th></tr>'
            +''.join(rows)+'</table>')


def performance_html(summary):
    result = '<p>'+html.escape(summary['status'])+'</p>'
    for comparison in summary['comparisons']:
        result += '<h3>'+html.escape(f"{comparison['comparison']} · {comparison['users']}명")+'</h3>'
        result += '<p>'+html.escape(comparison['status']+' '+comparison.get('reason',''))+'</p>'
        if not comparison['endpoints']: continue
        result += '<table border="1" cellpadding="8"><tr><th>API</th><th>반복별 p95 변화</th><th>중앙값</th><th>해석</th></tr>'
        for endpoint,value in comparison['endpoints'].items():
            cells = [endpoint, ', '.join(f'{n:+.1f}%' for n in value['pairedP95ChangesPercent']),
                     f"{value['medianChangePercent']:+.1f}%",value['observation']]
            result += '<tr>'+''.join('<td>'+html.escape(c)+'</td>' for c in cells)+'</tr>'
        result += '</table>'
    return result


def snapshot(ref, destination):
    sha = git('rev-parse', '--verify', '--end-of-options', ref + '^{commit}')
    archive = destination.with_suffix('.tar')
    command(['git', '-c', f'safe.directory={ROOT}', '-C', str(ROOT), 'archive', '--format=tar', '-o', str(archive), sha])
    destination.mkdir()
    with tarfile.open(archive) as data:
        data.extractall(destination, filter='data')
    archive.unlink()
    return sha


def build_images(source, folder, name):
    build_image = name + ':builder'
    backend = name + ':backend'
    frontend = name + ':frontend'
    with (folder / 'build.log').open('w') as log:
        command(['docker', 'build', '--target', 'build', '-t', build_image, str(source)], log=log)
        # Copy the compiled bootJar into the same prebuilt target used by deployment CI.
        container = command(['docker', 'create', build_image])
        jars = folder / 'jars'
        try:
            command(['docker', 'cp', container + ':/app/build/libs', str(jars)], log=log)
        finally:
            command(['docker', 'rm', container], check=False)
        candidates = [p for p in jars.glob('*.jar') if not p.name.endswith('-plain.jar')]
        if len(candidates) != 1:
            raise RuntimeError('Expected exactly one bootJar')
        context = folder / 'image-context'
        (context / 'build/deploy').mkdir(parents=True)
        (context / 'deploy').mkdir()
        shutil.copyfile(candidates[0], context / 'build/deploy/app.jar')
        shutil.copyfile(source / 'Dockerfile', context / 'Dockerfile')
        # The classic Docker builder also visits earlier stages for --target prebuilt.
        for filename in ('gradlew', 'build.gradle', 'settings.gradle'):
            shutil.copyfile(source / filename, context / filename)
        for directory in ('gradle', 'src'):
            shutil.copytree(source / directory, context / directory)
        shutil.copyfile(source / 'deploy/backend-entrypoint.sh', context / 'deploy/backend-entrypoint.sh')
        entrypoint = context / 'deploy/backend-entrypoint.sh'
        entrypoint.write_text(entrypoint.read_text().replace('\r\n', '\n'))
        command(['docker', 'build', '--target', 'prebuilt', '-t', backend, str(context)], log=log)
        command(['docker', 'build', '-t', frontend, '--build-arg', 'VITE_API_BASE_URL=',
                 '--build-arg', 'VITE_BASE_PATH=/', '--build-arg', 'VITE_KAKAO_MAP_JS_KEY=localBenchmarkKey', str(source / 'frontend')], log=log)
    return backend, frontend


def compose_config(folder, name, backend, frontend, cache):
    password = secrets.token_hex(24)
    # Explicit test-only env file; do not inherit application secrets from the host.
    env = {k: v for k, v in os.environ.items() if k in ('PATH', 'HOME', 'DOCKER_HOST', 'DOCKER_CONFIG', 'XDG_RUNTIME_DIR')}
    values = {'MYSQL_ROOT_PASSWORD': password, 'MYSQL_DATABASE': 'site_benchmark', 'JWT_SECRET': SECRET,
              'ADMIN_KEY': 'local-only', 'VITE_KAKAO_MAP_JS_KEY': 'localBenchmarkKey',
              'BACKEND_IMAGE': backend, 'FRONTEND_IMAGE': frontend}
    if cache != 'branch':
        values['APP_CACHE_ENABLED'] = 'true' if cache == 'on' else 'false'
    env_file = folder / 'private.env'
    env_file.write_text('\n'.join(f'{k}={v}' for k, v in values.items()) + '\n')
    os.chmod(env_file, 0o600)
    try:
        data = json.loads(command(['docker', 'compose', '--env-file', str(env_file), '-f', str(ROOT / 'docker-compose.yml'),
                                   'config', '--format', 'json'], env=env))
    finally:
        env_file.unlink()
    data['name'] = name
    services = data['services']
    services.pop('certbot', None)
    certs = folder / 'certs'
    certs.mkdir()
    command(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1',
             '-keyout', str(certs / 'privkey.pem'), '-out', str(certs / 'fullchain.pem'), '-subj', '/CN=nginx'])
    for service in services.values():
        service.pop('container_name', None)
        service.pop('restart', None)
        service.pop('build', None)
        service.pop('ports', None)
        for limit in ('cpus', 'mem_limit', 'memswap_limit', 'cpu_quota', 'cpu_period'):
            if limit in service:
                raise RuntimeError('Production Compose contains resource limits; choose explicitly before benchmarking')
    # Persistent resources are scoped to this unique project, never production names.
    for kind in ('networks', 'volumes'):
        for value in data.get(kind, {}).values():
            if value.get('external'):
                raise RuntimeError('External production resources cannot be used by the benchmark')
            value.pop('name', None)
    app_env = services['backend']['environment']
    app_env.update({'TMDB_AUTO_IMPORT':'false', 'KAKAO_CATALOG_AUTO_IMPORT':'false', 'SHOWTIME_SEED_ENABLED':'false',
                    'BOOKING_SEED_ENABLED':'false', 'ADMIN_INITIAL_PASSWORD':'', 'FRONTEND_URL':'https://nginx'})
    if str(app_env.get('ADMISSION_ENABLED')).lower() != 'true':
        raise RuntimeError('Deployment parity run requires enabled admission')
    services['nginx']['ports'] = [{'target':443, 'published':'0', 'host_ip':'127.0.0.1', 'protocol':'tcp'}]
    services['nginx']['volumes'] = [
        {'type':'bind', 'source':str(ROOT / 'deploy/nginx/default.conf'), 'target':'/etc/nginx/conf.d/default.conf', 'read_only':True},
        {'type':'bind', 'source':str(certs), 'target':'/etc/letsencrypt/live/smartticketing.duckdns.org', 'read_only':True}]
    path = folder / 'compose.private.json'
    path.write_text(json.dumps(data))
    os.chmod(path, 0o600)
    return path, password, app_env.get('APP_CACHE_ENABLED')


def seed(folder, compose, password, users, date):
    spec = importlib.util.spec_from_file_location('site_fixture', folder / 'site-fixture.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    sql, fixture = module.fixture(users, date)
    mysql = compose + ['exec', '-T', '-e', f'MYSQL_PWD={password}', 'mysql', 'mysql', '-uroot', 'site_benchmark']
    command(mysql, input=sql)
    port = command(compose + ['port', 'nginx', '443']).rsplit(':', 1)[1]
    base = 'https://127.0.0.1:' + port
    opener = urllib.request.build_opener(urllib.request.HTTPSHandler(context=ssl._create_unverified_context()),
                                        urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    def post(path, body=None):
        req = urllib.request.Request(base + path, data=json.dumps(body).encode() if body else b'',
                                     headers={'Content-Type':'application/json'}, method='POST')
        with opener.open(req, timeout=15) as response:
            return json.loads(response.read())
    state = post('/api/admission/enter')
    if state['state'] != 'ADMITTED':
        raise RuntimeError('Empty benchmark stack did not grant entry')
    # Have the application generate a compatible login hash; no crypto implementation in the harness.
    fixture['password'] = 'Benchmark-only-123!'
    post('/api/auth/signup', {'name':'Benchmark template', 'birthDate':'1990-01-01',
                             'loginId':'benchmark-password-template', 'password':fixture['password']})
    command(mysql, input="UPDATE users u JOIN users p ON p.login_id='benchmark-password-template' SET u.password=p.password WHERE u.login_id LIKE 'load-%';")
    post('/api/admission/leave')
    (folder / 'fixture.json').write_text(json.dumps(fixture))
    return base


def measure(folder, network, users, clients, duration, suite, warmup=30, collector=None):
    ids = []
    start = time.monotonic()
    seconds = int(duration[:-1]) * (60 if duration.endswith('m') else 1)
    common_start = time.time() + max(20, min(users, clients) * 2)
    window = {'start':common_start + warmup, 'end':common_start + warmup + seconds,
              'durationSeconds':seconds, 'warmupSeconds':warmup, 'snapshots':[], 'hostSamples':[]}
    (folder / 'window.json').write_text(json.dumps(window))
    try:
        offset = 0
        for index in range(min(users, clients)):
            count = users // min(users, clients) + (index < users % min(users, clients))
            path = folder / f'client-{index}'
            path.mkdir()
            shutil.copyfile(folder / 'fixture.json', path / 'fixture.json')
            name = folder.name + '-client-' + str(index)
            ids.append((name, path))
            command(['docker', 'run', '-d', '--name', name, '--network', network, '--user', '0:0',
                     '-v', f'{path}:/results', '-v', f'{folder}/site-load.js:/load.js:ro',
                     '-e', f'USERS={count}', '-e', f'USER_OFFSET={offset}', '-e', f'DURATION={warmup+seconds}s',
                     '-e', f'START_MS={common_start*1000:.0f}', '-e', f'SUITE={suite}',
                     '-e', f'WARMUP_SECONDS={warmup}', '-e', f'MEASUREMENT_SECONDS={seconds}',
                     'grafana/k6:2.3.0', 'run', '--quiet', '--no-usage-report', '--out', 'json=/results/metrics.jsonl', '/load.js'])
            offset += count
        if time.time() >= common_start:
            raise RuntimeError('Client startup exceeded common start barrier; measurement rejected')
        deadline = time.monotonic() + warmup + seconds + 180
        next_status = 0
        while time.monotonic() < deadline:
            if collector and len(window['snapshots']) < 2 and time.time() >= window[['start','end'][len(window['snapshots'])]]:
                taken = time.time()
                window['snapshots'].append({'started':taken, 'values':collector(), 'finished':time.time()})
                (folder / 'window.json').write_text(json.dumps(window))
            if time.monotonic() < next_status:
                time.sleep(.1)
                continue
            # Cheap host counters, not docker stats sampling that competes with the load.
            window['hostSamples'].append({'time':time.time(), 'logicalCpus':os.cpu_count(),
                'loadAverage':os.getloadavg(), 'cpu':Path('/proc/stat').read_text().splitlines()[0],
                'memory':{line.split(':')[0]:line.split(':')[1].strip() for line in Path('/proc/meminfo').read_text().splitlines()
                          if line.startswith(('MemTotal:','MemAvailable:','SwapTotal:','SwapFree:'))}})
            (folder / 'window.json').write_text(json.dumps(window))
            running = [command(['docker', 'inspect', '--format', '{{.State.Running}}', name]) == 'true' for name, _ in ids]
            if not any(running):
                break
            print(f'  load running: {time.monotonic()-start:.0f}s', flush=True)
            next_status = time.monotonic() + 5
        else:
            raise RuntimeError('Load generators exceeded their deadline')
        return [int(command(['docker', 'inspect', '--format', '{{.State.ExitCode}}', name])) for name, _ in ids]
    finally:
        for name, path in ids:
            (path / 'k6.log').write_text(command(['docker', 'logs', name], check=False))
            command(['docker', 'rm', '-f', name], check=False)
            (path / 'fixture.json').unlink(missing_ok=True)
        (folder / 'fixture.json').unlink(missing_ok=True)


def summarize(folder):
    values = {}; scenarios = {}; endpoints = {}
    window_path = folder / 'window.json'
    window = json.loads(window_path.read_text()) if window_path.exists() else None
    for path in folder.glob('client-*/metrics.jsonl'):
        with path.open() as stream:
            for line in stream:
                point = json.loads(line)
                if point.get('type') != 'Point':
                    continue
                if window:
                    timestamp = datetime.datetime.fromisoformat(point['data']['time'].replace('Z','+00:00')).timestamp()
                    started = float(point['data'].get('tags',{}).get('startedMs',timestamp*1000))/1000
                    # Follow requests started in the window through graceful drain;
                    # discarding slow completions would bias tail latency downward.
                    phase = point['data'].get('tags',{}).get('phase')
                    observed = started if point['metric']=='business_latency' else timestamp
                    included = phase=='measurement' if point['metric']=='business_latency' and phase else window['start'] <= observed < window['end']
                    if not included:
                        continue
                key = point['metric']
                if key in ('business_latency','business_failures','admission_errors','admission_wait','completed_journeys','completed_bookings','successful_business_requests'):
                    values.setdefault(key, []).append(point['data']['value'])
                if key == 'business_latency':
                    endpoint = point['data'].get('tags',{}).get('endpoint','unknown')
                    endpoints.setdefault(endpoint, []).append(point['data']['value'])
                if key == 'completed_journeys':
                    scenario = point['data'].get('tags', {}).get('journey', 'unknown')
                    scenarios[scenario] = scenarios.get(scenario, 0) + point['data']['value']
    latency = sorted(values.get('business_latency', []))
    def percentile(p, samples=None):
        samples = latency if samples is None else samples
        if not samples: return None
        at = (len(samples)-1)*p
        low = int(at); high = min(low+1, len(samples)-1)
        return samples[low] + (samples[high]-samples[low])*(at-low)
    checks = values.get('business_failures', [])
    summaries = [json.loads(path.read_text()) for path in folder.glob('client-*/summary.json')]
    # These Rate metrics receive true for errors: k6's "passes" counts true samples.
    rates = [summary.get('metrics',{}).get(metric,{}).get('values',{})
             for summary in summaries for metric in ('business_failures','admission_errors')]
    all_phase_errors = sum(rate['passes'] for rate in rates) if rates and all('passes' in rate for rate in rates) else None
    return {'requests':len(latency), 'failedChecks':sum(checks), 'failureRate':sum(checks)/len(checks) if checks else None,
            'allPhaseErrors':all_phase_errors,
            'successfulBusinessRequestsPerSecond':sum(values.get('successful_business_requests',[]))/window['durationSeconds'] if window else None,
            'endpoints':{key:{'samples':len(samples), 'p95Ms':percentile(.95,sorted(samples)),
                              'p99Ms':percentile(.99,sorted(samples)) if len(samples)>=1000 else None}
                         for key,samples in endpoints.items()},
            'p95Ms':percentile(.95), 'p99Ms':percentile(.99), 'admissionErrors':sum(values.get('admission_errors', [])),
            'admissionWaitP95Ms':percentile(.95, sorted(values.get('admission_wait', []))),
            'completedJourneys':sum(values.get('completed_journeys', [])), 'completedBookings':sum(values.get('completed_bookings', [])),
            'scenarios':scenarios}


def sql_snapshot(compose, password):
    # Schema-scoped digest counters exclude this collector (no default database).
    # Background worker SQL is deliberately included and labelled in the report.
    query = """SELECT COALESCE(SUM(COUNT_STAR),0) FROM performance_schema.events_statements_summary_by_digest
               WHERE SCHEMA_NAME='site_benchmark';
               SELECT COALESCE(SUM(COUNT_STAR),0) FROM performance_schema.events_statements_summary_by_digest
               WHERE DIGEST IS NULL;
               SELECT @@performance_schema;
               SELECT ENABLED FROM performance_schema.setup_consumers WHERE NAME='statements_digest';"""
    output = command(compose + ['exec','-T','-e',f'MYSQL_PWD={password}','mysql','mysql','-uroot',
                                 '--batch','--skip-column-names','-e',query]).splitlines()
    if len(output) != 4 or output[2] != '1' or output[3] != 'YES':
        raise RuntimeError('SQL digest collection is unavailable')
    return int(output[0]), int(output[1])


def cache_snapshot(compose):
    output = command(compose + ['exec','-T','redis','redis-cli','INFO','stats'])
    return {line.split(':')[0]:int(line.split(':')[1]) for line in output.splitlines()
            if line.startswith(('keyspace_hits:','keyspace_misses:','evicted_keys:'))}


def execute(args, source, folder, label, ref, sha, cache, images):
    folder.mkdir()
    for filename in ('site-load.js', 'site-fixture.py'):
        shutil.copyfile(args.harness / filename, folder / filename)
    name = folder.name
    result = {'label':label, 'ref':ref if not args.backend_image else 'supplied-images', 'commit':sha if not args.backend_image else None, 'suite':args.suite, 'users':args.users, 'clients':args.clients,
              'duration':args.duration, 'warmupSeconds':args.warmup, 'smoke':args.smoke, 'source':'supplied-images' if args.backend_image else 'production-dockerfiles',
              'localDifferences':['temporary database and credentials', 'synthetic fixtures; imports/seeding disabled',
                                  'local self-signed HTTPS certificate', 'OAuth/external providers not exercised'], 'status':'failed'}
    compose = None; config = None
    try:
        print(f'[{label}] preparing images and deployment Compose: {folder}', flush=True)
        backend, frontend = images or build_images(source, folder, name)
        result['images'] = {role: json.loads(command(['docker', 'image', 'inspect', image]))[0]['Id']
                            for role, image in [('backend',backend), ('frontend',frontend)]}
        result['jarSha256'] = command(['docker', 'run', '--rm', '--entrypoint', 'sha256sum', backend, '/app/app.jar']).split()[0]
        config, password, result['cacheEnabled'] = compose_config(folder, name, backend, frontend, cache)
        compose = ['docker','compose','-p',name,'--env-file','/dev/null','-f',str(config)]
        with (folder / 'startup.log').open('w') as log:
            command(compose + ['up','-d','--no-build','--wait','--wait-timeout','600'], log=log)
        result['services'] = {}
        for service in ('mysql','redis','admission-redis','backend','frontend','nginx'):
            container = command(compose + ['ps','-q',service])
            info = json.loads(command(['docker','inspect',container]))[0]
            if info['HostConfig']['Memory'] or info['HostConfig']['NanoCpus']:
                raise RuntimeError('Unexpected container resource limit')
            result['services'][service] = {'imageId':info['Image'], 'cpuLimit':0, 'memoryLimitBytes':0}
        seed(folder, compose, password, args.users, args.date)
        edge = command(compose + ['ps','-q','nginx'])
        network = next(iter(json.loads(command(['docker','inspect',edge]))[0]['NetworkSettings']['Networks']))
        cache_before = cache_snapshot(compose)
        result['exitCodes'] = measure(folder, network, args.users, args.clients, args.duration, args.suite,
                                      args.warmup, lambda: sql_snapshot(compose, password))
        cache_after = cache_snapshot(compose)
        result['cacheDiagnostics'] = {'scope':'Application Redis, warmup + measurement + drain; includes worker lookups, not HTTP cache hit rate',
                                     'delta':{key:cache_after[key]-value for key,value in cache_before.items()}}
        window = json.loads((folder/'window.json').read_text())
        result['measurementWindow'] = window
        snapshots = window['snapshots']
        if len(snapshots) != 2:
            raise RuntimeError('Incomplete measurement window')
        before_sql, after_sql = [s['values'] for s in snapshots]
        result['metrics'] = summarize(folder)
        sql_count = after_sql[0] - before_sql[0]
        valid_sql = sql_count >= 0 and after_sql[1] == before_sql[1] and all(
            0 <= s['finished'] - window[key] < 2 for s,key in zip(snapshots,('start','end')))
        result['metrics']['databaseStatements'] = sql_count if valid_sql else None
        result['metrics']['databaseStatementsPerBusinessRequest'] = (
            sql_count / result['metrics']['requests'] if valid_sql and result['metrics']['requests'] else None)
        result['sqlScope'] = 'Test schema during load; includes background workers, excludes fixture setup. Not per-request tracing.'
        if not valid_sql:
            result['sqlWarning'] = 'Digest overflow or counter reset; SQL count is unavailable.'
        seconds = int(args.duration[:-1]) * (60 if args.duration.endswith('m') else 1)
        result['metrics']['completedJourneysPerConfiguredSecond'] = result['metrics']['completedJourneys'] / seconds
        expected = {'browse','smart','manual','login'} if args.suite == 'all' else {args.suite}
        result['status'] = 'passed' if all(code == 0 for code in result['exitCodes']) and expected <= result['metrics']['scenarios'].keys() else 'failed'
    except Exception as error:
        result['error'] = str(error)
    finally:
        if compose:
            with (folder / 'services.log').open('w') as log:
                command(compose + ['logs','--no-color','--tail','500'], log=log, check=False)
            try:
                with (folder / 'cleanup.log').open('w') as log:
                    command(compose + ['down','--volumes','--remove-orphans'], log=log)
            except Exception as error:
                result['status'] = 'failed'
                result['cleanupError'] = str(error)
        if config: config.unlink(missing_ok=True)
        (folder / 'fixture.json').unlink(missing_ok=True)
        (folder / 'result.json').write_text(json.dumps(result, indent=2, ensure_ascii=False))
    print(f'[{label}] functional status: {result["status"]}', flush=True)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--reanalyze', type=Path, help='Rebuild a separate report from preserved raw site metrics; do not rerun load')
    parser.add_argument('--mode', choices=['site','compare'], default='site')
    parser.add_argument('--comparison', choices=['db','redis','overall','all'], default='all')
    parser.add_argument('--ref', action='append', default=[])
    parser.add_argument('--suite', choices=['all','browse','smart','manual','login'], default='all')
    parser.add_argument('--users', type=int, default=100)
    parser.add_argument('--user-levels', help='Comma-separated concurrent user levels for a bounded load sweep')
    parser.add_argument('--p95-limit-ms', type=float, default=1000)
    parser.add_argument('--clients', type=int, default=10)
    parser.add_argument('--duration', default='120s')
    parser.add_argument('--warmup', type=int, default=30)
    parser.add_argument('--cache-mode', choices=['branch','on','off'], default='branch')
    parser.add_argument('--repeats', type=int, default=4)
    parser.add_argument('--backend-image')
    parser.add_argument('--frontend-image')
    parser.add_argument('--smoke', action='store_true')
    args = parser.parse_args()
    if args.reanalyze:
        return reanalyze(args.reanalyze)
    if not re.fullmatch(r'[1-9][0-9]*[sm]', args.duration) or not 1 <= args.users <= 10000 or not 1 <= args.clients <= 100 or not 1 <= args.repeats <= 10:
        parser.error('Invalid load parameters')
    if bool(args.backend_image) != bool(args.frontend_image): parser.error('Supply both application images')
    if args.backend_image and args.ref: parser.error('Choose supplied images or a source ref, not both')
    required_refs = 1 if args.comparison == 'redis' else 2
    if args.mode == 'compare' and (len(args.ref) != required_refs or args.backend_image):
        parser.error('Redis comparison requires one final ref; other comparisons require before and final refs')
    if args.mode == 'compare' and args.cache_mode != 'branch': parser.error('Comparison determines Redis settings; cache-mode is for site runs')
    if args.mode == 'site' and len(args.ref) > 1: parser.error('Site accepts at most one ref')
    try:
        levels = sorted(set(int(value) for value in args.user_levels.split(','))) if args.user_levels else [args.users]
    except ValueError:
        parser.error('User levels must be comma-separated integers')
    if not levels or any(level < 1 or level > 10000 for level in levels) or not 0 < args.p95_limit_ms < float('inf'):
        parser.error('Invalid user levels or latency limit')
    if args.smoke and args.user_levels: parser.error('A smoke run cannot measure capacity')
    if not 0 <= args.warmup <= 600: parser.error('Warmup must be 0..600 seconds')
    if args.smoke: args.users, args.clients, args.duration, args.warmup, args.repeats = 2, 2, '40s', 0, 1
    if args.smoke: levels = [2]
    args.date = (datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=9))).date() + datetime.timedelta(days=2)).isoformat()
    run = 'site-' + datetime.datetime.now().strftime('%Y%m%d-%H%M%S') + '-' + uuid.uuid4().hex[:6]
    output = ROOT / 'benchmark-results' / run
    output.mkdir(parents=True)
    args.harness = output / 'harness'
    args.harness.mkdir()
    for filename in ('site-load.js', 'site-fixture.py', 'run-site.py'):
        shutil.copyfile(ROOT / 'scripts/benchmark' / filename, args.harness / filename)
    print(f'Results: {output}', flush=True)
    command(['docker','info'])
    command(['docker','compose','version'])
    sources = []
    for index, ref in enumerate(args.ref or [None]):
        source = output / f'source-{index}' if ref else ROOT
        sha = snapshot(ref, source) if ref else git('rev-parse','HEAD')
        sources.append((ref or 'working-tree', sha, source))
    if args.mode == 'compare' and required_refs == 2 and sources[0][1] == sources[1][1]:
        parser.error('Before and final must be different commits; use Redis comparison for the same commit')
    variants = [(label, *sources[index], cache) for label, index, cache in comparison_cases(args.comparison)] if args.mode == 'compare' else [
        ('current', *sources[0], args.cache_mode)]
    payload = {'run':run, 'date':args.date, 'smoke':args.smoke, 'comparison':args.comparison if args.mode == 'compare' else None,
               'analysisSha256':hashlib.sha256((args.harness/'run-site.py').read_bytes()).hexdigest(),
               'measurementMethodVersion':2,
               'harnessSha256':hashlib.sha256((args.harness/'site-load.js').read_bytes()+(args.harness/'site-fixture.py').read_bytes()).hexdigest(),
               'uncommittedChanges':bool(git('status','--porcelain')), 'runs':[]}
    built_images = {}
    for users in levels:
        args.users = users
        for repeat in range(args.repeats):
            rotated = variants[repeat // 2 % len(variants):] + variants[:repeat // 2 % len(variants)]
            order = rotated if repeat % 2 == 0 else list(reversed(rotated))
            for label, ref, sha, source, cache in order:
                folder = output / f'{run}-u{users}-{repeat+1}-{label}'
                images = (args.backend_image,args.frontend_image) if args.backend_image else built_images.get(sha)
                result = execute(args,source,folder,label,ref,sha,cache,images)
                if not args.backend_image and 'images' in result:
                    built_images[sha] = (result['images']['backend'], result['images']['frontend'])
                result['artifacts'] = folder.name
                result['repeat'] = repeat + 1
                payload['runs'].append(result)
                (output/'comparison.json').write_text(json.dumps(payload,ensure_ascii=False,indent=2))
    payload['capacity'] = capacity_summary(payload['runs'], levels, args.repeats, args.p95_limit_ms, args.smoke)
    payload['performance'] = performance_summary(payload['runs'], args.smoke)
    (output/'comparison.json').write_text(json.dumps(payload,ensure_ascii=False,indent=2))
    render_report(payload, output)
    print('Performance: '+payload['performance']['status'],flush=True)
    for comparison in payload['performance']['comparisons']:
        print(comparison['comparison']+': '+comparison['status']+' '+comparison.get('reason',''),flush=True)
    print(f'HTML: {output / "index.html"}', flush=True)
    return 0 if all(r['status']=='passed' for r in payload['runs']) else 1


def render_report(payload, output):
    rows = ''.join('<tr>'+''.join(f'<td>{html.escape(str(v))}</td>' for v in (
        r['label'],r.get('users','—'),(r['commit'] or 'image ID')[:12],r['status'],r.get('metrics',{}).get('requests','—'),
        r.get('metrics',{}).get('p95Ms','—'),r.get('metrics',{}).get('admissionWaitP95Ms','—'),
        r.get('metrics',{}).get('successfulBusinessRequestsPerSecond','—'),r.get('metrics',{}).get('databaseStatements','—'),
        r.get('metrics',{}).get('databaseStatementsPerBusinessRequest','—'),r.get('metrics',{}).get('failedChecks','—')))
        +f'<td><a href="{r["artifacts"]}/result.json">결과</a></td></tr>' for r in payload['runs'])
    (output/'index.html').write_text('<!doctype html><meta charset="utf-8"><title>브랜치별 전체 서비스 비교</title>'
        '<h1>브랜치별 전체 서비스 비교</h1><h2>성능 비교 판정</h2>'+performance_html(payload['performance'])+'<p>로컬 Docker 측정입니다. 스모크는 기능 확인이며 EC2 처리량을 의미하지 않습니다. '
        '실패한 실행은 개선율 계산에서 제외해야 합니다. p95는 모든 클라이언트 원시 표본을 합산했습니다.</p>'
        '<p>DB 개선: before-off ↔ final-off / Redis 효과: final-off ↔ final-on / 종합 효과: before-off ↔ final-on.</p>'
        '<p>SQL은 부하 구간의 테스트 DB 전체 문장 수이며 백그라운드 워커를 포함합니다. SQL/요청은 구간 비율이며 요청별 추적값이 아닙니다. '
        '고정 사용자 부하로 최대 처리량을 판정하지 않습니다. 단계별 부하는 지정 범위에서 지연·오류 기준을 통과한 최고 관측 처리량을 구합니다.</p>'
        '<p>30초 이상 예열·모드별 4회 이상 반복·API별 매회 200개 이상 표본이 있어야 비교합니다. 혼합 업무 전체 p95는 업무 구성에 영향을 받으므로 API별로 비교합니다. 지연은 측정 구간에 시작한 요청을 종료까지 추적하며, 요청률은 공통 측정 시간 안에 완료된 성공 요청 수/초입니다. 입장 제한을 포함한 고정 사용자 실험이며 일정 도착률이나 최대 처리량 실험이 아닙니다. 표의 passed는 기능 성공만 의미합니다.</p>'
        '<table border="1" cellpadding="8"><tr><th>구성</th><th>사용자</th><th>커밋</th><th>기능 결과</th><th>업무 요청</th><th>업무 p95(ms), 참고</th><th>입장 대기 p95(ms)</th><th>성공 업무 요청/s</th><th>SQL 수, 워커 포함</th><th>SQL/요청, 참고</th><th>실패</th><th>원본</th></tr>'+rows+'</table>'
        '<h2>단계별 부하 결과</h2>'+capacity_html(payload['capacity']))


def reanalyze(source):
    source = source.resolve()
    payload = json.loads((source/'comparison.json').read_text())
    # Preserve original summaries and raw data. The analyzer itself is copied for reproduction.
    output = source/'reanalysis-v2'
    output.mkdir(exist_ok=True)
    if Path(__file__).resolve() != (output/'analyzer.py').resolve():
        shutil.copyfile(Path(__file__),output/'analyzer.py')
    payload['analysisSha256'] = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
    payload['analysisSource'] = '../comparison.json'
    for run in payload['runs']:
        folder = source/run['artifacts']
        if not (folder/'window.json').exists():
            raise RuntimeError('Original run has no shared measurement window; cannot repair old methodology by reanalysis')
        run['metrics'].update(summarize(folder))
        run['metrics']['databaseStatementsPerBusinessRequest'] = None  # different SQL/request cohorts; diagnostic only
        if run['metrics']['allPhaseErrors'] != 0: run['status']='failed'
        artifact = output/run['artifacts']; artifact.mkdir(exist_ok=True)
        run['rawSource'] = '../../'+run['artifacts']
        (artifact/'result.json').write_text(json.dumps(run,ensure_ascii=False,indent=2))
    payload['performance'] = performance_summary(payload['runs'],payload['smoke'])
    levels = sorted({r['users'] for r in payload['runs']})
    payload['capacity'] = capacity_summary(payload['runs'],levels,max(r.get('repeat',1) for r in payload['runs']),
                                           payload.get('capacity',{}).get('p95LimitMs',1000),payload['smoke'])
    (output/'comparison.json').write_text(json.dumps(payload,ensure_ascii=False,indent=2))
    render_report(payload,output)
    print(f'Reanalyzed report: {output / "index.html"}',flush=True)
    return 0 if all(r['status']=='passed' for r in payload['runs']) else 1


if __name__ == '__main__':
    raise SystemExit(main())
