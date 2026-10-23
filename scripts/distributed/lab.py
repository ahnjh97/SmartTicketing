"""Run on EC2 A (or use up --role b on EC2 B). Uses only distributed_lab."""
import argparse
import base64
import concurrent.futures
import datetime as dt
import hashlib
import hmac
import importlib.util
import ipaddress
import json
import os
from pathlib import Path
import secrets
import subprocess
import threading
import time
import urllib.error
import urllib.request
import uuid
import checks

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
OUT = ROOT / '.local/distributed'


def settings():
    result = {}
    for line in (HERE / '.env').read_text().splitlines():
        if line.strip() and not line.lstrip().startswith('#'):
            key, value = line.split('=', 1)
            result[key.strip()] = value.strip()
    validate_settings(result)
    return result


def private_ip(value):
    """EC2 public addresses are NAT addresses and cannot be used for host binds."""
    address = ipaddress.IPv4Address(value)
    ranges = ('10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16')
    if not any(address in ipaddress.IPv4Network(network) for network in ranges):
        raise ValueError('Use the EC2 RFC1918 private IPv4 address, not its public IP')
    return str(address)


def validate_settings(config):
    for key in ('SERVER_A_IP', 'SERVER_B_IP'):
        private_ip(config[key])
    if config['SERVER_A_IP'] == config['SERVER_B_IP']:
        raise ValueError('A and B must be separate EC2 instances with different private IPs')
    for key in ('MYSQL_PASSWORD', 'REDIS_PASSWORD', 'JWT_SECRET'):
        if not config.get(key) or config[key].startswith('change-this-'):
            raise ValueError(f'Set {key}; run init once on A and copy the same .env to B')
    if len(config['JWT_SECRET'].encode()) < 32:
        raise ValueError('JWT_SECRET must contain at least 32 bytes')
    if config.get('LAB_MODE', 'two') not in ('one', 'two'):
        raise ValueError('LAB_MODE must be one or two')


def endpoints(config):
    return (os.environ.get('LAB_A_URL', f"http://{config['SERVER_A_IP']}:8080"),
            os.environ.get('LAB_B_URL', f"http://{config['SERVER_B_IP']}:8080"),
            os.environ.get('LAB_PROXY_URL', f"http://{config['SERVER_A_IP']}:8088"))


def compose(*args, input=None, role='a'):
    if role not in ('a', 'b'):
        raise ValueError('role must be a or b')
    result = subprocess.run(['docker', 'compose', '--env-file', str(HERE / '.env'),
                           '-f', str(HERE / f'compose.{role}.yml'), *args],
                          input=input, text=True, capture_output=True)
    if result.returncode:
        raise RuntimeError(f'Compose {args[0]} failed: {result.stderr[-3000:]}')
    return result.stdout.strip()


def sql(query):
    return compose('exec', '-T', 'mysql', 'sh', '-c',
                   'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql -ulab -N -B distributed_lab', input=query)


def token(user, secret):
    def enc(value):
        return base64.urlsafe_b64encode(json.dumps(value, separators=(',', ':')).encode()).decode().rstrip('=')
    now = int(time.time())
    body = enc({'alg': 'HS256'}) + '.' + enc({'iss': 'SmartTicketing', 'sub': str(user),
                                           'iat': now, 'exp': now + 86400, 'type': 'access'})
    sig = base64.urlsafe_b64encode(hmac.new(secret.encode(), body.encode(), hashlib.sha256).digest()).decode().rstrip('=')
    return body + '.' + sig


def request(base, path, user=1, body=None, method=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(base + path, data=data, method=method or ('POST' if data else 'GET'),
                                 headers={'Authorization': 'Bearer ' + token(user, settings()['JWT_SECRET']),
                                          'Content-Type': 'application/json', 'Idempotency-Key': str(uuid.uuid4())})
    try:
        with urllib.request.urlopen(req, timeout=10) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode()


def ok(result, codes=(200, 201)):
    status, body = result
    if status not in codes:
        raise AssertionError(f'Unexpected HTTP {status}: {body}')
    return body


def group(base, user, show):
    fixture = json.loads((OUT / 'fixture.json').read_text())
    return ok(request(base, '/api/booking-groups', user, {
        'entryPoint': 'THEATER_NORMAL', 'movieId': 1, 'viewingDate': fixture['date'],
        'partySize': 1, 'selectedShowtimeId': show, 'audience': {'adultCount': 1, 'youthCount': 0}}))['id']


def invariant():
    duplicates = int(sql("""SELECT COUNT(*) FROM (
        SELECT r.showtime_id, rs.seat_id FROM reservation_seats rs
        JOIN reservations r ON r.id=rs.reservation_id
        WHERE r.status IN ('PENDING','CONFIRMED')
        GROUP BY r.showtime_id,rs.seat_id HAVING COUNT(*)>1) d;"""))
    mismatch = int(sql("""SELECT COUNT(*) FROM showtimes s WHERE s.available_seats <>
        (SELECT COUNT(*) FROM showtime_seats i WHERE i.showtime_id=s.id AND i.status='AVAILABLE');"""))
    assert duplicates == 0 and mismatch == 0, (duplicates, mismatch)
    return {'duplicateActiveSeats': duplicates, 'inventoryMismatches': mismatch}


def race(a, b, clients):
    jobs = [(u, a if u % 2 else b) for u in range(1, clients + 1)]
    groups = [(u, base, group(base, u, 1)) for u, base in jobs]
    barrier = threading.Barrier(len(groups))
    def hold(item):
        user, base, gid = item
        barrier.wait(timeout=30)
        return user, base, request(base, f'/api/booking-groups/{gid}/manual-hold', user, {'seatIds': [1]})
    with concurrent.futures.ThreadPoolExecutor(max_workers=len(groups)) as pool:
        results = list(pool.map(hold, groups))
    winners = [(u, base, result[1]) for u, base, result in results if result[0] in (200, 201)]
    assert len(winners) == 1, results
    assert all(r[0] in (200, 201, 409) for _, _, r in results), results
    observed = invariant()
    user, base, reservation = winners[0]
    # Read through the other backend before cancelling.
    remote = ok(request(b if base == a else a, f"/api/reservations/{reservation['id']}", user))
    assert remote['seatIds'] == [1], remote
    ok(request(base, f"/api/reservations/{reservation['id']}/cancel", user, method='POST'))
    return {'attempts': len(results), 'successfulHolds': len(winners),
            'conflicts': sum(r[0] == 409 for _, _, r in results),
            'requestsToA': sum(base == a for _, base, _ in results),
            'requestsToB': sum(base == b for _, base, _ in results), **observed}


def notification(a, b):
    # Disable only A's outbox for this experiment so B must perform the allocation.
    assert compose('exec', '-T', 'backend-a', 'printenv', 'BOOKING_OUTBOX_ENABLED') == 'false', \
        'Recreate A with A_OUTBOX_ENABLED=false before notification/recovery verification'
    owner, waiter = 101, 102
    gid = group(b, owner, 2)
    held = ok(request(b, f'/api/booking-groups/{gid}/manual-hold', owner, {'seatIds': [101]}))
    waiting = group(b, waiter, 2)
    ok(request(b, f'/api/booking-groups/{waiting}/waiting-queues', waiter,
               {'showtimeIds': [2], 'seatIds': [101]}))
    connected, delivered = threading.Event(), threading.Event()
    errors = []
    def listen():
        try:
            req = urllib.request.Request(a + '/api/notifications/stream', headers={
                'Authorization': 'Bearer ' + token(waiter, settings()['JWT_SECRET'])})
            with urllib.request.urlopen(req, timeout=45) as response:
                for line in response:
                    if line.strip() == b'event:connected': connected.set()
                    if line.strip() == b'event:notification':
                        delivered.set()
                        return
        except Exception as error:
            errors.append(str(error))
    thread = threading.Thread(target=listen, daemon=True)
    thread.start()
    assert connected.wait(15), errors
    started = time.monotonic()
    ok(request(b, f"/api/reservations/{held['id']}/cancel", owner, method='POST'))
    assert delivered.wait(35), errors
    elapsed = (time.monotonic() - started) * 1000
    notifications = ok(request(a, '/api/notifications', waiter))
    assert any(n['groupId'] == waiting for n in notifications), notifications
    result = ok(request(a, f'/api/booking-groups/{waiting}', waiter))
    assert result['activeReservationId'], result
    return {'cancelToSseMs': elapsed, 'databaseNotificationVisible': True,
            'groupId': waiting, 'reservationId': result['activeReservationId'], **invariant()}


def recovery():
    assert compose('exec', '-T', 'backend-a', 'printenv', 'BOOKING_OUTBOX_ENABLED') == 'false'
    # Deterministic abandoned-lease injection, NOT evidence of a real process crash.
    event = sql("SELECT id FROM booking_outbox_events WHERE showtime_id=2 AND status='COMPLETED' ORDER BY id DESC LIMIT 1;")
    assert event.isdigit(), 'Run notification verification first and wait for outbox completion'
    before = sql('SELECT COUNT(*) FROM notifications;')
    sql(f"UPDATE booking_outbox_events SET status='PROCESSING',lease_token='{uuid.uuid4()}',"
        f"lease_until=TIMESTAMPADD(SECOND,5,NOW()),completed_at=NULL WHERE id={int(event)};")
    started = time.monotonic()
    status = ''
    while time.monotonic() - started < 45:
        status = sql(f'SELECT status FROM booking_outbox_events WHERE id={int(event)};')
        if status == 'COMPLETED': break
        time.sleep(.25)
    assert status == 'COMPLETED', status
    assert before == sql('SELECT COUNT(*) FROM notifications;'), 'Duplicate notification after replay'
    return {'mode': 'synthetic abandoned lease + replay, not process crash',
            'recoveryMsIncludingLease': (time.monotonic() - started) * 1000, **invariant()}


def outage():
    a, b, base = endpoints(settings())
    ok(request(a, '/api/health/readiness'))
    ok(request(b, '/api/health/readiness'))
    ok(request(base, '/api/showtimes/1/seats'))
    samples = []
    # Manual stop suppresses restart:unless-stopped; a raw kill can auto-restart A.
    compose('stop', '--timeout', '0', 'backend-a')
    started = time.monotonic()
    try:
        for _ in range(30):
            sent = time.monotonic()
            try:
                status, _ = request(base, '/api/showtimes/1/seats')
            except Exception:
                status = 0
            samples.append({'sinceKillMs': (sent-started)*1000, 'status': status,
                            'latencyMs': (time.monotonic()-sent)*1000})
            time.sleep(.2)
        assert any(s['status'] == 200 for s in samples), samples
    finally:
        (OUT / f'{time.time_ns()}-outage-samples.json').write_text(json.dumps(samples, indent=2))
        compose('start', 'backend-a')
        deadline = time.monotonic() + 180
        while True:
            try:
                ok(request(a, '/api/health/readiness'))
                break
            except Exception:
                if time.monotonic() >= deadline: raise
                time.sleep(1)
    return {'mode': 'actual backend A forced stop (timeout=0); shared DB/Redis remain running', 'samples': samples,
            'failedRequests': sum(s['status'] != 200 for s in samples),
            'firstSuccessfulRequestMs': next(s['sinceKillMs'] + s['latencyMs'] for s in samples if s['status'] == 200)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['init', 'config', 'up', 'fixture', 'preflight', 'balancing', 'admission', 'race', 'notification', 'recovery', 'outage', 'invariant'])
    parser.add_argument('--server-a-ip', type=private_ip, help='EC2 A private IPv4 (init only)')
    parser.add_argument('--server-b-ip', type=private_ip, help='EC2 B private IPv4 (init only)')
    parser.add_argument('--role', choices=['a', 'b'], help='Local EC2 role (required for up)')
    parser.add_argument('--clients', type=int, help='Explicit client count; race is limited to the 200-user fixture')
    parser.add_argument('--requests', type=int, help='Explicit total request count for balancing')
    parser.add_argument('--rounds', type=int, default=1, help='Number of race rounds, 1..100')
    args = parser.parse_args()
    if args.action in ('balancing', 'admission', 'race') and args.clients is None:
        parser.error('Choose --clients after preparing the EC2 instances')
    if args.clients is not None and args.clients < 1:
        parser.error('--clients must be positive')
    if args.action == 'race' and not 2 <= args.clients <= 200:
        parser.error('race requires 2..200 clients (fixture limit, not a server capacity limit)')
    if args.action == 'balancing' and (args.requests is None or args.requests < max(2, args.clients)):
        parser.error('balancing requires --requests >= max(2, --clients)')
    if not 1 <= args.rounds <= 100:
        parser.error('--rounds must be between 1 and 100')
    OUT.mkdir(parents=True, exist_ok=True)
    if args.action == 'init':
        if not args.server_a_ip or not args.server_b_ip:
            parser.error('init requires --server-a-ip and --server-b-ip (EC2 private IPv4)')
        if args.server_a_ip == args.server_b_ip:
            parser.error('A and B must have different EC2 private IPs')
        target = HERE / '.env'
        content = (HERE / '.env.example').read_text()
        content = content.replace('SERVER_A_IP=10.0.1.10', f'SERVER_A_IP={args.server_a_ip}')
        content = content.replace('SERVER_B_IP=10.0.1.11', f'SERVER_B_IP={args.server_b_ip}')
        for placeholder in ['change-this-lab-password', 'change-this-lab-redis-password',
                            'change-this-lab-jwt-secret-at-least-32-bytes']:
            content = content.replace(placeholder, secrets.token_hex(32))
        descriptor = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, 'w') as file: file.write(content)
        print('Created .env with EC2 private IPs. Copy the SAME file securely to EC2 B.')
        return
    config = settings()
    a, b, proxy = endpoints(config)
    if args.action == 'config':
        print(json.dumps({'a': a, 'b': b, 'proxy': proxy,
                          'image': config.get('BACKEND_IMAGE'),
                          'mode': config.get('LAB_MODE', 'two')}, indent=2))
        return
    if args.action == 'up':
        if not args.role:
            parser.error('up requires --role a or --role b; run on the corresponding EC2')
        compose('config', '--quiet', role=args.role)
        compose('up', '-d', '--no-build', '--pull', 'never', '--wait', '--wait-timeout', '600', role=args.role)
        if args.role == 'a':
            # Nginx resolves backend-a at startup; a recreated backend can get a new container IP.
            compose('up', '-d', '--no-deps', '--pull', 'never', '--force-recreate', 'nginx')
        print(compose('ps', role=args.role))
        return
    if args.action == 'fixture':
        assert sql('SELECT COUNT(*) FROM users;') == '0', 'Fixture requires a fresh dedicated DB; no existing data is deleted'
        spec = importlib.util.spec_from_file_location('fixture', HERE.parent / 'benchmark/site-fixture.py')
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        query, fixture = module.fixture(200, (dt.date.today() + dt.timedelta(days=1)).isoformat())
        sql(query)
        fixture['tokens'] = {str(u): token(u, config['JWT_SECRET']) for u in range(1, 201)}
        fixture['generatedAt'] = dt.datetime.now(dt.timezone.utc).isoformat()
        (OUT / 'fixture.json').write_text(json.dumps(fixture))
        print('Created fixture.json (tokens expire in 24h). Keep on A; copy only if using a separate load generator. Do not commit it.')
        return
    result = {'action': args.action, 'startedAt': dt.datetime.now(dt.timezone.utc).isoformat(), 'a': a, 'b': b}
    try:
        if args.action == 'preflight':
            for url in [a, b, proxy]:
                ok(request(url, '/api/health/readiness'))
            result['checks'] = {'bothBackendsAndProxyReady': True}
        elif args.action == 'balancing':
            result['checks'] = checks.balancing(proxy, f"{config['SERVER_B_IP']}:8080", args.requests, args.clients)
        elif args.action == 'admission':
            capacity = int(config.get('ADMISSION_CAPACITY', '100'))
            result['checks'] = checks.admission(a, b, proxy, capacity, args.clients)
        elif args.action == 'race':
            # The booking test isolates database concurrency from the admission gate.
            for url in (a, b):
                state = ok(request(url, '/api/admission/status'))
                assert state['state'] == 'DISABLED', 'Disable admission on BOTH backends before race'
            rounds = []
            result['checks'] = {'rounds': rounds}
            for _ in range(args.rounds):
                rounds.append(race(a, b, args.clients))
        elif args.action == 'notification': result['checks'] = notification(a, b)
        elif args.action == 'recovery': result['checks'] = recovery()
        elif args.action == 'outage': result['checks'] = outage()
        else: result['checks'] = invariant()
        result['status'] = 'passed'
    except Exception as error:
        result.update(status='failed', error=str(error))
        raise
    finally:
        path = OUT / f"{time.time_ns()}-{args.action}.json"
        path.write_text(json.dumps(result, indent=2))
        print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
