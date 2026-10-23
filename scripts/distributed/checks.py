"""HTTP checks for the two-EC2 lab; only uses Python's standard library."""
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from http.cookies import SimpleCookie
import json
import time
import urllib.error
import urllib.request


class Client:
    def __init__(self):
        self.cookie = None

    def call(self, base, path, method='GET'):
        headers = {'Cookie': self.cookie} if self.cookie else {}
        request = urllib.request.Request(base + path, headers=headers, method=method)
        try:
            response = urllib.request.urlopen(request, timeout=10)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            cookies = SimpleCookie()
            for value in response.headers.get_all('Set-Cookie', []):
                cookies.load(value)
            if 'st_admission' in cookies:
                self.cookie = 'st_admission=' + cookies['st_admission'].value
            return response.status, json.load(response), response.headers.get('X-Lab-Upstream', '')


def balancing(proxy, expected_b, count, clients):
    if clients < 1 or count < max(2, clients):
        raise ValueError('Choose clients >= 1 and requests >= max(2, clients)')
    observed = Counter()
    with ThreadPoolExecutor(max_workers=clients) as pool:
        replies = list(pool.map(lambda _: Client().call(proxy, '/api/health/readiness'), range(count)))
    for status, _, upstream in replies:
        if status != 200 or not upstream or ',' in upstream:
            raise AssertionError(f'Proxy request failed or retried: HTTP {status}, upstream={upstream}')
        observed[upstream] += 1
    if len(observed) != 2 or expected_b not in observed:
        raise AssertionError(f'Expected A and B ({expected_b}), observed {dict(observed)}')
    return {'requests': count, 'concurrentClients': clients,
            'upstreamCounts': dict(observed), 'bothBackendsObserved': True}


def admission(a, b, proxy, capacity, clients, timeout=60):
    if not 1 <= capacity < clients:
        raise ValueError('Use 1 <= capacity < clients; this checks correctness, not capacity limits')
    visitors = [Client() for _ in range(clients)]
    # This public API is still protected by the admission filter. Health is exempt.
    status, body, _ = Client().call(proxy, '/api/movies')
    if status != 429 or body.get('code') != 'ADMISSION_REQUIRED':
        raise AssertionError('Enable admission on BOTH backends before this check')
    peak = 0
    deadline = time.monotonic() + timeout

    def snapshot():
        nonlocal peak
        with ThreadPoolExecutor(max_workers=32) as pool:
            replies = list(pool.map(lambda item: item[1].call(
                a if item[0] % 2 else b, '/api/admission/status'), enumerate(visitors)))
        states = []
        for status, body, _ in replies:
            state = body.get('state')
            if status != 200 or state not in ('ADMITTED', 'WAITING'):
                raise AssertionError(f'Unexpected admission status: HTTP {status}, {body}')
            states.append(state)
        active = states.count('ADMITTED')
        peak = max(peak, active)
        if active > capacity:
            raise AssertionError(f'Global capacity exceeded: {active} > {capacity}')
        return states

    def wait_full():
        while True:
            states = snapshot()
            if states.count('ADMITTED') == capacity:
                return states
            if time.monotonic() >= deadline:
                raise AssertionError(f'Queue did not fill to {capacity}; use an otherwise idle lab')
            time.sleep(.5)

    try:
        with ThreadPoolExecutor(max_workers=32) as pool:
            replies = list(pool.map(lambda client: client.call(proxy, '/api/admission/enter', 'POST'), visitors))
        for client, (status, body, _) in zip(visitors, replies):
            if status != 200 or body.get('state') not in ('ADMITTED', 'WAITING') or not client.cookie:
                raise AssertionError(f'Entry failed: HTTP {status}, {body}')
        before = wait_full()
        admitted = [i for i, state in enumerate(before) if state == 'ADMITTED']
        waiting = [i for i, state in enumerate(before) if state == 'WAITING']
        # Reuse the same browser cookie on both servers, including protected API access.
        for index in (admitted[0], waiting[0]):
            expected = 200 if index in admitted else 429
            for base in (a, b):
                status, _, _ = visitors[index].call(base, '/api/movies')
                if status != expected:
                    raise AssertionError(f'Cross-server admission mismatch: expected {expected}, got {status}')
        released = min(5, len(admitted), len(waiting))
        leaving = admitted[:released]
        for index in leaving:
            status, _, _ = visitors[index].call(proxy, '/api/admission/leave', 'POST')
            if status != 200:
                raise AssertionError(f'Leave failed: HTTP {status}')
        # Departed browsers have no admission cookie; do not include them in status polls.
        remaining = [i for i in range(clients) if i not in leaving]
        visitors_before = visitors
        visitors = [visitors_before[i] for i in remaining]
        deadline = time.monotonic() + timeout
        after = wait_full()
        promoted = sum(after[remaining.index(index)] == 'ADMITTED' for index in waiting)
        if promoted != released:
            raise AssertionError(f'Expected {released} promotions, observed {promoted}')
        return {'clients': clients, 'capacity': capacity, 'initialAdmitted': len(admitted),
                'initialWaiting': len(waiting), 'released': released, 'promoted': promoted,
                'maxObservedAdmitted': peak, 'crossServerCookieAccepted': True,
                'waitingClientBlockedOnBothBackends': True}
    finally:
        # Each test owns only its browser sessions; never flush shared Redis state.
        errors = []
        for client in visitors:
            if client.cookie:
                try:
                    status, _, _ = client.call(proxy, '/api/admission/leave', 'POST')
                    if status != 200:
                        errors.append(str(status))
                except Exception as error:
                    errors.append(type(error).__name__)
        if errors:
            raise RuntimeError(f'Could not release {len(errors)} test sessions: {errors}')
