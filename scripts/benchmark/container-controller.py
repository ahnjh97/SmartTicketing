"""Host-side lifecycle controller for isolated fixtures, production JAR and k6 containers."""
import json
import os
from pathlib import Path
import subprocess
import sys
import time

root = Path(os.environ['BENCHMARK_OUTPUT']).resolve()
control = root / 'control'
control.mkdir(exist_ok=True)
compose = sys.argv[1:]

def run(args, **kwargs):
    return subprocess.run(args, check=True, **kwargs)

def output(args):
    return run(args, capture_output=True, text=True).stdout.strip()

def result_path(value):
    path = Path(value)
    relative = path.relative_to('/results')
    resolved = (root / relative).resolve()
    if not resolved.is_relative_to(root):
        raise ValueError('Output outside this run')
    resolved.parent.mkdir(parents=True, exist_ok=True)
    return resolved

backend = None

def redis_stats():
    info = output(compose + ['exec', '-T', 'redis', 'redis-cli', '--raw', 'INFO', 'stats'])
    values = dict(line.split(':', 1) for line in info.splitlines() if ':' in line)
    return {key: int(values.get(key, 0)) for key in ('keyspace_hits', 'keyspace_misses')}

def stop(evidence):
    global backend
    if backend:
        with result_path(evidence + '.log').open('w') as log:
            subprocess.run(['docker', 'logs', backend], stdout=log, stderr=subprocess.STDOUT)
        run(['docker', 'rm', '-f', backend], stdout=subprocess.DEVNULL)
        backend = None

def handle(req):
    global backend
    if req['action'] == 'start':
        if backend:
            raise RuntimeError('Previous backend still running')
        # This Compose project owns its Redis volume; never touches a developer service.
        run(compose + ['exec', '-T', 'redis', 'redis-cli', 'FLUSHDB'], stdout=subprocess.DEVNULL)
        aof = output(compose + ['exec', '-T', 'redis', 'redis-cli', '--raw', 'CONFIG', 'GET', 'appendonly']).splitlines()[-1]
        if aof != 'yes':
            raise RuntimeError('Redis AOF must match deployment (appendonly yes)')
        backend = output(compose + ['run', '-d', '--no-deps', '--use-aliases', 'backend'] + req['args']).splitlines()[-1]
        info = json.loads(output(['docker', 'inspect', backend]))[0]
        # Record only non-secret settings; request payloads are deleted immediately.
        cache = next(a.split('=', 1)[1] for a in req['args'] if a.startswith('--app.cache.enabled='))
        evidence = {'containerId': backend, 'imageId': info['Image'], 'entrypoint': info['Config']['Entrypoint'],
                    'cacheEnabled': cache == 'true', 'profile': 'default', 'redisAof': aof == 'yes',
                    'cpuLimit': info['HostConfig']['NanoCpus'] / 1e9, 'memoryLimitBytes': info['HostConfig']['Memory'],
                    'execution': 'production-prebuilt-jar', 'baseUrl': 'http://backend:8080'}
        result_path(req['evidence'] + '.json').write_text(json.dumps(evidence, indent=2))
        deadline = time.monotonic() + 180
        while time.monotonic() < deadline:
            if output(['docker', 'inspect', '-f', '{{.State.Running}}', backend]) != 'true':
                break
            ready = subprocess.run(['docker', 'exec', backend, 'curl', '--fail', '--silent', '--max-time', '3',
                                    'http://localhost:8080/api/health/readiness'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            if ready.returncode == 0:
                print('BENCH_BACKEND production JAR ready; Redis=' + cache, flush=True)
                return evidence
            time.sleep(1)
        stop(req['evidence'])
        raise RuntimeError('Production backend failed readiness; inspect backend log')
    if req['action'] == 'stop':
        stop(req['evidence'])
        return {}
    if req['action'] == 'k6':
        if not backend:
            raise RuntimeError('Backend not started')
        # Environment is passed by name, so temporary JWTs do not appear in process arguments.
        env = os.environ.copy()
        env.update(req['env'])
        args = compose + ['run', '--rm', '-T', '--no-deps']
        for key in req['env']:
            args += ['-e', key]
        before = redis_stats()
        with result_path(req['log']).open('w') as log:
            process = subprocess.run(args + ['k6'] + req['args'], env=env, stdout=log, stderr=subprocess.STDOUT)
        after = redis_stats()
        result_path(req['log']).with_suffix('.redis.json').write_text(json.dumps(
            {key: after[key] - before[key] for key in before}, indent=2))
        return {'exitCode': process.returncode}
    raise ValueError('Unknown controller action')

def main():
    runner = subprocess.Popen(compose + ['run', '--rm', '-T', '--no-deps', 'runner'])
    try:
        while runner.poll() is None:
            for path in control.glob('*.request.json'):
                try:
                    req = json.loads(path.read_text())
                    path.unlink()
                    response = handle(req)
                except Exception as exc:
                    response = {'error': str(exc)}
                destination = path.with_name(path.name.replace('.request.', '.response.'))
                pending = destination.with_suffix('.tmp')
                pending.write_text(json.dumps(response))
                pending.replace(destination)
            time.sleep(0.1)
        sys.exit(runner.returncode)
    finally:
        if runner.poll() is None:
            runner.terminate()
            runner.wait(timeout=15)
        stop('/results/interrupted-backend')
        for pattern in ('*.request.json', '*.response.json', '*.tmp'):
            for path in control.glob(pattern):
                path.unlink()

if __name__ == "__main__":
    main()
