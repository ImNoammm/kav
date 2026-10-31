<p align="center">
  <img src=".github/icon.png" width="112" alt="Kav">
</p>

<h1 align="center">Kav</h1>

<p align="center">
  Public transport in Israel, without the ads, the account or the tracking.
</p>

<h2 align="center">
  Moovit sent me a cease and desist, so I took the old versions down.<br>
  Kav isn't stopping, it's going harder: the next version, with nothing from
  Moovit in it, is coming in the next few days.
</h2>

<p align="center">
  <a href="https://github.com/ImNoammm/kav/releases/latest"><img alt="Latest release" src="https://img.shields.io/github/v/release/ImNoammm/kav?label=release&color=9ABEFF"></a>
  <img alt="Android 8.0 or newer" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white">
  <a href="LICENSE"><img alt="GPL-3.0" src="https://img.shields.io/badge/license-GPL--3.0-lightgrey"></a>
</p>

<p align="center">
  <a href="https://www.buymeacoffee.com/Noamm"><img alt="Buy Me A Coffee" src="https://www.buymeacoffee.com/assets/img/custom_images/orange_img.png"></a>
</p>

An Android app for getting around on public transport in Israel. No ads, no
account, no analytics, nothing phoning home about where you go. Hebrew and
English, and it lays itself out right to left when you pick Hebrew.

I built it because the app everyone uses for Israeli transit has become
unusable: full-screen ads, a subscription nag, and a permissions list that has
nothing to do with catching a bus. Kav is the same job done
plainly: it plans a trip, tells you which bus, and walks you through it while
you're on the way.

## What it does

- Plans a trip, gives you a few ways there, and walks you through the one you
  pick. The step moves on by itself as you walk and ride, the map turns with
  you, and if you leave the app the current step follows you in a small
  floating window.
- Shows where your bus actually is. The trip stays in the notification shade
  while you're on the way, and on Android 16 it shows up as a live update.
- Live bus locations on the map, free. They come from Kav's own server
  ([server/](server)), which takes them straight from the Ministry of
  Transport's real-time feed, and it plans your trips too, on the ministry's
  timetable with OpenTripPlanner. It only learns the lines, stops or rough area
  you're looking at and the start and end of a trip you plan, through
  Cloudflare, and keeps no logs.
- A live screen with every stop around you and the buses reporting their
  position.
- Departure boards for every station, and a page for every line with its route
  on a map, its stops and its buses on the road.
- OLED black, light and dark, with liquid glass or solid bars.
- Favourite places on the home screen, and backups of your places and settings
  to a `.kav` file.
- Private search, on by default, keeps your exact location out of searches:
  Google only sees the centre of the town you're in, or a place you pick.
- Pays for bus, train, light rail and Carmelit rides, if you want it to, with a
  Rav-Pass account you sign up or sign in to right in Kav. Only payments use
  it, and Rav-Pass gets your location only at the moment you scan or pay, as in
  its own app, never in the background. Everything else stays anonymous.
- Reports a driver, a bus or an inspector to the Ministry of Transport on its
  own form. Kav fills it in from your ride, and you check it and send it
  yourself. Your details stay on the phone and only go into that form.

## Getting it

Once the next version is out, grab the APK from
[Releases](https://github.com/ImNoammm/kav/releases) and open it. It updates
itself from the same page. No store, no update service.

GitHub builds every APK there from the code in this repo, and you can check the
one you downloaded with the [GitHub CLI](https://cli.github.com):

```sh
gh attestation verify kav-3.2.apk --repo ImNoammm/kav
```

First launch fetches the map, about 176 MB once. It lives on the phone from then
on, so the map works offline and no tile server sees where you look. The
satellite map you can pick in Settings loads online.

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
KAV_REGION=il KAV_BBOX=national python3 tools/export_web_bundle.py
```

A build of your own needs a server of your own for live buses and trips
(below): Kav's server only answers the APKs from Releases.

## Running your own server

Coming soon: everything Kav's server does, on a machine of your own.

## Credits

- Map data © OpenStreetMap contributors, from the Protomaps build, drawn with
  MapLibre. Satellite photos from Esri.
- The liquid glass shaders come from Kyant0's
  [AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) (Apache
  License 2.0).
- Timetables and live buses from the Israel Ministry of Transport. Trips are
  planned on that timetable and OpenStreetMap by
  [OpenTripPlanner](https://github.com/opentripplanner/OpenTripPlanner).
- Places and exact addresses from Google Maps' public web search, asked the way
  a logged-out browser asks it: no key, no account, no cookies. It gets the typed
  text and the area above. The request recipe is
  [Vela Maps](https://github.com/PimpinPumpkin/Vela)' (GPL-3.0). Once a day Kav
  reads Vela's signed recipe from GitHub, so a fix for Google reaches it without
  an update. A file without the right signature, or pointing anywhere but
  Google, is ignored.
- Addresses Google doesn't have from GovMap, the Survey of Israel's map, sent
  just the typed text.
- Street View from Google, shown inside Kav only when you open it on a stop.
  Google then sees that spot and your IP address, and its viewer keeps no
  cookies.
- Train times between two stations from Israel Railways' own timetable, asked
  only when you open that card. It learns the two stations and the day.
- The operator colours come from [@szaionz](https://github.com/szaionz)'s
  route_colors branch.
- Thanks to the amazing team at [HTTP Toolkit](https://httptoolkit.com), who
  kindly gave Kav a free Pro licence.
- A huge thanks to [@yair-654](https://github.com/yair-654) for all the support
  for Kav, and for the bug reports and ideas behind a lot of it.

## License

GPL-3.0. See [LICENSE](LICENSE).
