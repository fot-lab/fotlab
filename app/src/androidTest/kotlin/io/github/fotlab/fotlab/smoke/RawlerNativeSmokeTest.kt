package io.github.fotlab.fotlab.smoke

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.fotlab.fotlab_rawler.DemosaicAlgorithm
import io.github.fotlab.fotlab_rawler.DevelopParams
import io.github.fotlab.fotlab_rawler.RawlerFotlabBridge
import io.github.fotlab.fotlab_rawler.developToPng
import io.github.fotlab.fotlab_rawler.identify
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Native smoke test for the `rawler_fotlab` bridge — the first-party Rust library reached over
 * UniFFI/JNA (`FOTLAB-STUDIO-000001`).
 *
 * This is the regression guard for the crash class where handing bytes to the native rawler took
 * the whole process down: a native panic, a `SIGSEGV` inside `System.loadLibrary`, or a
 * `UnsatisfiedLinkError` on the emulator's ABI all abort the instrumentation process, which JUnit
 * reports as a failed run. [RawlerFotlabBridge] wraps every call in `runCatching`, so a *JVM*
 * exception cannot be observed here — only a process-level abort can, which is exactly what these
 * cases must catch.
 *
 * The inputs cover both sides of the reported failure: a PNG (a raster the rawler path must
 * decline, not die on) and arbitrary non-image bytes.
 */
@RunWith(AndroidJUnit4::class)
class RawlerNativeSmokeTest {

    /** A real 1x1 PNG, produced on-device so no test asset has to be committed. */
    private val pngBytes: ByteArray
        get() = ByteArrayOutputStream().use { out ->
            Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                .compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }

    /** Deterministic bytes that are not any RAW container rawler could recognize. */
    private val notAnImage: ByteArray = ByteArray(4096) { (it % 251).toByte() }

    /**
     * Closes the hole every other case in this class leaves open: because the bridge wraps its
     * calls in `runCatching`, an APK whose `librawler_fotlab.so` is missing or does not carry the
     * emulator's ABI answers `null` everywhere and would let the suite pass while the native path
     * is dead. Loading the library directly fails loudly instead. The library is already loaded by
     * the UniFFI runtime, and a repeated load is a no-op.
     */
    @Test
    fun nativeLibraryIsLoadableOnThisAbi() {
        System.loadLibrary("rawler_fotlab")
    }

    /** Call #1 (identify): unrecognized bytes must report "not a RAW", never abort the process. */
    @Test
    fun identifyRejectsNonRawBytes() {
        assertNull(RawlerFotlabBridge.identifyFormat(notAnImage))
    }

    /** Call #1 on a raster: the rawler sniffer must decline it without killing the process. */
    @Test
    fun identifyOnPngSurvives() {
        // Only survival is asserted: whether the sniff reports a format is rawler's business,
        // but it must not abort. A process-level abort fails this test by construction.
        RawlerFotlabBridge.identifyFormat(pngBytes)
    }

    /** Call #2 (decode): non-RAW input degrades to `null` instead of aborting. */
    @Test
    fun decodeOfNonRawBytesReturnsNull() {
        assertNull(RawlerFotlabBridge.decodeRawToPng(notAnImage))
    }

    /** Call #2 on PNG bytes: same contract as the identify call — decline, never abort. */
    @Test
    fun decodeOfPngSurvives() {
        RawlerFotlabBridge.decodeRawToPng(pngBytes)
    }

    /** A develop request with the default CFA algorithm — used to probe the develop path. */
    private val developParams: DevelopParams
        get() = DevelopParams(demosaicAlgorithm = DemosaicAlgorithm.DEFAULT, exposureEv = 0.0f, wb = null)

    /** Call #3 (develop): non-RAW input degrades to `null` instead of aborting the process. */
    @Test
    fun developOfNonRawBytesReturnsNull() {
        assertNull(RawlerFotlabBridge.developRawToPng(notAnImage, developParams))
    }

    /** Develop call on PNG bytes: same contract as decode — decline (returns null), never abort. */
    @Test
    fun developOfPngSurvives() {
        RawlerFotlabBridge.developRawToPng(pngBytes, developParams)
    }

    /**
     * Direct, un-swallowed probe of the generated native `developToPng` (NOT the `runCatching`-wrapped
     * [RawlerFotlabBridge.developRawToPng]). For unrecognized bytes rawler returns an *error*, which UniFFI
     * surfaces as a catchable JVM exception — not a process abort. We assert that the failure mode is
     * exactly that JVM exception (`DNGLAB-RAWLER-000002`): if the native side instead aborted
     * (SIGSEGV/SIGABRT inside `librawler_fotlab.so`), the instrumentation would die and this test would
     * fail by construction, with the tombstone captured in smoke_emulator.yaml's logcat. This guards the
     * new develop path's panic-hardening end to end.
     */
    @Test
    fun developDirectCallSurfacesJvmException() {
        var threw: Throwable? = null
        try {
            developToPng(notAnImage, developParams)
        } catch (t: Throwable) {
            threw = t
        }
        assertNotNull(
            "rawler develop on unrecognized bytes must surface a JVM exception (not abort the process): " +
                "${threw?.javaClass}",
            threw,
        )
    }

    /**
     * Direct, un-swallowed probe of the generated native `identify` (NOT the `runCatching`-wrapped
     * [RawlerFotlabBridge]). Every other case here funnels calls through the facade, which reduces any
     * *JVM-level* failure (a JNA `UnsatisfiedLinkError`, an `ExceptionInInitializerError` from a broken
     * UniFFI binding, a `RuntimeException` in the generated lower/raise glue) to `null` — making a dead
     * JVM binding indistinguishable from "rawler declined the input".
     *
     * A native abort (SIGSEGV/SIGABRT inside `librawler_fotlab.so`) still kills the process and fails
     * the instrumentation — that is the case the other tests already guard, and the tombstone/logcat
     * capture in `smoke_emulator.yaml` now records its backtrace. This case closes the *other* half:
     * if the call fails in the JVM (not natively), we fail loudly with the exception type + message so
     * the failure mode is unambiguous (`DNGLAB-RAWLER-000002`).
     */
    @Test
    fun identifyCallSurfacesJvmExceptions() {
        try {
            identify(notAnImage)
        } catch (t: Throwable) {
            fail("rawler identify threw a JVM exception (not a native abort): ${t.javaClass} — ${t.message}")
        }
    }
}
