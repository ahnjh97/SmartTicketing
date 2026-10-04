"""Fail deployment unless the public page and catalog APIs actually work."""
import json
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


def check(base):
    for path in ("/", "/api/health/readiness", "/api/main", "/api/movies?page=0&size=1", "/api/theaters"):
        request = Request(base.rstrip("/") + path, headers={"User-Agent": "SmartTicketing-deploy-check"})
        with urlopen(request, timeout=10) as response:
            if response.status != 200:
                raise ValueError(f"{path}: HTTP {response.status}")
            content_type = response.headers.get_content_type()
            body = response.read()
            if path == "/":
                if content_type != "text/html" or b'id="root"' not in body:
                    raise ValueError("/: expected the React application HTML")
            else:
                if content_type != "application/json":
                    raise ValueError(f"{path}: expected JSON, received {content_type}")
                data = json.loads(body)
                if path == "/api/health/readiness" and data != {"status": "UP"}:
                    raise ValueError(f"{path}: application is not ready")
                if path == "/api/main" and not (
                    isinstance(data, dict)
                    and isinstance(data.get("nowShowing"), list)
                    and isinstance(data.get("comingSoon"), list)
                ):
                    raise ValueError(f"{path}: unexpected chart response")
                if data is None:
                    raise ValueError(f"{path}: empty JSON response")
            print(f"OK {path}", flush=True)


if __name__ == "__main__":
    for attempt in range(1, 7):
        try:
            check(sys.argv[1])
            break
        except (HTTPError, URLError, TimeoutError, OSError, ValueError) as error:
            print(f"Attempt {attempt}/6: {error}", file=sys.stderr, flush=True)
            if attempt == 6:
                sys.exit(1)
            time.sleep(5)
