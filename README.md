# Kav

An Android app for getting around on public transport in Israel. No ads, no
account, no analytics, nothing phoning home about where you go.

I built it because Moovit is the only app that really covers Israeli transit and
it has become unusable: full-screen ads, a subscription nag, and a permissions
list that has nothing to do with catching a bus. Kav is the same job done
plainly: it plans a trip, tells you which bus, and walks you through it while
you're on the way.

## Getting it

Grab the APK from [Releases](https://github.com/ImNoammm/kav/releases) and open
it. It updates itself from the same page. No store, no update service.

First launch fetches the map, about 176 MB once. It lives on the phone from then
on, so the map works offline and no tile server sees where you look.

## What it does

Search for where you're going, get a few ways there, pick one, and start. From
then on it follows you: the step advances by itself as you walk and ride, the
map turns with you, and you can see where your bus actually is on the road. If
you leave the app mid-trip, the current step follows you in a small floating
window.

There's a stops screen for "what's leaving from here", a lines screen for
browsing routes, and a live screen with every bus reporting its position around
you. Favourite places sit on the home screen. Home is built in, the rest you
name yourself.

## Offline

The whole national timetable is compiled into the app, about 25 MB. Planning a
journey works with no signal at all: in a tunnel, abroad, on a dead SIM. Live
vehicle positions and the update check are the only things that touch the
network; there is no server of mine, and your places and trips stay in the
app's own storage on the phone.

## Building it

You need a JDK and the Android SDK. There's a script that fetches both into
`~/Android` without touching your system packages:

```sh
tools/android_toolchain.sh
tools/android_build.sh :app:assembleRelease
```

The timetable isn't in the repo. The Ministry of Transport publishes its GTFS
feed with no licence attached, so build it yourself:

```sh
tools/fetch.sh
KAV_REGION=il KAV_OUT=android/app/src/main/assets python3 tools/export_web_bundle.py
```
