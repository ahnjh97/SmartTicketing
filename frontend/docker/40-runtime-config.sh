#!/bin/sh
set -eu

# Only the browser-visible JavaScript key is exposed, never server credentials.
key=${VITE_KAKAO_MAP_JS_KEY:-}
case "$key" in
    ''|*[!a-zA-Z0-9]*)
        echo 'A valid VITE_KAKAO_MAP_JS_KEY is required to start the frontend' >&2
        exit 1
        ;;
esac
printf 'window.__SMART_TICKETING_CONFIG__ = {"kakaoMapJsKey":"%s"};\n' "$key" \
    > "${RUNTIME_CONFIG_PATH:-/usr/share/nginx/html/runtime-config.js}"
