#!/bin/sh
# Builds the second planner, on the ministry's own timetable (no predict.py), for kav-otp-official. Its trips must keep
# the main planner's ids, so it takes the very timetable tonight's rebuild.sh prepares: started before rebuild.sh, it
# hard-links data/build/israel-gtfs.zip the moment prepare.py writes it, before predict.py replaces it. Then it waits
# for rebuild.sh to finish and builds, one build at a time. rebuild.sh and the main planner are never touched; a failed
# step leaves the old official graph serving.
#   official-build.sh           keep tonight's timetable, then build (cron, 00:29)
#   official-build.sh prepare   when the keep was missed: the same timetable again from tonight's feeds, then build
set -eu
cd "$(dirname "$0")"
case "${1:-}" in prepare | "") ;; *) echo "usage: official-build.sh [prepare]"; exit 2 ;; esac
OTP=opentripplanner/opentripplanner:2.10.0
B=data/build-official
exec 9>data/official-build.lock
flock -n 9 || { echo "$(date -Is) official build already running"; exit 0; }

# rebuild.sh running (a shell running it, not a command line naming it), or any OpenTripPlanner build.
rebuilding() {
  pgrep -f '^[^ ]*sh ([^ ]*/)?rebuild\.sh( |$)' >/dev/null ||
    docker ps --no-trunc --filter ancestor=$OTP --format '{{.Command}}' | grep -q -- --build
}

keep() {
  if rebuilding; then
    echo "rebuild.sh is already running: run 'official-build.sh prepare' once it is done"
    return 1
  fi
  old=$(stat -c %i data/build/israel-gtfs.zip 2>/dev/null || echo none)
  i=0
  while :; do
    new=$(stat -c %i data/build/israel-gtfs.zip 2>/dev/null || echo none)
    [ "$new" = none ] || [ "$new" = "$old" ] || break
    i=$((i + 1))
    [ $i -le 1080 ] || { echo "no new timetable from prepare.py in 90 minutes"; return 1; }
    sleep 5
  done
  rm -rf $B && mkdir -p $B
  ln data/build/israel-gtfs.zip $B/israel-gtfs.zip
  [ "$(stat -c %i $B/israel-gtfs.zip)" = "$new" ] || { echo "the timetable changed while kept"; rm -rf $B; return 1; }
  echo "$(date -Is) kept prepare.py's timetable"
}

# The same timetable from the feeds prepare.py kept tonight (data/official), in a folder of its own: only the next days'
# trains are asked of Israel Railways again.
prepare() {
  W=data/official-work
  rm -rf $W $B && mkdir -p $W/in $W/build $B
  cp prepare.py stations.tsv $W/
  cp -p data/official/mot-gtfs.zip data/official/naim-gtfs.zip $W/in/
  ln data/build/israel.osm.pbf $W/build/
  docker run --rm --user 1000:1000 --memory=4g --cpu-shares=128 -v "$PWD/$W:/otp" -w /otp \
    -e GTFS_URL=file:///otp/in/mot-gtfs.zip -e NAIM_URL=file:///otp/in/naim-gtfs.zip \
    python:3.12-slim python3 prepare.py build > $B/prepare.log 2>&1 || { tail -5 $B/prepare.log; return 1; }
  mv $W/build/israel-gtfs.zip $B/
  rm -rf $W
}

# $1: a file from tonight's prepare.py; the main planner must have been built after it, or the ids may differ.
build() {
  [ -s $B/israel-gtfs.zip ] || { echo "no timetable in $B"; return 1; }
  [ data/serve/graph.obj -nt "$1" ] || { echo "the main planner was not rebuilt from tonight's timetable"; return 1; }
  # Low priority and capped like rebuild.sh's build, and the first to go if memory runs out. The official planner steps
  # aside for it too; the EXIT trap starts it again.
  if [ "$(docker inspect -f '{{.State.Running}}' kav-otp-official 2>/dev/null)" = true ]; then
    docker stop kav-otp-official >/dev/null
  fi
  avail=$(awk '/MemAvailable/ {print int($2 / 1048576)}' /proc/meminfo)
  [ "$avail" -ge 12 ] || { echo "only $avail GB of memory free"; return 1; }
  # The streets as rebuild.sh left them, walks.py's mending in.
  ln -f data/build/israel.osm.pbf $B/
  docker run --rm --user 1000:1000 -v "$PWD:/otp" -w /otp python:3.12-slim python3 clocks.py $B
  cp build-config.json router-config.json $B/
  rm -f $B/graph.obj
  docker run --rm --name kav-otp-official-build --user 1000:1000 --memory=15g --memory-swap=15g --cpus=4 \
    --cpu-shares=128 --oom-score-adj=1000 -e JAVA_OPTS=-Xmx14g -v "$PWD/$B:/var/opentripplanner" "$OTP" --build --save \
    > $B/build.log 2>&1 || { tail -20 $B/build.log; return 1; }
  mkdir -p data/serve-official
  cp router-config.json data/serve-official/
  mv $B/graph.obj data/serve-official/graph.obj
  if docker inspect kav-otp-official >/dev/null 2>&1; then
    docker restart kav-otp-official >/dev/null || echo "kav-otp-official did not restart"
  fi
  echo "$(date -Is) official planner rebuilt"
}

wait_rebuild() {
  i=0
  while rebuilding; do
    i=$((i + 1))
    [ $i -le 180 ] || { echo "rebuild.sh still running after 3 hours"; return 1; }
    sleep 60
  done
}

# Plain calls, not an && chain: set -e works inside the functions only then.
ok=
trap '[ -n "$ok" ] || echo "$(date -Is) official planner not rebuilt: the old one stays"
[ "$(docker inspect -f "{{.State.Running}}" kav-otp-official 2>/dev/null)" != false ] ||
  docker start kav-otp-official >/dev/null || echo "kav-otp-official did not start"' EXIT
if [ "${1:-}" = prepare ]; then
  wait_rebuild
  prepare
  build data/official/mot-gtfs.zip
else
  keep
  wait_rebuild
  build $B/israel-gtfs.zip
fi
ok=1
