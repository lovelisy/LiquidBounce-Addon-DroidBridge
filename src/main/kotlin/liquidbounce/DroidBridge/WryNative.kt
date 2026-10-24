package liquidbounce.DroidBridge

import java.io.File
import java.security.MessageDigest

/**
 * The native library, which drives the system web view through JNI.
 *
 * Every call comes from the render thread.
 */
@Suppress("TooManyFunctions")
internal object WryNative {

    internal val isAndroid = System.getenv("ANDROID_DATA") != null || File("/system/build.prop").isFile

    private val arch: String? = when (System.getProperty("os.arch").lowercase()) {
        "amd64", "x86_64" -> "x64"
        "aarch64", "arm64" -> "arm64"
        else -> null
    }

    private val platform: String? = arch?.let { "android-$it" }

    private val libraryName = System.mapLibraryName("liquidbounce_droidbridge")

    private val resource = platform?.let { "/natives/$it/$libraryName" }

    /**
     * Whether the add-on has a library for this system at all.
     */
    val isSupported: Boolean
        get() = resource != null && WryNative::class.java.getResource(resource) != null

    private var isLoaded = false

    /**
     * Extracts the library into [folder], under a name of its own content so an update never meets a loaded copy.
     */
    fun load(folder: File) {
        if (isLoaded) {
            return
        }
        check(isSupported) {
            "DroidBridge has no library for ${System.getProperty("os.arch")}"
        }

        val bytes = WryNative::class.java.getResourceAsStream(resource!!)!!.use { it.readBytes() }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val file = loadableFolder(folder).resolve(hash.take(16)).resolve(libraryName)
        if (!file.isFile || file.length() != bytes.size.toLong()) {
            file.parentFile.mkdirs()
            val temporary = File(file.parentFile, "$libraryName.tmp")
            temporary.writeBytes(bytes)
            temporary.renameTo(file)
        }

        System.load(file.absolutePath)
        isLoaded = true
    }

    private fun loadableFolder(folder: File): File {
        val writable = System.getenv("TMPDIR")?.takeIf(String::isNotBlank)
            ?: System.getProperty("java.io.tmpdir")?.takeIf(String::isNotBlank)
        return writable?.let { File(it, "droidbridge-natives") } ?: folder
    }

    const val EVENT_LOG = 0
    const val EVENT_LOADING = 1
    const val EVENT_LOADED = 2
    const val EVENT_FAILED = 3
    const val EVENT_URL = 4
    const val EVENT_CONSOLE = 5
    const val EVENT_CURSOR = 6

    @JvmStatic
    external fun start(dataDir: String, gpuFrames: Boolean, renderNode: String?, formats: LongArray)

    @JvmStatic
    external fun stop()

    @JvmStatic
    external fun update()

    @JvmStatic
    @Suppress("LongParameterList")
    external fun createBrowser(url: String, width: Int, height: Int, zoom: Double, incognito: Boolean, fps: Int): Long

    @JvmStatic
    external fun closeBrowser(id: Long)

    @JvmStatic
    external fun setVisible(id: Long, visible: Boolean)

    @JvmStatic
    external fun navigate(id: Long, url: String)

    @JvmStatic
    external fun reload(id: Long, ignoreCache: Boolean)

    @JvmStatic
    external fun goBack(id: Long)

    @JvmStatic
    external fun goForward(id: Long)

    @JvmStatic
    external fun resize(id: Long, width: Int, height: Int, zoom: Double)

    @JvmStatic
    external fun mouseMove(id: Long, x: Double, y: Double)

    /**
     * @param button 0 left, 1 middle, 2 right
     */
    @JvmStatic
    external fun mouseButton(id: Long, x: Double, y: Double, button: Int, pressed: Boolean)

    /**
     * @param steps positive scrolls up
     */
    @JvmStatic
    external fun mouseScroll(id: Long, x: Double, y: Double, steps: Double)

    /**
     * A key as Minecraft reports it: its keycode, scancode and modifiers.
     */
    @JvmStatic
    external fun key(id: Long, pressed: Boolean, keycode: Int, scancode: Int, modifiers: Int)

    @JvmStatic
    external fun text(id: Long, text: String)

    @JvmStatic
    external fun enter(id: Long)

    @JvmStatic
    external fun pollEvents(): Array<WryEvent>?

}

/**
 * Something that happened in the native library, see `EventKind` there.
 */
class WryEvent(
    val browser: Long,
    val kind: Int,
    val code: Int,
    val text: String,
    val detail: String
)
