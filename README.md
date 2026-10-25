# Album Screensaver for Android TV

A lightweight Android TV screensaver (DreamService) that plays photos from one or more **Google Photos
shared-album links**. No sign-in and no Google Cloud project: point it at album links and new photos show up
by themselves. Built for an old Xiaomi Mi Box 3 (Android TV 9, 2 GB RAM, 32-bit), so memory use is kept low.

## Features

- Photos from all albums shuffled together; mix by photo or equally per album; show/hide each album.
- Full screen: landscape photos crop-fill the screen, portrait photos sit whole on a blurred backdrop.
- Caption with album name, date taken and place (from the photo's GPS, reverse-geocoded on the device).
  Photos without GPS borrow the place of the closest photo taken within 2 hours.
- Clock, slow zoom-and-drift motion, adjustable slide time and transition speed.
- Smart selection: hides near-duplicate bursts and favours sharp photos, photos with faces and
  "on this day" memories. Analysis runs on the device.
- Adding albums: from a phone (the settings screen serves a small paste page on the LAN at port 8765,
  only while it's open), by typing the link, or over adb:
  `adb shell am start -n com.dk.albumdream/.SettingsActivity --es url <album link>`
- Videos are skipped.

## How it works

- **Album reading (unofficial).** The album page embeds its first 300 items in an `AF_initDataCallback`
  block (`ds:1`). The rest come from the web client's `snAcKc` batchexecute RPC, using the continuation
  token at `data[2]`. Videos have key `76647426` in the item's trailing object; `item[2]` is the taken-at
  time (UTC ms) and `item[4]` the time-zone offset. The official Library API can't read user albums since
  March 2025, so this may break whenever Google changes the page.
- **Downloads.** Each photo is fetched at the size a 1080p screen needs (`=w1920-l85` landscape,
  `=h1080-l85` portrait) and cached on the device, so playback works offline. Location comes from EXIF
  in a 64 KB range request of the original (`=d`). Cache cap: 4000 photos / 1.2 GB.
- **Sync.** A JobScheduler job runs hourly, does its work and exits; there is no persistent service.
  A run that hits its time budget schedules a continuation 15 s later.
- **Memory.** At most two full-size bitmaps, decoded straight to screen size. Measured at about 20-35 MB
  while running on the Mi Box 3.

## Build and install

Needs JDK 17 and the Android SDK (platform 34, build-tools 34).

```sh
cd albumdream
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell settings put secure screensaver_components com.dk.albumdream/.AlbumDream
```

The release APK is signed with the debug key; it's meant for sideloading.

## Other files

- `changes.log`: settings and apps changed on the test box while tuning it, with original values.
- `mibox_baseline.sh`: read-only performance snapshot of an Android TV box over adb
  (`MIBOX_ADB=<ip>:5555 sh mibox_baseline.sh <label>`).
