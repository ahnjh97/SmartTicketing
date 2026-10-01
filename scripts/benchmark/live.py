"""Real Seoul catalog/scenarios. No fabricated theater coordinates or route durations."""
import csv
import math

from harness import distance


def default_origins():
    return [dict(name='seoul-cityhall', latitude=37.5665, longitude=126.9780),
            dict(name='seoul-gangnam', latitude=37.4979, longitude=127.0276),
            dict(name='seoul-hongdae', latitude=37.5572, longitude=126.9245)]


def read_catalog(path):
    with path.open(encoding='utf-8-sig', newline='') as stream:
        rows = list(csv.DictReader(stream))
    if not rows:
        raise ValueError('No active Seoul theaters with coordinates in the source DB. Load the team catalog first.')
    ids = set()
    for row in rows:
        if not row['id'] or row['id'] in ids or row['brand'] not in ('CGV', 'LOTTE_CINEMA', 'MEGABOX'):
            raise ValueError('Source catalog has missing/duplicate place IDs or unsupported brands')
        ids.add(row['id'])
        if not row['address'].startswith('서울'):
            raise ValueError('Source catalog must contain Seoul theaters only')
        for key, limit in [('latitude', 90), ('longitude', 180)]:
            row[key] = float(row[key])
            if not math.isfinite(row[key]) or not -limit <= row[key] <= limit:
                raise ValueError('Invalid source coordinates')
    return rows


def live_scenarios(origins, catalog):
    if not isinstance(origins, list) or not origins:
        raise ValueError('Origins must be a nonempty array')
    names, scenarios = set(), []
    for origin in origins:
        if not origin.get('name') or origin['name'] in names or 'theaters' in origin:
            raise ValueError('Live origins need unique names and must not embed theater fixtures')
        names.add(origin['name'])
        for key, limit in [('latitude', 90), ('longitude', 180)]:
            if not isinstance(origin[key], (int, float)) or not math.isfinite(origin[key]) or not -limit <= origin[key] <= limit:
                raise ValueError('Invalid origin coordinates')
        candidates = [t for t in catalog if distance(origin, t) <= 10000]
        if not candidates:
            raise ValueError(f'No source theaters within 10km of {origin["name"]}')
        scenarios.append(dict(origin, theaters=candidates))
    return scenarios


def require_live_settings(env):
    missing = [key for key in ('BENCH_SOURCE_DB_URL', 'KAKAO_MAP_REST_API_KEY') if not env.get(key)]
    if missing:
        raise ValueError('Live benchmark requires ' + ', '.join(missing) + '. Set .env.benchmark; no mock fallback is used.')
    if not env['BENCH_SOURCE_DB_URL'].startswith('jdbc:mysql://'):
        raise ValueError('BENCH_SOURCE_DB_URL must be a MySQL JDBC URL')
