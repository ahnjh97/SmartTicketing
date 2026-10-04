#!/bin/sh
set -eu

# Enable only providers whose credentials exist. Do not log their values.
if [ -z "${SPRING_PROFILES_ACTIVE:-}" ]; then
    profiles=""
    enable_provider() {
        provider=$1
        client_id=$2
        client_secret=$3
        if [ -n "$client_id" ] || [ -n "$client_secret" ]; then
            if [ -z "$client_id" ] || [ -z "$client_secret" ]; then
                echo "OAuth provider $provider needs both client ID and client secret" >&2
                exit 1
            fi
            profiles="${profiles:+$profiles,}oauth-$provider"
        fi
    }
    enable_provider google "${GOOGLE_CLIENT_ID:-}" "${GOOGLE_CLIENT_SECRET:-}"
    enable_provider naver "${NAVER_CLIENT_ID:-}" "${NAVER_CLIENT_SECRET:-}"
    enable_provider kakao "${KAKAO_CLIENT_ID:-}" "${KAKAO_CLIENT_SECRET:-}"
    export SPRING_PROFILES_ACTIVE="$profiles"
fi

exec java -jar app.jar "$@"
