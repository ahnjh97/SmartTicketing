"""Loopback-only benchmark gateway. No retries, caching, or unmetered live fallback."""
import csv
from contextlib import contextmanager
import hashlib
import http.client
import json
import sqlite3
import threading
import time
from datetime import datetime, timezone, timedelta
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

ENDPOINTS = {"/v2/local/search/keyword.json": "search", "/v2/routing/publictraffic": "transit", "/v2/routing/walk": "walk"}


class BudgetExceeded(RuntimeError):
    pass


class Ledger:
    def __init__(self, path, key, limits):
        path.parent.mkdir(parents=True, exist_ok=True)
        self.path, self.limits = path, limits
        self.app = hashlib.sha256(key.encode()).hexdigest()
        with self.connect() as db:
            db.execute("CREATE TABLE IF NOT EXISTS usage (app TEXT, day TEXT, endpoint TEXT, used INTEGER NOT NULL, PRIMARY KEY(app,day,endpoint))")

    @contextmanager
    def connect(self):
        db = sqlite3.connect(self.path, timeout=10)
        try:
            with db:
                yield db
        finally:
            db.close()

    def day(self):
        return datetime.now(timezone(timedelta(hours=9))).date().isoformat()

    def remaining(self):
        with self.connect() as db:
            used = dict(db.execute("SELECT endpoint, used FROM usage WHERE app=? AND day=?", (self.app, self.day())))
        return {k: max(0, v - used.get(k, 0)) for k, v in self.limits.items()}

    def reserve(self, endpoint):
        # Persist BEFORE transmission. Crashes/network errors do not refund an uncertain call.
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            args = (self.app, self.day(), endpoint)
            row = db.execute("SELECT used FROM usage WHERE app=? AND day=? AND endpoint=?", args).fetchone()
            used = row[0] if row else 0
            if used >= self.limits[endpoint]:
                raise BudgetExceeded("daily_benchmark_budget_exhausted:" + endpoint)
            db.execute("INSERT INTO usage VALUES(?,?,?,1) ON CONFLICT(app,day,endpoint) DO UPDATE SET used=used+1", args)


class LiveGateway(ThreadingHTTPServer):
    daemon_threads = False

    def __init__(self, key, ledger, events, timeout=15):
        super().__init__(("127.0.0.1", 0), LiveHandler)
        self.key, self.ledger, self.timeout = key, ledger, timeout
        self.lock = threading.Lock()
        self.counts = {p: 0 for p in ENDPOINTS}
        self.counts["errors"] = 0
        self.halted, self.sample = "", ""
        self.run_used = {k: 0 for k in ledger.limits}
        self.stream = events.open("w", encoding="utf-8", newline="")
        self.csv = csv.writer(self.stream)
        self.csv.writerow(["sample", "endpoint", "status", "upstream_ms", "attempted", "reason"])

    def begin_sample(self, label):
        with self.lock:
            self.sample = label

    def snapshot(self):
        with self.lock:
            return dict(self.counts)

    def admit(self, path):
        with self.lock:
            if self.halted:
                raise BudgetExceeded(self.halted)
            kind = ENDPOINTS[path]
            if self.run_used[kind] >= self.ledger.limits[kind]:
                self.halted = "run_budget_exhausted:" + kind
                raise BudgetExceeded(self.halted)
            try:
                self.ledger.reserve(kind)
            except Exception:
                self.halted = "ledger_reservation_failed:" + kind
                raise
            self.run_used[kind] += 1
            self.counts[path] += 1

    def record(self, path, status, elapsed, attempted, reason):
        with self.lock:
            if status >= 400:
                self.counts["errors"] += 1
                self.halted = self.halted or reason or "upstream_http_" + str(status)
            self.csv.writerow([self.sample, path, status, round(elapsed, 4), attempted, reason])
            self.stream.flush()

    def server_close(self):
        super().server_close()
        self.stream.close()


class LiveHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *_):
        pass

    def do_GET(self):
        path = urlsplit(self.path).path
        status, payload, reason, attempted = 502, b"{}", "", False
        start = time.perf_counter_ns()
        connection = None
        try:
            if path not in ENDPOINTS or not self.path.startswith("/") or urlsplit(self.path).netloc:
                raise ValueError("unsupported_endpoint")
            self.server.admit(path)
            attempted = True
            connection = http.client.HTTPSConnection("dapi.kakao.com", timeout=self.server.timeout)
            connection.request("GET", self.path, headers={"Authorization": "KakaoAK " + self.server.key})
            response = connection.getresponse()
            status, payload = response.status, response.read()
            if status >= 400: reason = "upstream_http_" + str(status)
        except Exception as exc:
            # Never log exception bodies or credentials from upstream responses.
            reason = type(exc).__name__
            status = 429 if isinstance(exc, BudgetExceeded) else 502
            payload = json.dumps({"error": reason}).encode()
        finally:
            if connection: connection.close()
        self.server.record(path, status, (time.perf_counter_ns() - start) / 1e6, attempted, reason)
        try:
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
        except (ConnectionResetError, BrokenPipeError):
            pass
