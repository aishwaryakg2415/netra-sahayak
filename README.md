# Netra Sahayak — Offline AI Diabetic Retinopathy Screening

**AI-Assisted Diabetic Retinopathy Screening**

> AI screening support only. Final diagnosis must be confirmed by a qualified healthcare professional.

An Android app for ASHA / rural health workers. It collects minimal patient details, captures or
selects a retinal image, checks the image quality, grades diabetic retinopathy **on the phone** with
a bundled LiteRT model, explains the result with a class-activation heatmap, and gives a transparent
referral suggestion. Screening needs **no internet**: release builds do not even have the INTERNET
permission. Every screening is stored on the phone.

> **Not clinically validated.** The model is a research/hackathon prototype trained on public
> datasets with research-only terms. Its referral thresholds are provisional and have not been
> reviewed by an ophthalmologist. See [docs/MODEL_CARD.md](docs/MODEL_CARD.md).

## 0. On-device AI at a glance

```mermaid
flowchart LR
    A[Camera / gallery] --> B[RetinalImageDecoder<br/>EXIF-upright RGB]
    B --> C{ImageQualityAssessor}
    C -- RETAKE / UNSUPPORTED --> X[Plain error, no grade]
    C -- ACCEPTED --> D[RetinaPreprocessor<br/>crop + 384 px]
    D --> E[LiteRT model<br/>logits + feature maps]
    E --> F[Temperature-scaled<br/>softmax]
    F --> G[ReferralPolicy<br/>transparent rules]
    E --> H[Class activation map<br/>= Grad-CAM for GAP head]
    G --> I[ScreeningResult]
    H --> I
    I --> J[(Room, app-private files)]
    I --> K[Existing result screen]
    subgraph Training - Kaggle GPU, never on the phone
      T1[APTOS + EyePACS + DDR] --> T2[Grouped splits + dedup] --> T3[Train / compare / calibrate] --> T4[Export FP32/FP16/INT8, pick by measurement]
      T5[DDR test, IDRiD, Messidor-2] --> T6[External evaluation]
    end
    T4 -. model_config.json + .tflite + SHA-256 .-> E
```

| Where | What |
|---|---|
| `app/src/main/java/com/sih/netrasahayak/ml/` | Kotlin: decoder, quality gate, preprocessing, LiteRT classifier, CAM, referral rules, model config |
| `repository/LocalInferenceRepository.kt` | The offline screening pipeline the UI calls |
| `ml/` | Python: datasets, training, evaluation, calibration, export, tests ([ml/README.md](ml/README.md)) |
| `docs/` | [Model card](docs/MODEL_CARD.md), [datasets & provenance](docs/DATASETS_AND_PROVENANCE.md), [deployment](docs/DEPLOYMENT.md) |
| `IMPLEMENTATION_STATUS.md` | What is implemented, tested, trained, evaluated and verified — and what is not |

The original FastAPI path (sections 4–7, 11) still exists as an optional **debug-only** mode
(`INFERENCE_MODE = "remote"`).

---

## 1. Project structure

```
NetraSahayak/
├── settings.gradle.kts, build.gradle.kts, gradle.properties
├── gradle/libs.versions.toml          ← all dependency versions
├── gradlew, gradle/wrapper/           ← Gradle 8.7 wrapper
├── local.properties                   ← sdk.dir (not committed)
├── IMPLEMENTATION_PLAN.md
└── app/
    ├── build.gradle.kts                ← BASE_URL and USE_MOCK_API live here
    ├── src/debug/res/xml/               ← debug-only: allows cleartext http to a local server
    └── src/main/
        ├── AndroidManifest.xml
        ├── res/                        ← strings, theme, launcher icon, file_paths,
        │                                  network_security_config (HTTPS-only)
        └── java/com/sih/netrasahayak/
            ├── AppConfig.kt             backend URL, mock switch, timeouts
            ├── NetraSahayakApp.kt       Application, initialises ServiceLocator
            ├── MainActivity.kt          single Activity, hosts Compose
            ├── di/ServiceLocator.kt     hand-written DI; mock vs real chosen here
            ├── model/                   DrClass, PatientDetails, ScreeningResult,
            │                            AppError, Outcome
            ├── network/                 NetraApiService (Retrofit), dto/,
            │                            RetrofitProvider, ConnectivityObserver
            ├── database/                Room: ScreeningEntity, ScreeningDao, NetraDatabase
            ├── camera/                  RetinalCameraController (interface),
            │                            PhoneCameraController (CameraX), ImageUtils
            ├── repository/              InferenceRepository (+Remote/Mock/Local),
            │                            ScreeningRepository, SyncRepository, SyncDataSource
            └── ui/
                ├── theme/               Color, Type, Theme (fixed high-contrast light)
                ├── components/          PrimaryButton, SecondaryButton, ImagePreviewCard,
                │                        ResultCard, ConfidenceIndicator, LoadingView,
                │                        ErrorView, EmptyHistoryView, RetinalImageViewer,
                │                        DisclaimerBanner, NetraScaffold, Formatters
                ├── navigation/          Routes, NetraNavHost
                ├── home/ patient/ imagesource/ camera/ preview/ result/
                ├── history/             list + detail + view models
                ├── sync/                sync screen + view model
                └── screening/           ScreeningViewModel (shared across the flow)
```

### Architecture

```
Composable  →  ViewModel  →  Repository  →  Retrofit  →  FastAPI
                                  ↘  Room (offline history)
```

Retrofit is never called from a Composable. Screens receive plain state and callbacks; all I/O
happens in a repository on `Dispatchers.IO`.

The whole PATIENT → IMAGE → PREVIEW → RESULT flow is one nested navigation graph sharing a single
`ScreeningViewModel`. Leaving the graph destroys it, so the next patient always starts clean.

---

## 2. Size of the codebase

52 Kotlin files plus the Gradle, manifest and resource scaffolding — the full layout is the tree
above. No generated build output is tracked; `app/build/`, `.gradle/` and `local.properties` are
ignored by git and recreated on each machine.

---

## 3. How to run the app

**Option A — Android Studio (normal route)**

1. Open Android Studio → *Open* → select the `NetraSahayak` folder.
2. Let it sync (it downloads Gradle 8.7 and the AGP 8.5.2 dependencies once).
3. Pick a device or emulator (API 24+) and press ▶︎.

The debug build runs in **mock mode** — no backend needed. Pick any photo from the gallery and the
full result screen, including a generated placeholder heatmap, appears.

**Option B — command line**

```bash
cd NetraSahayak
./gradlew assembleDebug     # macOS/Linux   → app/build/outputs/apk/debug/app-debug.apk
.\\gradlew.bat assembleDebug # Windows
```

Requirements: JDK 17 and the Android SDK (API 34 platform + build-tools 34.0.0) — Android Studio
installs both. See §3a if Gradle cannot find the SDK.

---

## 3a. First-time setup (Windows, macOS or Linux)

The project is fully portable - nothing in the source hard-codes a path. Only `local.properties`
is machine-specific, and it is deliberately **not** included (Gradle or Android Studio recreates it).

**Recommended: Android Studio**

1. Install [Android Studio](https://developer.android.com/studio) (bundles a JDK and the Android SDK).
2. Unzip the project somewhere without spaces in the path, e.g. `C:\dev\NetraSahayak`.
3. Android Studio → **Open** → select the `NetraSahayak` folder.
4. It will say the SDK location is missing and offer to fix it - accept. That writes
   `local.properties` with the Windows SDK path, e.g. `sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk`.
5. Let Gradle sync (first run downloads Gradle 8.7 and the dependencies - a few minutes).
6. Choose a device or emulator (API 24+) and press **Run**.

**Command line (PowerShell)**

```powershell
cd C:\dev\NetraSahayak
.\gradlew.bat assembleDebug      # APK -> app\build\outputs\apk\debug\app-debug.apk
.\gradlew.bat installDebug       # build + install on a connected device/emulator
```

Requires JDK 17 and the Android SDK (API 34 + build-tools 34.0.0). If Gradle cannot find the SDK,
create `local.properties` in the project root containing your own path:

```
sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk
```

**Note:** `run.sh` and `tools/push_sample_image.sh` are macOS/Linux helpers. On Windows use
Android Studio's Run button, or the `gradlew.bat` commands above. `tools/fake_backend.py` is
pure Python and works everywhere (`python tools\fake_backend.py`).

---

## 3b. Trying it out

Once the project runs, there is nothing else to configure — the debug build works offline with no
backend at all.

```bash
./run.sh                       # macOS/Linux: starts an emulator if needed, builds, installs, launches
./run.sh emulator              # just start an emulator
./tools/push_sample_image.sh   # put a sample retinal image in the device's gallery
```

On Windows, press **Run** in Android Studio instead, and add the sample image by dragging
`tools/sample_fundus.jpg` onto the running emulator window.

Then, in the app: **START NEW SCREENING** → Patient ID `P001`, Age `55` → **CONTINUE** →
**CHOOSE FROM GALLERY** → pick the sample → **ANALYZE IMAGE**. A result and a generated heatmap
appear after about two seconds. Try the ORIGINAL / HEATMAP / OVERLAY toggle, press **DONE**, then
open **SCREENING HISTORY**.

Useful commands (`adb` lives in `<Android SDK>/platform-tools`):

```bash
adb logcat | grep -i netrasahayak                  # app logs
adb shell cmd connectivity airplane-mode enable    # test offline behaviour
adb shell cmd connectivity airplane-mode disable
adb devices                                        # list connected devices
adb emu kill                                       # shut the emulator down
```

### Trying the real network path without a backend

`tools/fake_backend.py` is a stdlib-only stand-in that speaks the exact `/predict` contract and
serves a heatmap from `/results/`. Useful for checking the Retrofit path before the FastAPI server
exists:

```bash
python3 tools/fake_backend.py    # listens on :8000, prints the multipart parts it receives
```

Then set `USE_MOCK_API` to `false` in `app/build.gradle.kts`, rebuild, and screen a patient — the
request goes over the wire to that server. Set it back to `true` afterwards.

### Testing on a real Android phone

Enable Developer options → USB debugging, plug it in, then `./gradlew installDebug`. For a real
backend, set `BASE_URL` to your computer's LAN IP (e.g. `http://192.168.1.7:8000/`) and start
uvicorn with `--host 0.0.0.0`.

---

## 4. How to connect the FastAPI backend

1. Run FastAPI on your machine: `uvicorn main:app --host 0.0.0.0 --port 8000`
2. Turn the mock off — in `app/build.gradle.kts`, `defaultConfig`:
   ```kotlin
   buildConfigField("boolean", "USE_MOCK_API", "false")
   ```
3. Point `BASE_URL` at the server (see §7).
4. Rebuild and run.

For a reference implementation the backend needs to satisfy exactly this:

```python
from fastapi import FastAPI, File, Form, UploadFile

app = FastAPI()

@app.post("/predict")
async def predict(
    image: UploadFile = File(...),
    patient_id: str | None = Form(None),
    age: int | None = Form(None),
    gender: str | None = Form(None),
    diabetes_duration: int | None = Form(None),
):
    # EfficientNet inference + Grad-CAM here
    return {
        "prediction": "Moderate Diabetic Retinopathy",
        "confidence": 0.91,
        "heatmap_url": "/results/abc123_heatmap.jpg",
        "recommendation": "Clinical evaluation by an ophthalmologist is recommended.",
    }
```

The heatmap file must be reachable over HTTP — e.g.
`app.mount("/results", StaticFiles(directory="results"))` — because Android loads it by URL.

---

## 5. Expected API request format

`POST {BASE_URL}predict`, `Content-Type: multipart/form-data`

| Part | Required | Type | Notes |
|---|---|---|---|
| `image` | yes | file (`image/jpeg`) | Down-scaled to max 1024 px on the long edge, EXIF-rotated upright, JPEG quality 90 |
| `patient_id` | optional | text | |
| `age` | optional | text (integer) | |
| `gender` | optional | text | `female` \| `male` \| `other` |
| `diabetes_duration` | optional | text (integer years) | |

Optional parts are omitted entirely when the worker did not fill them in.
Defined in `network/NetraApiService.kt`; the multipart body is assembled in
`repository/RemoteInferenceRepository.kt`.

---

## 6. Expected API response format

```json
{
  "prediction": "Moderate Diabetic Retinopathy",
  "confidence": 0.91,
  "heatmap_url": "/results/abc123_heatmap.jpg",
  "recommendation": "Clinical evaluation by an ophthalmologist is recommended."
}
```

Mapped by `network/dto/PredictionResponseDto.kt` → `model/ScreeningResult.kt`.

- `prediction` — parsing is forgiving (`model/DrClass.kt`): `"Moderate Diabetic Retinopathy"`,
  `"moderate_dr"`, `"Moderate DR"` and `"2"` all resolve to the same grade. An unrecognised value
  produces a clean *"unexpected reply"* message rather than a crash.
- `confidence` — `0.91` or `91` both work; clamped to 0–1.
- `heatmap_url` — relative or absolute. Relative paths are resolved against `BASE_URL`
  (`RetrofitProvider.resolveUrl`). May be omitted; the UI then hides the heatmap views.
- `recommendation` — optional. If missing, a sensible default for that grade is used.

The five supported classes: No / Mild / Moderate / Severe / Proliferative Diabetic Retinopathy.

---

## 7. Where to put the backend URL

`app/build.gradle.kts` — it is a `BuildConfig` field, so it is never hardcoded in Kotlin and can
differ per build type. No API keys or credentials are stored anywhere in the app.

```kotlin
defaultConfig {
    buildConfigField("String", "BASE_URL", "\"http://10.0.2.2:8000/\"")   // emulator → your laptop
    buildConfigField("boolean", "USE_MOCK_API", "true")
}
buildTypes {
    release {
        buildConfigField("String", "BASE_URL", "\"https://your-server.example.com/\"")
        buildConfigField("boolean", "USE_MOCK_API", "false")
    }
}
```

| Where you run FastAPI | BASE_URL |
|---|---|
| Laptop + Android **emulator** | `http://10.0.2.2:8000/` |
| Laptop + **physical phone** on the same Wi-Fi | `http://192.168.x.x:8000/` |
| Cloud / production | `https://your-domain/` |

The value is read once through `AppConfig.BASE_URL` and used by `RetrofitProvider`.

**Cleartext http:** Android blocks plain `http://` by default at `targetSdk 34`, so the app ships a
network security config:

| File | Applies to | Policy |
|---|---|---|
| `app/src/debug/res/xml/network_security_config.xml` | debug builds | cleartext allowed (10.0.2.2, localhost, any LAN IP) |
| `app/src/main/res/xml/network_security_config.xml` | release builds | **HTTPS only** |

So `http://10.0.2.2:8000/` works out of the box while developing, and a release build cannot silently
fall back to unencrypted traffic. **Production must use HTTPS.**

---

## 8. How camera input works

- Tapping **TAKE PHOTO** on the Image Source screen is the *only* moment the `CAMERA` permission is
  requested. Denial shows a plain message, never a crash.
- `ui/camera/CameraScreen.kt` hosts a CameraX `PreviewView` and one large CAPTURE button. It
  re-checks permission itself, so RETAKE also works when the user arrived via the gallery.
- All CameraX work sits behind `camera/RetinalCameraController` (interface), implemented today by
  `camera/PhoneCameraController` using the ordinary rear camera. **No fundus-camera SDK is assumed.**
  When a real fundus device arrives, add a second implementation of that interface and return it
  from `ServiceLocator.createCameraController()` — no screen changes needed.
- The photo is written to the app cache (`ImageUtils.newCaptureFile`) and handed back as a `Uri`.

## 9. How gallery input works

- **CHOOSE FROM GALLERY** uses `ActivityResultContracts.PickVisualMedia` — Android's modern photo
  picker. It needs **no storage permission**, and falls back to the document picker on older
  devices automatically.
- The chosen `Uri` is verified as a decodable image (`ImageUtils.isReadableImage`) before the app
  moves on; an unreadable file produces *"This file is not a valid image."*

### Image handling (both sources)

`camera/ImageUtils.kt` does the work:
- **Original preserved** — copied byte-for-byte into app-private storage so history can show it.
- **Upload copy only** is modified: down-sampled to ≤1024 px, EXIF rotation applied, JPEG q90.
- Out-of-memory on a huge file becomes *"This image is too large to process."*
- Upload temp files are deleted after the request; the original is untouched.
- The backend still performs all ML preprocessing.

---

## 10. How the Grad-CAM heatmap is displayed

`ui/components/RetinalImageViewer.kt` shows one square image with a three-way toggle:

```
[ ORIGINAL ]   [ HEATMAP ]   [ OVERLAY ]
```

- **ORIGINAL** — the retinal photo as captured.
- **HEATMAP** — the image at `heatmap_url`, loaded by Coil straight from the backend.
- **OVERLAY** — the heatmap drawn on top of the original at 55 % alpha, both with
  `ContentScale.Fit` so they align.

If `heatmap_url` is absent, the last two are disabled and a short line explains why. Above it the
result screen shows *"Why this result?"* and *"Highlighted regions indicate areas that contributed
to the AI model's prediction."*

Grad-CAM is **not** computed on Android — the app only displays what Python returns. The same
viewer is reused on the history detail screen.

---

## 11. Replacing the mock API with the real API

One flag, one place:

```kotlin
// app/build.gradle.kts
buildConfigField("boolean", "USE_MOCK_API", "false")
```

`di/ServiceLocator.kt` reads it and hands the ViewModel either
`MockInferenceRepository` or `RemoteInferenceRepository`. Nothing else changes — the ViewModel and
every screen depend only on the `InferenceRepository` interface.

The release build type already sets `USE_MOCK_API = false`, so **a release APK can never use mock
data.** All mock code is confined to `MockInferenceRepository` and `MockSyncDataSource`, both marked
`MOCK / DEMO ONLY` in comments. There is no fake prediction logic anywhere in the production path.

**Offline inference later:** `repository/LocalInferenceRepository.kt` is the reserved slot for a
bundled TensorFlow Lite EfficientNet. Its header documents the exact steps (add the TFLite
dependency, drop the `.tflite` in `assets/`, implement `analyze`, return it from `ServiceLocator`).
Until then it fails with a clear message rather than pretending to work.

---

## 12. Offline behaviour

Everything works with **no internet**, including AI screening, the explanation heatmap and history:
the model ships inside the APK and the heatmap is computed and stored on the phone. Release builds
have no INTERNET permission. `tools/verify_offline.sh` runs the real inference tests on a device in
airplane mode.

(Only the optional debug-only `remote` mode and the proposed Sync upload need a connection.)

Results are written to Room immediately after the server replies, marked `synced = false`. The Sync
screen shows the connection state and how many records are waiting, and **SYNC NOW** pushes them
through `SyncRepository` / `SyncDataSource`.

---

## 13. Error handling

Every failure is an `AppError` (`model/AppError.kt`) with a plain-language message. Raw exceptions
and stack traces are never shown. Covered: camera permission denied, camera unavailable, no image
selected, invalid image, image too large, no internet, timeout, server unreachable, HTTP error
codes, invalid/unparseable response, failed upload, local storage failure, empty Patient ID,
invalid age, offline model unavailable.

Duplicate submissions are prevented in `ScreeningViewModel.analyze()` and `SyncViewModel.syncNow()`.

---

## 14. Known limitations

1. **No fundus camera.** Ordinary phone photos are not clinically adequate input for a DR model.
   The camera abstraction is ready for a real device, but screening quality depends on it.
2. **Mock results are meaningless.** In demo mode the grade is derived from the Patient ID hash so
   demos are repeatable — it carries no clinical information whatsoever.
3. **`POST /screenings` (sync) is a proposed contract.** No backend implements it yet; with
   `USE_MOCK_API = false` the Sync screen will report a server error until it exists.
4. **On-device model is a research prototype.** See the model card for measured performance,
   data licences and what has *not* been validated (clinical use, phone-camera images, Indian
   population-specific performance).
5. **Gallery URI lifetime.** A picked `content://` URI is valid for the current process. If Android
   kills the app while the preview is open, re-pick the image. Once analysed, the image is copied
   into app storage and is permanent.
6. **Heatmaps** from the on-device model are local files and always available offline. (Heatmaps
   from the optional debug backend are not cached — only their URL is stored.)
7. **Portrait only, light theme only** — deliberate, for consistency and daylight readability.
8. **No authentication.** Out of scope for the MVP, as specified.
