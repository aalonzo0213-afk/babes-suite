# Babe's Manager — Suite App

One Android app that wraps the Live Schedule, Safe & Sales Count, and
Table & Server Count HTML tools behind a single home screen. Each tool
stays a self-contained HTML file — the app is just a viewer, so a change
to one tool can't break the others.

## What's inside

- `app/src/main/assets/index.html` — the home screen with a card for each tool
- `app/src/main/assets/` — **drop your three HTML files here** (exact names in index.html)
- `MainActivity.kt` — the WebView shell:
  - localStorage enabled and stored in app data (safe from Chrome's file:// wipes)
  - Export buttons (JSON backup, xlsx) save straight to the tablet's **Downloads** folder
  - Import/restore file pickers work
  - Back button returns to the home screen
- `.github/workflows/build.yml` — cloud APK build, same as WearWorkout

## Steps (computer-free workflow)

1. Create a new GitHub repo (e.g. `babes-suite`) on your `aalonzo0213-afk` account
2. Upload this whole folder's contents to the repo (GitHub web UI: "Add file → Upload files" works fine — you can drag the folders in)
3. Add your three HTML apps into `app/src/main/assets/`
4. Push to `main` (or use Actions → "Build APK" → Run workflow)
5. Download the `BabesSuite-debug-apk` artifact from the finished run
6. Install via Termux + ADB over Wi-Fi, same as WearWorkout:
   `adb install app-debug.apk`

## Important: data migration

The app's localStorage starts EMPTY — it does not share data with Chrome.
Before switching over, open each tool in Chrome and use its **Export/Backup**
button, then open the same tool inside the new app and **Import/Restore**
the file. Do this once per tool and you're fully moved in.

## Adding a tool later (e.g. FOH Roles)

1. Drop the new .html file into `app/src/main/assets/`
2. Add one entry to the `apps` list at the bottom of `index.html`
3. Push — Actions rebuilds the APK
