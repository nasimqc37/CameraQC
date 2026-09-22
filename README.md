# CameraQC Starter

Android/Kotlin + CameraX starter app. Captures a photo and scores:
- **Sharpness** — Laplacian variance (higher = sharper)
- **Noise** — Immerkær noise estimation (higher = noisier)

## Build fully online (no local Android Studio needed)

1. Create an empty repo on GitHub.
2. Upload this whole folder to it (GitHub web UI supports drag-and-drop upload
   of a folder's files, or use GitHub Codespaces' terminal with `git push`).
3. Go to **codemagic.io** → sign in with GitHub → **Add application** → select
   this repo. It auto-detects the Android/Gradle project.
4. Pick the UI-based workflow, set output to **Debug APK**, click **Start new
   build**.
5. Once the build finishes, download the APK from the **Artifacts** section.

## Editing the code online

Open the repo on GitHub → click **Code** → **Codespaces** tab → **Create
codespace on main**. This opens a full browser-based VS Code with a terminal —
edit `MainActivity.kt`, then commit + push to trigger a new Codemagic build.

## Notes

- `minSdk 24` (Android 7.0+), `compileSdk 34`.
- Uses CameraX (`camera-core`, `camera-camera2`, `camera-lifecycle`,
  `camera-view`) — same as the existing CameraQC tool.
- Scoring runs on a background thread so the UI doesn't freeze after capture.
