# Localization and formatting — English hard-coded in the viewer detail panel, unsafe and locale-implicit date formatting

- ID: ACTION-KOTLIN-000006
- Status: Observation
- Priority: P1
- Created: 2026-09-16
- Owner: —
- Related: `ACTION-KOTLIN-000001` (master audit), `rules/DESIGN/detail/FOTLAB-IMGMGR-000001.md`, `app/src/main/kotlin/io/github/fotlab/fotlab/feature/library/LibraryViewerDialog.kt`, `app/src/main/kotlin/io/github/fotlab/fotlab/feature/library/LibraryScreen.kt`, `app/src/main/kotlin/io/github/fotlab/fotlab/feature/library/LibraryScreenRecycle.kt`

## Background & Goal

Every other screen in the app resolves user-visible text through `stringResource`; `res/values/strings.xml` carries the library, recycle and viewer copy. This item records the places where user-facing text or formatting bypasses that, and the date/number formatting that is either not thread-safe or not locale-explicit.

## Finding

### 1. English hard-coded in the viewer detail panel (K-06, P1)

`LibraryViewerDialog.kt`:

```370:370:app/src/main/kotlin/io/github/fotlab/fotlab/feature/library/LibraryViewerDialog.kt
                val text = if (exposure >= 1) "${exposure}s" else "1/${(1 / exposure).roundToInt()} s"
```

```376:376:app/src/main/kotlin/io/github/fotlab/fotlab/feature/library/LibraryViewerDialog.kt
                rows += DetailRow(stringS(context, R.string.library_viewer_label_aperture), "f/$aperture")
```

```387:387:app/src/main/kotlin/io/github/fotlab/fotlab/feature/library/LibraryViewerDialog.kt
                rows += DetailRow(stringS(context, R.string.library_viewer_label_focal), "$focal mm")
```

```395:395:app/src/main/kotlin/io/github/fotlab/fotlab/feature/library/LibraryViewerDialog.kt
                    if (fired) "Fired" else "Did not fire",
```

```421:431:app/src/main/kotlin/io/github/fotlab/fotlab/feature/library/LibraryViewerDialog.kt
private fun exifOrientationText(value: Int): String = when (value) {
    ExifInterface.ORIENTATION_ROTATE_90 -> "Rotate 90° CW"
    ExifInterface.ORIENTATION_ROTATE_180 -> "Rotate 180°"
    ExifInterface.ORIENTATION_ROTATE_270 -> "Rotate 270° CW"
    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> "Flip horizontal"
    ExifInterface.ORIENTATION_FLIP_VERTICAL -> "Flip vertical"
    ExifInterface.ORIENTATION_TRANSPOSE -> "Transpose"
    ExifInterface.ORIENTATION_TRANSVERSE -> "Transverse"
    ExifInterface.ORIENTATION_NORMAL -> "Normal"
    else -> "Normal"
}
```

The eight orientation labels are the worst case — they are complete sentences in a `when`, with no resource indirection at all. Lint `SetTextI18n` covers these. The surrounding labels (`library_viewer_label_*`) are correctly resource-driven, so the panel shows a mix of localised labels and English values.

### 2. Three shared, non-thread-safe `SimpleDateFormat` instances (K-09, P2)

- `LibraryScreen.kt:626` — `private val nodeDateformat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())` with the KDoc "Secondary line under a node name".
- `LibraryScreenRecycle.kt:75` — `private val recycleBatchFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())`, used to name recycle batch folders.
- `LibraryViewerDialog.kt:443` — `private val EXIF_DATE_FORMAT = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)` (this one pins the locale, correctly, because it parses an EXIF string).

`SimpleDateFormat` is documented as not thread-safe; sharing one instance across callers is unsafe. `minSdk` is 26, so `java.time` is fully available, and the code base already uses `DateUtils.formatDateTime` twice in the same file.

### 3. `String.format` with an implicit locale (K-10, P2)

- `LibraryViewerDialog.kt:364` — `"%.5f, %.5f".format(ll[0], ll[1])` for GPS coordinates.
- `LibraryViewerDialog.kt:410` — `"%d:%02d".format(seconds / 60, seconds % 60)` for video duration.

Both take the default locale. Decimal separators and digits change with it, so the same coordinate renders differently on different devices, and Lint `DefaultLocale` flags both.

## Impact / Conflict

- Item 1 is user-visible: a localised app showing "Fired" / "Did not fire" and "Rotate 90° CW" next to Chinese labels.
- Item 2 is latent rather than active — all three formatters are currently reached from the main thread — but one of them (`nodeDateformat`) is called from inside a list cell, so any future background rendering would break it without warning.
- Item 3 changes output per device, which is worse than being wrong consistently.
- No rule conflict. `FOTLAB-IMGMGR-000001` does not specify detail-panel formatting.

## Recommendation

1. Move all eight orientation labels plus the exposure / aperture / focal / flash values into `strings.xml`. Because `loadDetails` is a `suspend` function with a `Context`, the cleanest shape is to widen `DetailRow` to carry either a resolved `String` or a `@StringRes` + `vararg Any` pair, resolved at the point the row is built.
2. Replace the three `SimpleDateFormat` instances with `java.time.DateTimeFormatter` (all three formats are expressible with pattern strings, and `DateTimeFormatter` is immutable and thread-safe). Alternatively use `DateUtils.formatDateTime`, which the file already uses and which handles locale and 24-hour preference for free.
3. Pass `Locale.US` to both `format` calls — or, for the duration, use `DateUtils.formatElapsedTime`, and for GPS, `String.format(Locale.US, …)`.
4. While in `LibraryViewerDialog`, note that `loadImageExif` mutates a `MutableList<DetailRow>` passed in from the caller; returning a `List<DetailRow>` instead would make the function testable and remove the shared-mutable-state pattern.

## Change History

- 2026-09-16 — Recorded from the full-source Kotlin audit (`ACTION-KOTLIN-000001`). Three findings: eight-plus English strings hard-coded in the EXIF detail panel (P1), three shared non-thread-safe `SimpleDateFormat` instances (P2), and two `String.format` calls with an implicit locale (P2). No code changed.
