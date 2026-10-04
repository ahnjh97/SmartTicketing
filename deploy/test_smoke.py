import contextlib
import importlib.util
import io
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from threading import Thread
import unittest
from urllib.error import HTTPError

spec = importlib.util.spec_from_file_location("smoke", Path(__file__).with_name("smoke-test.py"))
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)


class SmokeTests(unittest.TestCase):
    def setUp(self):
        self.routes = {
            "/": (200, "text/html", b'<div id="root"></div>'),
            "/api/health/readiness": (200, "application/json", b'{"status":"UP"}'),
            "/api/main": (200, "application/json", b'{"nowShowing":[],"comingSoon":[]}'),
            "/api/movies?page=0&size=1": (200, "application/json", b'{"items":[]}'),
            "/api/theaters": (200, "application/json", b'{"items":[]}'),
        }
        routes = self.routes

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                status, content_type, body = routes[self.path]
                self.send_response(status)
                self.send_header("Content-Type", content_type)
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *args):
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base = f"http://127.0.0.1:{self.server.server_port}"

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()

    def check(self):
        with contextlib.redirect_stdout(io.StringIO()):
            smoke.check(self.base)

    def test_healthy_site_with_empty_catalog_passes(self):
        self.check()

    def test_backend_502_fails_even_when_home_is_200(self):
        self.routes['/api/main'] = (502, 'text/html', b'Bad Gateway')
        with self.assertRaises(HTTPError):
            self.check()

    def test_initialization_in_progress_fails(self):
        self.routes['/api/health/readiness'] = (503, 'application/json', b'{"status":"OUT_OF_SERVICE"}')
        with self.assertRaises(HTTPError):
            self.check()

    def test_spa_fallback_instead_of_api_fails(self):
        self.routes['/api/theaters'] = self.routes['/']
        with self.assertRaisesRegex(ValueError, 'expected JSON'):
            self.check()

    def test_invalid_json_fails(self):
        self.routes['/api/main'] = (200, 'application/json', b'not json')
        with self.assertRaises(ValueError):
            self.check()

    def test_unrelated_home_page_fails(self):
        self.routes['/'] = (200, 'text/html', b'Welcome to nginx!')
        with self.assertRaisesRegex(ValueError, 'React application'):
            self.check()

    def test_wrong_chart_shape_fails(self):
        self.routes['/api/main'] = (200, 'application/json', b'{}')
        with self.assertRaisesRegex(ValueError, 'chart response'):
            self.check()


if __name__ == '__main__':
    unittest.main()
