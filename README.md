# pixelcamera-patches

A standalone Morphe patch source for Pixel Camera (GCam): forces photo/video
geotagging to read location from Android's own `LocationManager` (GPS +
network providers) instead of the Google Play Services Fused Location
Provider — even when Play Services is installed and the "Use Google's
location service" setting is enabled — and makes the native provider fall
back to a last-known/approximate fix when no fresh live fix has arrived
yet.

This repo is self-contained: it's its own Gradle project with its own
`settings.gradle.kts`/plugin setup, not a module living inside anyone else's
patch repo and not depending on any other patch. You can add it to Morphe
directly from this repo's GitHub URL once pushed.

Pinned to `com.google.android.GoogleCamera` **10.4.117.936816638.14**
(versionCode `69481630`). Obfuscated class/method names will differ in
other versions and the patch will need to be re-located.

**Status: beta, verified on-device.** All three bytecode changes below were
decompiled, patched, rebuilt, signed, and installed over a real running
GCam install (Pixel 8, GrapheneOS) — both directly via `apktool`/`adb` and
through an actual Morphe-built `.mpp`. `dumpsys location` confirmed the app
registers directly with the system's `gps`/`network` providers and never
reaches the GMS path. Two real bugs were found and fixed this way during
development (a register collision in the Kotlin translation that silently
corrupted the method instead of erroring, and a remote feature-flag gate
that could short-circuit location before the other patches even ran) — see
git history for details if you're curious.

## How it works

GCam already ships two interchangeable location-provider implementations
behind one interface (`Lkdu;`):

- `Lkdr;` — wraps the Google Play Services Fused Location Provider
  (`com.google.android.gms.location.*`).
- `Lkdt;` — wraps plain `android.location.LocationManager`, listening to the
  `gps` and `network` providers directly.

### Patch 1 — force the native provider

The selector, `Lkdx;->a()Luyx;`, picks between them at runtime:

1. Checks `ACCESS_COARSE_LOCATION` / `ACCESS_FINE_LOCATION` — no permission,
   no location at all (patch doesn't touch this).
2. Checks an internal/remote feature flag — off, no location at all. See
   **Patch 1b** below: this one *is* patched, because it can gate location
   off before step 3 ever runs.
3. Calls `GoogleApiAvailability.isGooglePlayServicesAvailable()`
   (`Lppm;->h`). If Play Services is **unavailable**, it falls straight
   through to `Lkdt;` (native).
4. If Play Services **is** available, it queries the
   `use_location_for_services` secure setting ("Use Google's location
   service"). If that's on, it instantiates `Lkdr;` (GMS). Otherwise it
   falls through to `Lkdt;` (native) anyway.

The patch changes exactly one instruction in step 3: the conditional branch
right after the availability check (`if-nez v0, :cond_6`) becomes an
unconditional jump (`goto :cond_6`) to the same target — the code path that
builds `Lkdt;`. The GMS branch (step 4, and the whole `Lkdr;` /
`LocationRequest` machinery it drives) becomes unreachable.

### Patch 1b — remove the remote feature-flag gate

Step 2 above reads a boolean from a remote/experiment config
(`Lnbq;->b(Lnbj;)` with key `Lnbl;->a`) and, if it's false, bails out to the
exact same "no location at all" path as a denied permission — before step 3
(and therefore Patch 1) ever runs. On a re-signed, non-Google-signed build
this config may never sync, or may default differently, and silently
short-circuit everything else in this repo. The fix is just removing that
one `if-eqz` instruction outright (not replacing it with anything), so
execution always falls through as if the flag were true.

This one was found the hard way: Patch 1 and Patch 2 were both confirmed
correct in the installed APK's bytecode, the device had a real cached
location, permissions were granted — and it still didn't work, because this
earlier gate was cutting location off before any of that ran.

### Patch 2 — fall back to an approximate last-known fix

`Lkdt;->a()` only ever returns a location once its `LocationListener`
(`Lkds;`) has actually received a live `onLocationChanged` callback for
`gps` or `network`. If neither has fired yet (e.g. GPS hasn't gotten a cold
fix, or indoors), it returns nothing — there's no fallback the way GMS's
fused provider implicitly had via its system-wide location cache.

The patch appends a fallback at the end of that method: before giving up,
it now also tries `LocationManager.getLastKnownLocation()` for `gps` then
`network`, and uses whichever cached fix is available. This can only
surface a location the system already has cached somewhere — it doesn't
conjure a fix out of nothing — but it means once *any* fix has landed (from
this app or another), later captures don't have to wait for a brand-new
live update.

## What's in here

```
pixelcamera-patches/
├── settings.gradle.kts, build.gradle.kts, gradle.properties  — standalone Gradle project
├── gradle/libs.versions.toml                                 — morphe-patcher/smali/gson coords
├── gradlew, gradlew.bat, gradle/wrapper/...                  — Gradle wrapper (self-bootstrapping)
├── patches/
│   ├── build.gradle.kts                                      — applies the `patches { about {...} }` block
│   └── src/main/kotlin/app/morphe/patches/pixelcamera/location/
│       └── NativeLocationPatch.kt                            — the Morphe bytecode patch
├── smali_patches/                                             — proven ground-truth .patch diffs
│   ├── android-native-location-provider.patch                 — Patch 1
│   ├── skip-remote-feature-flag.patch                         — Patch 1b
│   └── approximate-location-fallback.patch                    — Patch 2
└── README.md
```

- **`smali_patches/*.patch`** are the verified ground truth — the exact
  edits that were applied, rebuilt, signed, and confirmed running on a real
  device.
- **`patches/src/.../NativeLocationPatch.kt`** expresses both changes as
  one Morphe bytecode patch (Kotlin DSL), using the real API surface
  (`bytecodePatch { … }`, `mutableClassDefByOrNull`, `toInstructions`,
  `impl.replaceInstruction`, `impl.addInstruction`). Patch 1's translation
  mirrors the proven edit exactly (single instruction swap, same jump
  target). Patch 2's translation inserts the same multi-instruction block
  with its own internal labels — a pattern directly confirmed against real,
  shipped Morphe patches for this same app (an "early return" style
  insertion with a self-contained label, used identically elsewhere) — but
  drops the try/catch wrapper the proven raw-smali version has, since
  nothing in the reference patches shows how this API's simple
  instruction-list insertion expresses exception-handler ranges. In
  practice this should be safe regardless: by the time `Lkdt;->a()` runs,
  location permission is already confirmed granted, and `"gps"`/`"network"`
  are always-valid provider names, so neither exception should occur here.
  **Not independently build-verified** against Morphe Manager's actual
  patcher library (that needs a GitHub PAT with `read:packages` for
  `MorpheApp/registry`, which only you can supply) — if it fails to
  compile/apply, the `.patch` files are the confirmed-working fallback.

## Building

```bash
export GITHUB_ACTOR="your-github-username"
export GITHUB_TOKEN="your-github-pat-with-read:packages"
./gradlew buildAndroid
```

Output: `patches/build/libs/patches-1.0.0.mpp` (or similar — check the
task output). If the bare `buildAndroid` task name isn't found at the
root, try `./gradlew :patches:buildAndroid`.

Before publishing, edit `patches/build.gradle.kts`'s `about { }` block
(`source`, `author`, `website`) to point at your actual GitHub repo instead
of the `YOUR_USERNAME`/`YOUR_NAME` placeholders.

### Building via GitHub Actions (no local setup needed)

`.github/workflows/build-morphe-patch.yml` builds the `.mpp` in CI instead —
modeled on the real workflow `Akshayykadam/Pixel-Camera` uses. It runs on
every push that touches `patches/**`, on manual dispatch, and on publishing
a GitHub Release (in which case it attaches the built `.mpp` to that
release automatically). It uses the repo's automatic `secrets.GITHUB_TOKEN`
for the `MorpheApp/registry` GitHub Packages dependency — no personal PAT
needed, since that package is public and GitHub only requires *some* valid
token be presented, not package-specific permission.

To cut a distributable version:
1. Push a tag / publish a GitHub Release (e.g. version `1.0.0`).
2. The workflow builds `patches-1.0.0.mpp` and attaches it to that release.
3. `patches-bundle.json` at the repo root already points
   `download_url` at `.../releases/download/1.0.0/patches-1.0.0.mpp` —
   update the version there (and in `patches/build.gradle.kts` /
   `build.gradle.kts` / `patches-list.json`) if you cut a different
   version.

`patches-list.json` and `patches-bundle.json` are the discovery metadata
Morphe's "Add source" flow reads from the repo (same files
`Akshayykadam/Pixel-Camera` ships) — `patches-list.json` catalogs what the
bundle contains, `patches-bundle.json` says where to download the current
`.mpp` from.

## Using it

### Option A — apply directly with apktool (already proven; what was tested)

```bash
apktool d -f -o decoded base.apk
patch -p1 -d decoded < smali_patches/android-native-location-provider.patch
patch -p1 -d decoded < smali_patches/skip-remote-feature-flag.patch
patch -p1 -d decoded < smali_patches/approximate-location-fallback.patch
apktool -JXmx4G b -f -o patched_base.apk decoded
apksigner sign --ks your.keystore --out patched_base.signed.apk patched_base.apk
```

If replacing an already-installed GCam with a different signing cert,
you'll need to `adb uninstall com.google.android.GoogleCamera` first (loses
its app data), then `adb install`. Re-signing with the *same* key on later
rebuilds lets you use `adb install -r` instead and keep data/permissions.

### Option B — add it as a Morphe source

1. Push this repo to your own GitHub (`git remote add origin <url> && git
   push -u origin main`), after filling in the `about { }` placeholders
   above.
2. Build the `.mpp` per the "Building" section, or let Morphe build it from
   source directly.
3. In Morphe, use "Add to Morphe" with this repo's GitHub URL as the
   source.

If the Kotlin half doesn't build cleanly in Morphe Manager, fall back to
Option A's `.patch` files, which are already confirmed to work against
this exact APK version.

## Known limitations

- The native fallback still depends on the system actually being able to
  get a fix: enable Network Location in Settings → Location if you're on
  GrapheneOS or a similar ROM, and give GPS a clear sky view the first
  time.
- Both patches are pinned to version `10.4.117.936816638.14`. A GCam update
  will very likely renumber these obfuscated classes/methods and the
  patches will need to be re-located against the new build.
