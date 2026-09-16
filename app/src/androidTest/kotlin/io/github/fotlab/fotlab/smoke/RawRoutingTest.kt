package io.github.fotlab.fotlab.smoke

import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import io.github.fotlab.fotlab.feature.studio.StudioEngine
import io.github.fotlab.fotlab.feature.studio.StudioRenderResult
import io.github.fotlab.fotlab.media.FormatSniffer
import io.github.fotlab.fotlab.media.Route
import io.github.fotlab.fotlab.media.SniffResult
import io.github.fotlab.fotlab.media.Sniffer
import io.github.fotlab.fotlab.media.route
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the RAW path is REAL for the camera formats we ship — Canon CR2, Sony ARW (x2),
 * Nikon NEF and Panasonic RW2 — on the emulator, with a trace of every state on the way.
 *
 * Why this class exists: everything else only ever exercised PNG. For a RAW the app has two
 * ways to produce pixels, and they are NOT equivalent:
 *
 *  1. **Coil on the original** — Coil/Android can at best pull the *embedded preview* out of a
 *     RAW container (a few hundred pixels across). It looks like "the image opened" but it is
 *     not the photograph.
 *  2. **rawler (dnglab) → PNG** — the real demosaic of the sensor data, full frame.
 *
 * `FOTLAB-STUDIO-000001` / R8 says RAW must take path 2: [FormatSniffer] asks rawler, the route
 * resolves to [Route.RawToRaster], and Studio renders the decoded PNG. This test asserts that
 * whole chain per format, and — the decisive part — checks the SIZE of the decoded PNG: an
 * embedded preview never reaches [FULL_FRAME_MIN_WIDTH] pixels wide, a demosaiced frame does.
 *
 * The corpus is the public `fot-lab/rawdb` repository (`samples` branch). smoke_emulator.yaml
 * downloads it on the runner and `adb push`es it into the app's external files dir
 * (`getExternalFilesDir/rawdb`), which the app can read without any storage permission.
 *
 * Every step logs under `RAW-E2E`; the job ships per-test logcat on failure.
 */
@RunWith(AndroidJUnit4::class)
class RawRoutingTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val tag = "RAW-E2E"
    private var t0 = 0L

    private fun step(phase: String, detail: String) {
        val ms = (System.nanoTime() - t0) / 1_000_000
        Log.i(tag, "[T+${ms}ms][$phase] $detail")
    }

    // ---------------------------------------------------------------- corpus

    /** One sample: its file name in `rawdb/`, and what the trace should call it. */
    private data class Sample(val file: String, val label: String)

    private val canonCr2 = Sample("Canon EOS 5DS_ISO_1000_RAW.CR2", "Canon EOS 5DS CR2")
    private val sonyArw7r = Sample("ILCE-7R_ISO_50_14bits_Sony ARW Compressed.ARW", "Sony ILCE-7R ARW")
    private val sonyArw7rm2 = Sample("ILCE-7RM2_ISO_100_14bits_Compressed RAW.ARW", "Sony ILCE-7RM2 ARW")
    private val nikonNef = Sample("NIKON D850_Large_ISO_64_14bits_Lossless.NEF", "Nikon D850 NEF")
    private val panasonicRw2 = Sample("DC-S1R_ISO_100_6fmt_8368x5584.RW2", "Panasonic DC-S1R RW2")

    // ---------------------------------------------------------------- tests (one per sample)

    @Test
    fun canonCr2RoutesToRawlerAndDecodesFullFrame() = runSample(canonCr2)

    @Test
    fun sonyIlce7rArwRoutesToRawlerAndDecodesFullFrame() = runSample(sonyArw7r)

    @Test
    fun sonyIlce7rm2ArwRoutesToRawlerAndDecodesFullFrame() = runSample(sonyArw7rm2)

    @Test
    fun nikonD850NefRoutesToRawlerAndDecodesFullFrame() = runSample(nikonNef)

    @Test
    fun panasonicDcS1rRw2RoutesToRawlerAndDecodesFullFrame() = runSample(panasonicRw2)

    // ---------------------------------------------------------------- the journey

    /**
     * The whole chain for one RAW, in production order: locate → sniff → route → Studio decode →
     * measure the decoded frame. Every intermediate state is logged, so a failure says WHICH
     * stage settled wrongly rather than just "the image did not open".
     */
    private fun runSample(sample: Sample) {
        t0 = System.nanoTime()
        val file = File(File(context.getExternalFilesDir(null), RAWDB_DIR), sample.file)
        step("fixture", "${sample.label}: ${file.absolutePath}")
        assertTrue(
            "sample missing: ${file.absolutePath} — smoke_emulator.yaml must adb push the rawdb corpus " +
                "into getExternalFilesDir/rawdb before the tests run",
            file.isFile,
        )
        val sizeMb = file.length() / 1_048_576
        step("fixture", "size=${file.length()} bytes ($sizeMb MB)")
        assertTrue("sample is empty: ${file.name}", file.length() > 0)
        step("fixture", "magic=${file.magicHex(16)}")

        val uri = Uri.fromFile(file)
        try {
            // ---- 1) sniff, exactly as StudioEngine.runPipeline does (1 MiB header) ----
            val header = context.contentResolver.openInputStream(uri)!!.use { it.readHeader(HEADER_BYTES) }
            step("sniff", "read header: ${header.size} bytes")
            val tSniff = System.nanoTime()
            val sniff = runBlocking { FormatSniffer.sniff(header, SNIFF_TIMEOUT_MS) }
            step("sniff", "FormatSniffer.sniff took ${(System.nanoTime() - tSniff) / 1_000_000} ms")
            assertTrue("sniff timed out on ${sample.label}", sniff is SniffResult.Ok)

            val verdicts = (sniff as SniffResult.Ok).verdicts
            val coil = verdicts[Sniffer.COIL]
            val rawler = verdicts[Sniffer.RAWLER]
            step("sniff", "COIL  verdict: format=${coil?.format} canDecode=${coil?.canDecode}")
            step("sniff", "RAWLR verdict: format=${rawler?.format} canDecode=${rawler?.canDecode}")

            // Coil must NOT claim a RAW: BitmapFactory cannot demosaic one. If it ever does,
            // that is the embedded-preview trap this class exists to detect.
            if (coil != null && coil.canDecode) {
                step("sniff", "NOTE: Coil also claims canDecode (${coil.format}) — it can only be an embedded preview")
            }
            assertTrue("rawler must recognize ${sample.label}", rawler != null && rawler.canDecode)
            assertTrue("rawler must name the format for ${sample.label}", !rawler!!.format.isNullOrBlank())

            // ---- 2) the pure routing decision ----
            val r = route(verdicts)
            step("route", "route()=${r.javaClass.simpleName} format=${(r as? Route.RawToRaster)?.format}")
            assertTrue("${sample.label} must route to RawToRaster, was $r", r is Route.RawToRaster)

            // ---- 3) what Coil does with the RAW on its own (evidence, not an assertion) ----
            val tCoil = System.nanoTime()
            val coilResult = runCatching {
                runBlocking {
                    context.imageLoader.execute(ImageRequest.Builder(context).data(uri).build())
                }
            }
            val coilMs = (System.nanoTime() - tCoil) / 1_000_000
            val coilInfo = coilResult.getOrNull()
            val drawable = (coilInfo as? SuccessResult)?.drawable
            step(
                "coil",
                "direct Coil execute: ${coilInfo?.javaClass?.simpleName ?: coilResult.exceptionOrNull()?.javaClass?.simpleName} " +
                    "in $coilMs ms" + if (drawable != null) {
                        " preview=${drawable.intrinsicWidth}x${drawable.intrinsicHeight}"
                    } else {
                        ""
                    },
            )

            // ---- 4) the real Studio pipeline ----
            val states = mutableListOf<String>()
            StudioEngine.setCurrentNode(uri.toString())
            val ready = runBlocking {
                withTimeout(DECODE_TIMEOUT_MS) {
                    StudioEngine.renderResult
                        .onEach { states += it.javaClass.simpleName }
                        .filterNot { it is StudioRenderResult.Idle || it is StudioRenderResult.Loading }
                        .first()
                }
            }
            step("studio", "renderResult transitions: $states")
            step("studio", "final=${ready.javaClass.simpleName}")
            assertTrue("Studio must reach Ready for ${sample.label}, was $ready", ready is StudioRenderResult.Ready)

            val model = (ready as StudioRenderResult.Ready).model
            // THIS is the assertion that separates rawler from a preview: the rawler branch hands
            // the renderer decoded PNG BYTES, the Coil branch hands it the original Uri.
            assertTrue(
                "${sample.label} must render decoded PNG bytes (rawler path), got ${model.javaClass.simpleName}",
                model is ByteBuffer,
            )
            val png = (model as ByteBuffer).toByteArray()
            step("studio", "rawler returned ${png.size} bytes, PNG magic=${png.magicHex(8)}")

            // ---- 5) measure the decoded frame ----
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(png, 0, png.size, opts)
            step("decode", "decoded PNG = ${opts.outWidth}x${opts.outHeight} (${opts.outMimeType})")
            assertTrue("rawler output is not a decodable PNG for ${sample.label}", opts.outWidth > 0)
            assertTrue(
                "decoded frame is ${opts.outWidth}x${opts.outHeight} — that is an embedded preview, not a " +
                    "demosaiced full frame (${sample.label})",
                opts.outWidth >= FULL_FRAME_MIN_WIDTH,
            )
            step("decode", "FULL FRAME confirmed: ${opts.outWidth}x${opts.outHeight}")
        } finally {
            runCatching { StudioEngine.setCurrentNode(null) }
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Mirrors the API-safe header loop in StudioEngine (`readNBytes` needs API 33). */
    private fun InputStream.readHeader(max: Int): ByteArray {
        val buffer = ByteArray(max)
        var filled = 0
        while (filled < max) {
            val read = read(buffer, filled, max - filled)
            if (read < 0) break
            filled += read
        }
        return if (filled == max) buffer else buffer.copyOf(filled)
    }

    private fun File.magicHex(count: Int): String {
        val buf = ByteArray(count)
        inputStream().use { s ->
            var filled = 0
            while (filled < count) {
                val read = s.read(buf, filled, count - filled)
                if (read < 0) break
                filled += read
            }
        }
        return buf.joinToString(" ") { b -> "%02X".format(b) }
    }

    private fun ByteArray.magicHex(count: Int): String =
        copyOf(minOf(count, size)).joinToString(" ") { b -> "%02X".format(b) }

    private fun ByteBuffer.toByteArray(): ByteArray = ByteArray(remaining()).also { get(it) }

    private companion object {
        /** Directory smoke_emulator.yaml pushes the rawdb corpus into. */
        const val RAWDB_DIR = "rawdb"

        /** Same bound StudioEngine hands to the sniffer (1 MiB). */
        const val HEADER_BYTES = 1024 * 1024

        /**
         * Generous: the native identify on a 1 MiB header is fast, but the pipeline below is not.
         */
        const val SNIFF_TIMEOUT_MS = 30_000L

        /** A 36–50 MP demosaic on a software-rendered emulator is slow; 5 min per sample. */
        const val DECODE_TIMEOUT_MS = 300_000L

        /**
         * An embedded preview is at most a few hundred to ~2k pixels wide; every sample here is
         * 36 MP or more (narrowest full frame: 7360 px). Anything below this is a preview.
         */
        const val FULL_FRAME_MIN_WIDTH = 3000
    }
}
