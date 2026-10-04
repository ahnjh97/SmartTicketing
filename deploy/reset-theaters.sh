#!/usr/bin/env bash
set -euo pipefail
case ${1:-} in
  RESET_THEATERS_KEEP_USERS)
    reset_label='theater and booking data; preserving accounts and movies'
    backup_prefix=theater ;;
  RESET_THEATERS_AND_USERS)
    reset_label='theater, booking and ALL account data; preserving movies'
    backup_prefix=theater-and-users ;;
  *) echo 'Invalid reset confirmation' >&2; exit 1 ;;
esac
: "${cleanup_sql_base64:?Missing reset SQL}"
umask 077
exec 9> "$HOME/.smartticketing-deploy.lock"
flock -w 900 9

db_container=smartticketing-mysql
app_container=smartticketing-backend
[[ $(docker inspect --format '{{.State.Running}}' "$db_container") == true ]]
[[ $(docker inspect --format '{{.State.Running}}' "$app_container") == true ]]
work=$(mktemp -d)
restart_needed=false
finish() {
  result=$?
  trap - EXIT
  if [[ $restart_needed == true ]]; then
    docker start "$app_container" >/dev/null || result=1
  fi
  rm -f -- "$work/reset.sql"
  rmdir -- "$work"
  exit "$result"
}
trap finish EXIT
printf '%s' "$cleanup_sql_base64" | base64 --decode > "$work/reset.sql"

# Stop writers and startup/background jobs before taking a consistent backup.
restart_needed=true
docker stop --time 60 "$app_container" >/dev/null
[[ $(docker inspect --format '{{.State.Running}}' "$app_container") == false ]]
backup_dir="$HOME/.smartticketing-backups"
install -m 700 -d "$backup_dir"
backup=$(mktemp --suffix=.sql.gz "$backup_dir/before-$backup_prefix-reset-$(date -u +%Y%m%dT%H%M%SZ)-XXXXXX")
echo 'Backing up the database on EC2 (not uploaded to GitHub)...'
docker exec "$db_container" sh -c '
  export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"
  exec mysqldump -uroot --single-transaction --quick --no-tablespaces --set-gtid-purged=OFF "${MYSQL_DATABASE:?}"
' | gzip > "$backup"
gzip -t "$backup"
echo "Backup ready: $backup"

# Batch mode aborts at the first SQL error; disconnect rolls back the transaction.
# Never use --force, TRUNCATE, or FOREIGN_KEY_CHECKS=0 here.
echo "Deleting $reset_label..."
docker exec -i "$db_container" sh -c '
  export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"
  exec mysql -uroot --batch --skip-column-names "${MYSQL_DATABASE:?}"
' < "$work/reset.sql"
echo 'Database cleanup committed. Restarting the existing backend container...'
docker start "$app_container" >/dev/null
restart_needed=false
# Starting the same container preserves its image and secret environment.
# Its configured import/seed jobs rebuild the current three-day schedule.
for attempt in $(seq 1 450); do
  state=$(docker inspect --format '{{.State.Status}}' "$app_container")
  [[ "$state" == running ]] || { echo 'Backend stopped during restart' >&2; exit 1; }
  health=$(docker inspect --format '{{.State.Health.Status}}' "$app_container")
  if [[ "$health" == healthy ]]; then
    docker restart smartticketing-nginx >/dev/null
    echo 'Cleanup and backend readiness check completed.'
    exit 0
  fi
  sleep 2
done
echo 'Cleanup committed, but backend readiness timed out. Check initialization progress.' >&2
exit 1
