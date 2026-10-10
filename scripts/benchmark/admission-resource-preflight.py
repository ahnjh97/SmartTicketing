#!/usr/bin/env python3
"""Local Docker resource check and optional load before increasing admission.

Run as root on Linux with systemd/cgroup v2 and prebuilt JAR/frontend assets.
The shared slice includes MySQL, both Redis instances, Java and both Nginx
containers. The default 896 MiB leaves 128 MiB of a 1 GiB machine for its OS;
this is a resource stress test, not an EC2 performance equivalence claim.
Only uniquely named resources created by this invocation are removed.
"""
import argparse
import hashlib
import importlib.util
import json
import pathlib
import secrets
import subprocess
import time
import uuid


def command(*args, check=True):
    result = subprocess.run(args, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if check and result.returncode:
        raise RuntimeError(f'{args[0]} failed: {result.stdout[-3000:]}')
    return result.stdout.strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--backend-image', required=True, help='Existing production Java runtime image; current JAR is mounted over its JAR')
    parser.add_argument('--memory-mib', type=int, default=896)
    parser.add_argument('--machine-memory-mib', type=int, default=1024)
    parser.add_argument('--capacity', type=int, default=100)
    parser.add_argument('--timeout', type=int, default=180)
    parser.add_argument('--load', action='store_true', help='Run ten distinct-IP k6 clients outside the server resource slice')
    parser.add_argument('--duration', type=int, default=120)
    parser.add_argument('--load-cpu-percent', type=int, default=200, help='Shared CPU quota during load (40 approximates small baseline, not Unlimited mode)')
    args = parser.parse_args()
    root = pathlib.Path(__file__).resolve().parents[2]
    jar = root / 'build/libs/SmartTicketing-0.0.1-SNAPSHOT.jar'
    if not jar.is_file() or not (root / 'frontend/dist/index.html').is_file():
        raise RuntimeError('Build bootJar and frontend first')
    run = 'stadmission' + uuid.uuid4().hex[:12]
    folder = root / 'benchmark-results' / run
    folder.mkdir(parents=True)
    slice_name = run + '.slice'
    cgroup = pathlib.Path('/sys/fs/cgroup') / slice_name
    containers = []
    generators = []
    result = {'run': run, 'memoryMiB': args.memory_mib, 'cpuQuota': '200%',
              'machineMemoryMiB': args.machine_memory_mib,
              'durationSeconds': args.duration,
              'loadCpuPercent': args.load_cpu_percent,
              'swapBytes': 0, 'capacity': args.capacity,
              'jarSha256': hashlib.sha256(jar.read_bytes()).hexdigest(),
              'scope': 'seeded browse/smart-hold/cancel load' if args.load else 'startup/readiness preflight; no seeded booking load', 'samples': []}
    if args.capacity not in (100, 150, 200):
        raise ValueError('Use a planned capacity of 100, 150 or 200')
    password = secrets.token_hex(24)

    def docker(*items, **kwargs):
        return command('docker', *items, **kwargs)

    def start(service, image, options=(), entry=()):
        name = run + '-' + service
        containers.append(name)
        docker('run', '-d', '--pull', 'never', '--name', name, '--network', run,
               '--network-alias', service, '--cgroup-parent', slice_name,
               '--memory', f'{args.machine_memory_mib}m', '--memory-swap', f'{args.machine_memory_mib}m', '--cpus', '2',
               *options, image, *entry)
        return name

    def sample():
        data = {'seconds': round(time.monotonic() - started, 1)}
        for file in ['memory.current', 'memory.peak', 'memory.events', 'memory.stat', 'memory.pressure', 'cpu.stat']:
            path = cgroup / file
            if path.exists():
                data[file] = path.read_text().strip()
        data['containers'] = [json.loads(docker('inspect', '--format', '{{json .State}}', name))
                              | {'name': name} for name in containers]
        result['samples'].append(data)
        return data

    started = time.monotonic()
    try:
        result['runtimeImage'] = json.loads(docker('image', 'inspect', '--format', '{{json .}}', args.backend_image))['Id']
        command('systemctl', 'start', slice_name)
        command('systemctl', 'set-property', '--runtime', slice_name,
                f'MemoryMax={args.memory_mib}M', 'MemorySwapMax=0', 'CPUQuota=200%')
        result['limits'] = {file: (cgroup / file).read_text().strip()
                            for file in ['memory.max', 'memory.swap.max', 'cpu.max']}
        docker('network', 'create', run)
        mysql = start('mysql', 'mysql:8.4', ('-e', f'MYSQL_ROOT_PASSWORD={password}', '-e', 'MYSQL_DATABASE=admission_test'))
        start('redis', 'redis:7-alpine', entry=('redis-server', '--appendonly', 'yes'))
        start('admission-redis', 'redis:7-alpine', entry=('redis-server', '--appendonly', 'yes', '--maxmemory', '128mb', '--maxmemory-policy', 'noeviction'))
        for _ in range(90):
            state = sample()
            if any(not x['Running'] for x in state['containers']):
                raise RuntimeError('Infrastructure container stopped before backend startup')
            ping = subprocess.run(['docker', 'exec', '-e', f'MYSQL_PWD={password}', mysql,
                                   'mysql', '--protocol=TCP', '-h', '127.0.0.1', '-uroot', '-e', 'SELECT 1'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            if ping.returncode == 0:
                break
            time.sleep(2)
        else:
            raise RuntimeError('MySQL readiness timeout')
        backend = start('backend', args.backend_image, (
            '-v', f'{jar}:/app/app.jar:ro',
            '-e', 'DB_URL=jdbc:mysql://mysql:3306/admission_test?serverTimezone=Asia/Seoul',
            '-e', 'DB_USERNAME=root', '-e', f'DB_PASSWORD={password}',
            '-e', 'JWT_SECRET=local-admission-test-secret-012345678901234567890123456789',
            '-e', 'JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=2',
            '-e', 'ADMIN_KEY=local-only', '-e', 'SPRING_DATA_REDIS_HOST=redis',
            '-e', 'ADMISSION_ENABLED=true', '-e', 'ADMISSION_SECURE_COOKIE=true',
            '-e', 'ADMISSION_REDIS_HOST=admission-redis', '-e', f'ADMISSION_CAPACITY={args.capacity}'),
            ('--tmdb.auto-import=false', '--kakao.catalog.auto-import=false', '--showtime.seed.enabled=false'))
        start('frontend', 'nginx:alpine', (
            '-v', f'{root}/frontend/dist:/usr/share/nginx/html:ro',
            '-v', f'{root}/frontend/nginx.conf:/etc/nginx/conf.d/default.conf:ro'))
        certs = folder / 'certs'
        certs.mkdir()
        command('openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-keyout', str(certs / 'privkey.pem'),
                '-out', str(certs / 'fullchain.pem'), '-days', '1', '-subj', '/CN=smartticketing.duckdns.org')
        start('nginx', 'nginx:alpine', (
            '-v', f'{root}/deploy/nginx/default.conf:/etc/nginx/conf.d/default.conf:ro',
            '-v', f'{certs}:/etc/letsencrypt/live/smartticketing.duckdns.org:ro'))
        deadline = time.monotonic() + args.timeout
        while time.monotonic() < deadline:
            state = sample()
            print(json.dumps({'seconds': state['seconds'], 'memory': state.get('memory.current'),
                              'stopped': [s['name'] for s in state['containers'] if not s['Running']]}), flush=True)
            if any(not x['Running'] for x in state['containers']):
                result['outcome'] = 'container-stopped'
                break
            ready = subprocess.run(['docker', 'exec', backend, 'curl', '-fsS', '--max-time', '2',
                                    'http://localhost:8080/api/health/readiness'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            if ready.returncode == 0:
                result['outcome'] = 'ready-requires-seeded-load-test'
                break
            time.sleep(3)
        else:
            result['outcome'] = 'readiness-timeout'
        if args.load and result['outcome'] == 'ready-requires-seeded-load-test':
            spec = importlib.util.spec_from_file_location('fixture', root / 'scripts/benchmark/admission-resource-fixture.py')
            fixture_module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(fixture_module)
            sql, fixture_data = fixture_module.fixture()
            seeded = subprocess.run(['docker', 'exec', '-i', '-e', f'MYSQL_PWD={password}', mysql,
                                     'mysql', '-uroot', 'admission_test'], input=sql, text=True,
                                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
            if seeded.returncode:
                raise RuntimeError('Fixture failed: ' + seeded.stdout[-2000:])
            (folder / 'fixture.json').write_text(json.dumps(fixture_data))
            command('systemctl', 'set-property', '--runtime', slice_name, f'CPUQuota={args.load_cpu_percent}%')
            for shard in range(10):
                name = run + '-load-' + str(shard)
                generators.append(name)
                docker('run', '-d', '--pull', 'never', '--name', name, '--network', run,
                       '--user', '0:0', '-v', f'{folder}:/results',
                       '-v', f'{root}/scripts/benchmark/admission-resource-load.js:/load.js:ro',
                       '-e', f'SHARD={shard}', '-e', f'VUS={args.capacity//10}', '-e', f'DURATION={args.duration}s',
                       'grafana/k6:2.3.0', 'run', '--quiet', '--no-usage-report', '/load.js')
            result['loadExitCodes'] = {}
            try:
                deadline = time.monotonic() + args.duration + 45
                while time.monotonic() < deadline:
                    state = sample()
                    states = {name: json.loads(docker('inspect', '--format', '{{json .State}}', name)) for name in generators}
                    print(json.dumps({'loadSeconds': state['seconds'], 'memory': state.get('memory.current'),
                                      'serverStopped': [s['name'] for s in state['containers'] if not s['Running']]}), flush=True)
                    if any(not s['Running'] for s in state['containers']):
                        result['outcome'] = 'load-container-stopped'
                        break
                    if all(not s['Running'] for s in states.values()):
                        result['outcome'] = 'load-complete'
                        result['loadExitCodes'] = {name: s['ExitCode'] for name, s in states.items()}
                        break
                    time.sleep(5)
                else:
                    result['outcome'] = 'load-timeout'
            finally:
                for name in generators:
                    (folder / (name + '.log')).write_text(docker('logs', name, check=False))
                    docker('rm', '-f', name, check=False)
                generators.clear()
            final_query = subprocess.run(['docker', 'exec', '-e', f'MYSQL_PWD={password}', mysql,
                'mysql', '-uroot', '--batch', 'admission_test', '-e',
                "SELECT status,COUNT(*) FROM reservations GROUP BY status; "
                "SELECT status,COUNT(*) FROM showtime_seats GROUP BY status; "
                "SELECT status,COUNT(*) FROM booking_outbox_events GROUP BY status;"],
                text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
            result['finalDatabaseState'] = final_query.stdout
            result['finalDatabaseQueryExitCode'] = final_query.returncode
    except Exception as error:
        result['outcome'] = 'preflight-error'
        result['error'] = str(error).replace(password, '[redacted]')
    finally:
        for name in generators:
            docker('rm', '-f', name, check=False)
        reports = [json.loads(path.read_text()) for path in folder.glob('load-*.json')]
        if reports:
            def value(report, metric, field):
                return report['metrics'].get(metric, {}).get('values', {}).get(field, 0)
            failed = sum(value(r, 'business_failures', 'passes') for r in reports)
            total = failed + sum(value(r, 'business_failures', 'fails') for r in reports)
            result['summary'] = {
                'clientReports': len(reports), 'businessRequests': total,
                'failedBusinessRequests': failed,
                'admittedUsers': sum(value(r, 'admitted_users', 'count') for r in reports),
                'completedSmartCycles': sum(value(r, 'completed_smart_cycles', 'count') for r in reports),
                'worstClientP95Ms': max(value(r, 'business_latency', 'p(95)') for r in reports),
                'allThresholdsPassed': len(reports) == 10 and all(
                    threshold['ok'] for r in reports for metric in r['metrics'].values()
                    for threshold in metric.get('thresholds', {}).values()),
            }
        for name in containers:
            (folder / (name + '.log')).write_text(docker('logs', '--tail', '300', name, check=False).replace(password, '[redacted]'))
        (folder / 'result.json').write_text(json.dumps(result, indent=2))
        for name in reversed(containers):
            docker('rm', '-fv', name, check=False)
        docker('network', 'rm', run, check=False)
        command('systemctl', 'stop', slice_name, check=False)
        command('systemctl', 'revert', slice_name, check=False)
        print(json.dumps({'outcome': result.get('outcome'), 'result': str(folder / 'result.json')}), flush=True)


if __name__ == '__main__':
    main()
