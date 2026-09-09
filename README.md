# Kav

An Android app for getting around on public transport. No ads, no account, no
analytics, nothing phoning home about where you go.

I built it because Moovit is the only app that really covers Israeli transit and
it has become unusable: full-screen ads, a subscription nag, and a permissions
list that has nothing to do with catching a bus.

Kav is the same job done plainly. It plans a trip, tells you which bus, and walks
you through it while you're on the way.

## Getting it

Grab the APK from [Releases](https://github.com/ImNoammm/kav/releases) and open it.
Android will ask you to allow installing apps from wherever you downloaded it.

It updates itself: it checks this repo's releases page on launch and offers to
install a new one if there is one. No store, no update service.

## What it does

Search for where you're going, get a few ways to get there, pick one, and start.
From then on it follows you: the current step advances by itself as you walk and
ride, the map turns with you, and you can see where your bus actually is on the
road if it's reporting its position.

If you leave the app mid-trip, the current step follows you around in a small
floating window.

There's also a stops screen for "what's leaving from here", and a lines screen for
browsing routes.

First launch asks two things: what colour you want the app to be, and which kinds
of results you care about (buses, trains, taxis, bike, walking). Both are in
Settings afterwards.

## Offline

The whole national timetable is compiled into the app, about 25 MB of it. So
planning a journey works with no signal at all: in a tunnel, abroad with no data,
on a dead SIM. That was the main thing I wanted.

The parts that genuinely can't work offline are live vehicle positions, map tiles
and the update check. Everything else is on the phone.

## Privacy

There is no account, so there is nothing tied to you. Nothing is logged anywhere,
because there is no server of mine for it to be logged on. The app talks to the
transit API and a map tile server and that's it.

Your recent trips and your searches live on the phone in the app's own storage.
Uninstalling takes them with it.

## Building it

You need a JDK and the Android SDK. There's a script that fetches both into
`~/Android` without touching your system packages:

```sh
tools/android_toolchain.sh
tools/android_build.sh :app:assembleRelease
```

The timetable isn't in the repo. It's built from the Ministry of Transport's GTFS
feed, which is published with no licence attached, so redistributing it here didn't
seem like a good idea. Build it yourself:

```sh
tools/fetch.sh
KAV_REGION=il KAV_OUT=android/app/src/main/assets python3 tools/export_web_bundle.py
```

The feed is about 180 MB and compiling it takes a minute or so. It writes
`il.kav` straight into the app's assets, and the build picks it up from there.
Leave `KAV_REGION` off and you get a Tel Aviv-only bundle instead, which is a
third of the size and quicker to iterate on.

There's also a browser version in `webui/` which is where the routing engine was
prototyped. It still works and it's useful for trying routing changes quickly,
but the Android app is the real thing.

## Poking around the code

- `android/`: the app. Compose, one activity, `data/` for everything that talks to
  the outside world and `ui/` for screens.
- `tools/`: the data pipeline and the build scripts.
