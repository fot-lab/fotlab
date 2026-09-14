package io.github.fotlab.fotlab.feature.studio

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import io.github.fotlab.fotlab.media.FormatSniffer
import io.github.fotlab.fotlab.media.RawDecoder
import io.github.fotlab.fotlab.media.Route
import io.github.fotlab.fotlab.media.SniffResult
import io.github.fotlab.fotlab.media.StubRawDecoder
import io.github.fotlab.fotlab.media.route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.ByteBuffer

/**
 * Lower layer of the Studio feature (`FOTLAB-STRUCT-000001`), analogous to `LibraryCore`.
 *
 * Studio is a Snapseed-style editor. The node it shows on its canvas is produced by the Studio top
 * bar's open action, which adds the picked file to the Library's current directory
 * (`LibraryCore.importUris`) and then surfaces it here through its virtual `uri_storage` path — the
 * physical file is never touched (`FOTLAB-IMGMGR-000001` R1/R3). Studio reads that path, runs it
 * through the first-party format-sniffing wrapper ([FormatSniffer], R8) and routes the result.
 *
 * Render pipeline (R8): every opened node goes through [FormatSniffer] -> [route]:
 *  1. rawler recognizes AND can decode -> decode to PNG via [rawDecoder], then render that raster;
 *  2. otherwise Coil can decode    -> render the original source with Coil;
 *  3. neither                     -> [StudioRenderResult.Unsupported] (the UI shows the dialog).
 * The rawler native decode bridge ([RawDecoder]) is a TODO seam (FOTLAB-STUDIO-000001); until it is
 * wired, [StubRawDecoder] returns null and the source falls through to [StudioRenderResult.Unsupported].
 */
object StudioEngine {

    /** Prepare process-wide state; call once from the application context. */
    fun prepare(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * The virtual `uri_storage` of the node currently shown on the Studio canvas, or `null` when
     * nothing has been opened yet. Process-scoped; never persisted.
     */
    private val currentNodeUriState = MutableStateFlow<String?>(null)
    val currentNodeUri: StateFlow<String?> = currentNodeUriState.asStateFlow()

    /** Live result of running the render pipeline over the current node. */
    private val renderResultState = MutableStateFlow<StudioRenderResult>(StudioRenderResult.Idle)
    val renderResult: StateFlow<StudioRenderResult> = renderResultState.asStateFlow()

    /** The RAW->PNG decoder. Swap for the native (rawler/UniFFI) bridge when it is wired. */
    var rawDecoder: RawDecoder = StubRawDecoder

    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Point the canvas at [uri] (the virtual node's `uri_storage`) and run the render pipeline. */
    fun setCurrentNode(uri: String?) {
        currentNodeUriState.value = uri
        if (uri == null) {
            renderResultState.value = StudioRenderResult.Idle
            return
        }
        val parsed = runCatching { Uri.parse(uri) }.getOrNull()
        if (parsed == null) {
            renderResultState.value = StudioRenderResult.Unsupported
            return
        }
        renderResultState.value = StudioRenderResult.Loading
        scope.launch { renderResultState.value = runPipeline(appContext.contentResolver, parsed) }
    }

    private suspend fun runPipeline(resolver: ContentResolver, uri: Uri): StudioRenderResult {
        val header = runCatching { resolver.openInputStream(uri)?.use { it.readNBytes(HEADER_BYTES) } }
            .getOrNull()
        if (header == null) return StudioRenderResult.Unsupported

        // 1) Sniff: every input passes through the first-party wrapper (R8). Timeout is a hard error.
        val verdicts = when (val sniff = FormatSniffer.sniff(header)) {
            is SniffResult.Ok -> sniff.verdicts
            is SniffResult.Timeout -> return StudioRenderResult.Unsupported
        }

        // 2) Route, then execute (R8 / Q6, resolved).
        return when (val r = route(verdicts)) {
            is Route.RawToRaster -> {
                // rawler path — TWO separate calls:
                //   call #1 (identify) already happened above in FormatSniffer.sniff via RawlerProbe,
                //   which produced `r.format` + `canDecode`. This is call #2 (decode): hand that format
                //   to the native bridge so it decodes the already-identified RAW, then render the PNG.
                val png = runCatching {
                    rawDecoder.decodeToPng(r.format) { resolver.openInputStream(uri) ?: error("cannot open source") }
                }.getOrNull()
                if (png != null) StudioRenderResult.Ready(ByteBuffer.wrap(png)) else StudioRenderResult.Unsupported
            }
            is Route.ToCoil -> StudioRenderResult.Ready(uri)
            is Route.Unsupported -> StudioRenderResult.Unsupported
        }
    }

    private companion object {
        const val HEADER_BYTES = 64 * 1024
    }
}

/** Result of running the studio render pipeline over the current node (R8). */
sealed interface StudioRenderResult {
    /** Nothing opened yet. */
    data object Idle : StudioRenderResult
    /** Sniffing / decoding in progress. */
    data object Loading : StudioRenderResult
    /** A model Coil can render: the original [Uri] (Coil path) or decoded PNG [ByteBuffer] (rawler path). */
    data class Ready(val model: Any) : StudioRenderResult
    /** Neither sniffer can decode the source. */
    data object Unsupported : StudioRenderResult
}
