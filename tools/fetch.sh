#!/bin/sh
# Fetch the Israeli MOT GTFS feed into .cache/ (~180 MiB, rebuilt nightly).
set -e
cd "$(dirname "$0")/.."
mkdir -p .cache
curl -sSL --retry 3 -o .cache/israel-gtfs.zip \
  https://gtfs.mot.gov.il/gtfsfiles/israel-public-transportation.zip
ls -l .cache/israel-gtfs.zip
