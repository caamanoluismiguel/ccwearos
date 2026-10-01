# Integration: Status Tile + complication (Agent F)

Code lives in `app/src/main/java/com/caamano/ccwearos/tile/` and `.../complication/`.
It compiles and its 18 unit tests pass once the lines below are added. This branch
leaves the manifest and Gradle files alone on purpose.

## 1. `app/build.gradle.kts` → `dependencies { }`

```kotlin
// Tile + complication
implementation("androidx.wear.tiles:tiles:1.5.0")
implementation("androidx.wear.protolayout:protolayout:1.3.0")
implementation("androidx.wear.protolayout:protolayout-expression:1.3.0")
implementation("androidx.wear.watchface:watchface-complications-data-source-ktx:1.2.1")
implementation("androidx.concurrent:concurrent-futures-ktx:1.2.0") // SuspendToFutureAdapter
testImplementation("junit:junit:4.13.2")
```

Version catalog version, if you prefer `libs.versions.toml`:

```toml
[versions]
wearTiles = "1.5.0"
protolayout = "1.3.0"
watchfaceComplications = "1.2.1"
concurrentFutures = "1.2.0"
junit = "4.13.2"

[libraries]
wear-tiles = { group = "androidx.wear.tiles", name = "tiles", version.ref = "wearTiles" }
wear-protolayout = { group = "androidx.wear.protolayout", name = "protolayout", version.ref = "protolayout" }
wear-protolayout-expression = { group = "androidx.wear.protolayout", name = "protolayout-expression", version.ref = "protolayout" }
watchface-complications-data-source-ktx = { group = "androidx.wear.watchface", name = "watchface-complications-data-source-ktx", version.ref = "watchfaceComplications" }
concurrent-futures-ktx = { group = "androidx.concurrent", name = "concurrent-futures-ktx", version.ref = "concurrentFutures" }
junit = { group = "junit", name = "junit", version.ref = "junit" }
```

## 2. `AndroidManifest.xml` → inside `<application>`

```xml
<service
    android:name=".tile.StatusTileService"
    android:exported="true"
    android:label="@string/tile_status_label"
    android:description="@string/tile_status_description"
    android:icon="@drawable/ic_complication_mascot"
    android:permission="com.google.android.wearable.permission.BIND_TILE_PROVIDER">
    <intent-filter>
        <action android:name="androidx.wear.tiles.action.BIND_TILE_PROVIDER" />
    </intent-filter>
    <meta-data
        android:name="androidx.wear.tiles.PREVIEW"
        android:resource="@drawable/tile_preview" />
</service>

<service
    android:name=".complication.StatusComplicationService"
    android:exported="true"
    android:label="@string/complication_status_label"
    android:icon="@drawable/ic_complication_mascot"
    android:permission="com.google.android.wearable.permission.BIND_COMPLICATION_PROVIDER">
    <intent-filter>
        <action android:name="android.support.wearable.complications.ACTION_COMPLICATION_UPDATE_REQUEST" />
    </intent-filter>
    <meta-data
        android:name="android.support.wearable.complications.SUPPORTED_TYPES"
        android:value="SHORT_TEXT,RANGED_VALUE" />
    <meta-data
        android:name="android.support.wearable.complications.UPDATE_PERIOD_SECONDS"
        android:value="300" />
</service>
```

No new permissions needed (INTERNET is already declared).

## 3. Live refresh hooks (recommended)

Tiles and complications can't hold listeners. They refresh on their own
(tile every 60 s, complication every 300 s), but for instant updates call these
from the foreground service's RTDB listener whenever `/status` changes (and,
if you like, when `/claudeStatus/contextPct` or `/metrics` change):

```kotlin
import com.caamano.ccwearos.tile.TileUpdater
import com.caamano.ccwearos.complication.ComplicationUpdater

TileUpdater.requestUpdate(context)
ComplicationUpdater.requestUpdate(context)
```

Both are cheap, swallow their own exceptions and are safe on any thread.
The system throttles tile updates, so de-duplicating on the status value is
enough; no need to debounce further.

## Behaviour notes

- Reads are one-shot `get()` calls with a 4 s timeout. Signed out → "Abre la
  app". Timeout → "Sin señal".
- Permitir / Rechazar appear only when `/permissionPromptId` is set and
  `RiskClassifier` finds nothing risky in the full prompt text. At tap time the
  service re-reads status, promptId and prompt; any mismatch writes nothing.
  The `/command` payload includes `promptId`.
- Risk is checked on the whole prompt, not just the 2 lines shown. Even so,
  the tile shows at most 2 lines, so a long harmless-looking prompt can still
  be approved without being read in full. "Abrir" is always there for a full
  review.
