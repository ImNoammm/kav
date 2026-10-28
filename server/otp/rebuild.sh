#!/bin/sh
# Rebuilds Kav's trip planner from tonight's timetable and puts it in service. Run by cron at night; the old planner
# keeps answering until the new one is ready, and a failed build leaves it alone.
set -eu
cd "$(dirname "$0")"
OTP=opentripplanner/opentripplanner:2.10.0
mkdir -p data/build data/serve
docker run --rm --user 1000:1000 -v "$PWD:/otp" -w /otp python:3.12-slim python3 prepare.py data/build
# The app's own timetable for the seven days from today, from tonight's timetable with its real calendars and the weekend
# lines, for phones to take in place of the week they shipped with (export_web_bundle.py beside prepare.py). Its manifest
# is signed with .signing/bundle.key, whose public half the app pins; kav-live serves realtime/bundle. Not needed for a
# planner: if any of it fails, the phones keep the one they have and the build goes on.
today=$(TZ=Asia/Jerusalem date +%F)
if rm -rf realtime/bundle-new realtime/bundle-old && mkdir -p realtime/bundle-new &&
   docker run --rm --user 1000:1000 --memory=4g -v "$PWD:/otp" -w /otp -e KAV_FROM="$today" -e KAV_BBOX=national \
     -e KAV_REGION=il -e KAV_MANIFEST=1 -e KAV_GTFS=data/official/mot-gtfs.zip -e KAV_NAIM=data/official/naim-gtfs.zip \
     -e KAV_OUT=realtime/bundle-new python:3.12-slim python3 export_web_bundle.py > data/build/bundle.log 2>&1 &&
   rm -f realtime/bundle-new/il.kav &&
   openssl dgst -sha256 -sign .signing/bundle.key realtime/bundle-new/manifest.json | base64 -w0 > realtime/bundle-new/manifest.json.sig &&
   [ -s realtime/bundle-new/manifest.json.sig ] &&
   { [ ! -d realtime/bundle ] || mv realtime/bundle realtime/bundle-old; } && mv realtime/bundle-new realtime/bundle; then
  echo "app timetable from $today: $(cat realtime/bundle/manifest.json)"
else
  tail -5 data/build/bundle.log || true
  [ -d realtime/bundle ] || [ ! -d realtime/bundle-old ] || mv realtime/bundle-old realtime/bundle || true
  echo "app timetable not built: phones keep theirs"
fi
rm -rf realtime/bundle-new realtime/bundle-old || true
# Joins a few bus stops' sidewalks to the street the bus stops on, where OpenStreetMap leaves them apart (walks.py).
# Not needed for a planner: if it fails (pip can't reach PyPI), the map is left as it was and the build goes on.
docker run --rm --user 1000:1000 -e HOME=/tmp -v "$PWD:/otp" -w /otp python:3.12 \
  sh -c 'pip install -q --user --no-warn-script-location osmium==4.3.2 && python3 walks.py data/build/israel.osm.pbf' ||
  echo "walks not mended: the map stays as it was"
# The buses moved to when they really get to each stop, from the passings Kav's server has recorded.
docker run --rm --user 1000:1000 --memory=4g -v "$PWD:/otp" -v "$PWD/../history:/history:ro" -w /otp python:3.12-slim \
  python3 predict.py data/build /history
# The night the clocks change, the trips before it go where the ministry runs them.
docker run --rm --user 1000:1000 -v "$PWD:/otp" -w /otp python:3.12-slim python3 clocks.py data/build
cp build-config.json router-config.json data/build/
rm -f data/build/graph.obj
# Low priority and capped: the server's other services come first. The timetable with the predicted bus times needs more
# than 9 GB; 14 builds it in about 12 minutes.
docker run --rm --user 1000:1000 --memory=15g --memory-swap=15g --cpus=4 --cpu-shares=128 -e JAVA_OPTS=-Xmx14g \
  -v "$PWD/data/build:/var/opentripplanner" "$OTP" --build --save > data/build/build.log 2>&1 ||
  { tail -20 data/build/build.log; exit 1; }
cp router-config.json data/serve/
mv data/build/graph.obj data/serve/graph.obj
# The live updates name trips by their route's direction in this very timetable, and find each trip's stops in it.
mkdir -p realtime && cp data/build/directions.json data/build/trip-patterns.json realtime/
# The weekend lines' timetable too, for kav-live's catalog: the city's site turns a server abroad away.
if [ -f data/official/naim-gtfs.zip ]; then cp data/official/naim-gtfs.zip realtime/; fi
docker restart kav-otp >/dev/null
echo "$(date -Is) planner rebuilt"
