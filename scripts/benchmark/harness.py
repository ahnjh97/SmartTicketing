"""Standard-library HTTP fixtures, validation and statistics. No real API calls here."""
import base64
import hashlib
import hmac
import http.client
import json
import math
import statistics
import threading
import time
from collections import Counter
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlencode, urlsplit


def default_fixtures():
    scenarios = []
    for index, (name, lat, lon, count) in enumerate([
        ("seoul-3", 37.5665, 126.9780, 3),
        ("bundang-9", 37.3943, 127.1107, 9),
        ("suwon-15", 37.2636, 127.0286, 15),
    ]):
        theaters = []
        for i in range(count):
            brand, prefix = [("CGV", "CGV"), ("LOTTE_CINEMA", "롯데시네마"),
                             ("MEGABOX", "메가박스")][i % 3]
            theaters.append(dict(id=str(900000 + index * 100 + i), name=f"{prefix} fixture {i}",
                                 brand=brand, latitude=round(lat + (i + 1) * .0005, 7),
                                 longitude=round(lon + (i + 1) * .0003, 7),
                                 transitMinutes=10 + i, transitDistance=1000 + i * 100))
        scenarios.append(dict(name=name, latitude=lat, longitude=lon, theaters=theaters))
    return scenarios


def validate_fixtures(scenarios):
    if not isinstance(scenarios, list) or not scenarios:
        raise ValueError("Fixtures must be a nonempty scenario array")
    names, ids = set(), set()
    for s in scenarios:
        if s['name'] in names or not 1 <= len(s['theaters']) <= 15:
            raise ValueError("Unique scenario names and 1..15 theaters per scenario required")
        names.add(s['name'])
        for obj in [s] + s['theaters']:
            if not -90 <= obj['latitude'] <= 90 or not -180 <= obj['longitude'] <= 180:
                raise ValueError("Invalid coordinates")
        for t in s['theaters']:
            if str(t['id']) in ids or t['brand'] not in ('CGV', 'LOTTE_CINEMA', 'MEGABOX'):
                raise ValueError("Unique theater IDs and supported brands required")
            ids.add(str(t['id']))
            if t['transitMinutes'] <= 0 or t['transitDistance'] < 0:
                raise ValueError("Invalid expected route")
    # DB selects from the entire dataset, so all scenarios must have exactly these candidates.
    all_theaters = [t for s in scenarios for t in s['theaters']]
    for s in scenarios:
        actual = {str(t['id']) for t in all_theaters if distance(s, t) <= 10000}
        if actual != {str(t['id']) for t in s['theaters']}:
            raise ValueError("Scenario candidates do not match the DB 10km filter")


def distance(a, b):
    lat1, lat2 = math.radians(a['latitude']), math.radians(b['latitude'])
    dlat = lat2 - lat1
    dlon = math.radians(b['longitude'] - a['longitude'])
    x = math.sin(dlat / 2)**2 + math.cos(lat1) * math.cos(lat2) * math.sin(dlon / 2)**2
    return round(6371000 * 2 * math.atan2(math.sqrt(x), math.sqrt(1 - x)))


class FixtureServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, scenarios, route_ms=100, search_ms=50):
        super().__init__(('127.0.0.1', 0), FixtureHandler)
        self.scenarios, self.route_ms, self.search_ms = scenarios, route_ms, search_ms
        self.lock, self.counts = threading.Lock(), Counter()

    def snapshot(self):
        with self.lock:
            return dict(self.counts)


class FixtureHandler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def handle(self):
        try:
            super().handle()
        except (ConnectionResetError, BrokenPipeError):
            # The JVM closes its keep-alive connections during normal server shutdown.
            # A disconnect during a measured request is still recorded by the HTTP client.
            pass

    def log_message(self, *_):
        pass

    def do_GET(self):
        u = urlsplit(self.path)
        q = parse_qs(u.query)
        status = 200
        try:
            if u.path == '/v2/local/search/keyword.json':
                s = next(s for s in self.server.scenarios
                         if abs(s['latitude'] - float(q['y'][0])) < 1e-6
                         and abs(s['longitude'] - float(q['x'][0])) < 1e-6)
                brand = {'CGV': 'CGV', '롯데시네마': 'LOTTE_CINEMA', '메가박스': 'MEGABOX'}[q['query'][0]]
                body = {'documents': [dict(id=t['id'], place_name=t['name'], x=str(t['longitude']),
                        y=str(t['latitude']), distance=str(distance(s, t)), road_address_name=s['name'],
                        place_url='https://example.invalid/fixture') for t in s['theaters'] if t['brand'] == brand]}
                time.sleep(self.server.search_ms / 1000)
            elif u.path in ('/v2/routing/publictraffic', '/v2/routing/walk'):
                t = next(t for s in self.server.scenarios for t in s['theaters']
                         if abs(t['latitude'] - float(q['end_y'][0])) < 1e-6
                         and abs(t['longitude'] - float(q['end_x'][0])) < 1e-6)
                props = {'totalTime': t['transitMinutes'] * 60, 'totalDistance': t['transitDistance']}
                body = {'status': 'OK', 'routes': [{'properties': props}], 'route': {'properties': props}}
                time.sleep(self.server.route_ms / 1000)
            else:
                status, body = 404, {'error': 'Unknown fixture path'}
        except (KeyError, StopIteration, ValueError):
            status, body = 400, {'error': 'Unknown fixture query'}
        with self.server.lock:
            self.server.counts[u.path] += 1
            if status != 200:
                self.server.counts['errors'] += 1
        payload = json.dumps(body).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)


def token(secret):
    def enc(b):
        return base64.urlsafe_b64encode(b).rstrip(b'=')
    now = int(time.time())
    data = enc(b'{"alg":"HS256","typ":"JWT"}') + b'.' + enc(json.dumps(
        {'sub': '1', 'iss': 'SmartTicketing', 'type': 'access', 'iat': now, 'exp': now + 86400}).encode())
    return (data + b'.' + enc(hmac.new(secret.encode(), data, hashlib.sha256).digest())).decode()


class Client:
    def __init__(self, port, secret, timeout=120):
        self.connection = http.client.HTTPConnection('127.0.0.1', port, timeout=timeout)
        self.secret = secret

    def request(self, scenario):
        query = urlencode(dict(latitude=scenario['latitude'], longitude=scenario['longitude'],
                               address=scenario['name'], radius=10000, sort='TRANSIT'))
        status, body, error = 0, b'', ''
        headers = {'Authorization': 'Bearer ' + token(self.secret)}
        start = time.perf_counter_ns()
        try:
            self.connection.request('GET', '/api/theaters/nearby?' + query, headers=headers)
            response = self.connection.getresponse()
            status = response.status
            body = response.read()  # Stop clock BEFORE JSON parsing/validation.
        except (OSError, http.client.HTTPException) as exc:
            error = type(exc).__name__
            self.connection.close()
        elapsed = (time.perf_counter_ns() - start) / 1e6
        return status, body, elapsed, error

    def close(self):
        self.connection.close()


def validate_response(status, body, scenario, mode, original=False):
    if status != 200:
        return False, f'http_{status}', 0, ''
    try:
        rows = json.loads(body)
        if not isinstance(rows, list) or not rows:
            return False, 'empty_or_non_array', 0, ''
        ids = [str(r['kakaoPlaceId']) for r in rows]
        expected = {str(t['id']): t for t in scenario['theaters']}
        if len(set(ids)) != len(ids) or set(ids) != set(expected):
            return False, 'candidate_mismatch', len(rows), ''
        minutes = [r['transitMinutes'] for r in rows]
        if any(not isinstance(m, (int, float)) or isinstance(m, bool) or not math.isfinite(m) or m <= 0 for m in minutes):
            return False, 'missing_or_invalid_transit', len(rows), ''
        if minutes != sorted(minutes):
            return False, 'incorrect_sort', len(rows), ''
        for r in rows:
            if r.get('transitDistance') is None or r['transitDistance'] < 0:
                return False, 'missing_transit_distance', len(rows), ''
            if original and (r.get('walkMinutes') is None or r['walkMinutes'] <= 0):
                return False, 'missing_walk', len(rows), ''
            if mode == 'stub':
                t = expected[str(r['kakaoPlaceId'])]
                if r['transitMinutes'] != t['transitMinutes'] or r['transitDistance'] != t['transitDistance']:
                    return False, 'route_mismatch', len(rows), ''
        canonical = sorted((str(r['kakaoPlaceId']), r['transitMinutes'], r['transitDistance']) for r in rows)
        signature = hashlib.sha256(json.dumps(canonical).encode()).hexdigest()
        return True, '', len(rows), signature
    except (ValueError, TypeError, KeyError):
        return False, 'invalid_payload', 0, ''


def percentile(values, p):
    """Nearest-rank percentile; never interpolate or silently include invalid samples."""
    return sorted(values)[max(0, math.ceil(len(values) * p) - 1)] if values else None


def summarize(rows):
    groups = {}
    for row in rows:
        if row['phase'] != 'measure':
            continue
        for rnd in (row['round'], 'all'):
            groups.setdefault((row['branch'], row['scenario'], rnd), []).append(row)
    result = []
    for (branch, scenario, rnd), items in groups.items():
        times = [float(r['response_ms']) for r in items if str(r['valid']).lower() == 'true']
        result.append(dict(branch=branch, scenario=scenario, round=rnd, requests=len(items),
                           valid=len(times), error_pct=100 * (len(items) - len(times)) / len(items),
                           mean_ms=statistics.mean(times) if times else None,
                           p50_ms=percentile(times, .5), p95_ms=percentile(times, .95),
                           min_ms=min(times) if times else None, max_ms=max(times) if times else None))
    return result
