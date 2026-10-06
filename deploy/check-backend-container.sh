#!/usr/bin/env bash
set -euo pipefail

: "${BACKEND_IMAGE:?Set BACKEND_IMAGE}"
project="backend-check-${GITHUB_RUN_ID:-local}-${GITHUB_RUN_ATTEMPT:-1}"
network="$project-network"
mysql="$project-mysql"
redis="$project-redis"
backend="$project-app"
port=${BACKEND_CHECK_PORT:-8089}
cleanup() {
  result=$?
  if (( result != 0 )); then docker logs "$backend" || true; docker logs "$mysql" || true; fi
  docker rm -f -v "$backend" "$redis" "$mysql" || true
  docker network rm "$network" || true
  exit "$result"
}
trap cleanup EXIT

# Disposable CI containers only; no production network, credentials or volumes.
docker network create "$network"
docker run -d --name "$mysql" --network "$network" \
  -e MYSQL_ROOT_PASSWORD=ci-container-only-password -e MYSQL_ROOT_HOST=% \
  -e MYSQL_DATABASE=smart_ticketing mysql:8.4
docker run -d --name "$redis" --network "$network" redis:7-alpine
ready=false
for attempt in {1..60}; do
  if docker exec -e MYSQL_PWD=ci-container-only-password "$mysql" \
       mysql --protocol=TCP -h 127.0.0.1 -uroot -e 'SELECT 1' >/dev/null 2>&1; then ready=true; break; fi
  sleep 2
done
[[ "$ready" == true ]] || { echo 'CI MySQL readiness timed out' >&2; exit 1; }

docker run -d --name "$backend" --network "$network" -p "127.0.0.1:$port:8080" \
  -e "DB_URL=jdbc:mysql://$mysql:3306/smart_ticketing?serverTimezone=Asia/Seoul&characterEncoding=UTF-8" \
  -e DB_USERNAME=root -e DB_PASSWORD=ci-container-only-password \
  -e "SPRING_DATA_REDIS_HOST=$redis" -e REDIS_PORT=6379 \
  -e JWT_SECRET=ci-container-only-secret-at-least-32-bytes-long \
  -e ADMIN_INITIAL_PASSWORD=CiContainerOnlyPassword123 \
  -e TMDB_AUTO_IMPORT=false -e KAKAO_CATALOG_AUTO_IMPORT=false -e SHOWTIME_SEED_ENABLED=false \
  "$BACKEND_IMAGE"

ready=false
for attempt in {1..90}; do
  [[ "$(docker inspect --format '{{.State.Running}}' "$backend")" == true ]] || {
    echo 'Backend container exited before becoming ready' >&2; exit 1;
  }
  if curl --fail --silent --connect-timeout 1 --max-time 3 "http://127.0.0.1:$port/api/health/readiness" >/dev/null; then
    ready=true; break
  fi
  sleep 2
done
[[ "$ready" == true ]] || { echo 'Backend HTTP readiness timed out' >&2; exit 1; }
for path in '/api/main' '/api/movies?page=0&size=1' '/api/theaters'; do
  curl --fail --silent --show-error --max-time 10 "http://127.0.0.1:$port$path" >/dev/null
done
# A fresh deployment must create every new booking table before receiving traffic.
tables=$(docker exec -e MYSQL_PWD=ci-container-only-password "$mysql" mysql -uroot -N -B smart_ticketing \
  -e "select count(*) from information_schema.tables where table_schema='smart_ticketing' and table_name in ('waiting_queue_seats','waiting_zone_sequences','booking_user_limits')")
[[ "$tables" == 3 ]] || { echo 'Booking schema was not fully initialized' >&2; exit 1; }
echo 'Backend image, HTTP readiness and booking schema verified'
