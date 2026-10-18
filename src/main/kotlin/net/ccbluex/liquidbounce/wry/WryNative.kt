package net.ccbluex.liquidbounce.wry

import org.lwjgl.system.Platform
import org.lwjgl.system.linux.DynamicLinkLoader
import java.io.File
import java.security.MessageDigest

/**
 * The native library, which runs Wry's web view on its own threads.
 *
 * Every call comes from the render thread.
 */
@Suppress("TooManyFunctions")
internal object WryNative {

    /**
     * The platform in the naming of the libraries in the jar, or null if there is none for this system.
     */
    internal val isAndroid = System.getenv("ANDROID_DATA") != null || File("/system/build.prop").isFile

    private val platform: String? = run {
        val arch = when (System.getProperty("os.arch").lowercase()) {
            "amd64", "x86_64" -> "x64"
            "aarch64", "arm64" -> "arm64"
            else -> return@run null
        }
        if (isAndroid) {
            return@run "android-$arch"
        }
        when (Platform.get()) {
            Platform.WINDOWS -> "windows-$arch"
            Platform.MACOSX -> "macos-$arch"
            Platform.LINUX -> "linux-$arch"
            else -> null
        }
    }

    private val libraryName = System.mapLibraryName("liquidbounce_wry")

    private val resource = platform?.let { "/natives/$it/$libraryName" }

    /**
     * Whether the add-on has a library for this system at all.
     */
    val isSupported: Boolean
        get() = resource != null && WryNative::class.java.getResource(resource) != null

    /**
     * What the player has to install first, or null if nothing.
     */
    val missingDependency: String? by lazy {
        if (!isAndroid && Platform.get() == Platform.LINUX && !isLoadable("libwebkit2gtk-4.1.so.0")) {
            "WebKitGTK, install it with: ${webKitGtkCommand()}"
        } else {
            null
        }
    }

    /**
     * The command that installs WebKitGTK on the player's distribution.
     */
    private fun webKitGtkCommand(): String {
        val release = runCatching { File("/etc/os-release").readLines() }.getOrDefault(emptyList())
            .associate { it.substringBefore('=') to it.substringAfter('=').trim('"') }
        val ids = (listOf(release["ID"]) + release["ID_LIKE"].orEmpty().split(' ')).filterNotNull()
        return when {
            ids.any { it in setOf("arch", "endeavouros", "manjaro") } -> "sudo pacman -S webkit2gtk-4.1"
            ids.any { it in setOf("debian", "ubuntu", "linuxmint") } -> "sudo apt install libwebkit2gtk-4.1-0"
            ids.any { it in setOf("fedora", "rhel") } -> "sudo dnf install webkit2gtk4.1"
            ids.any { it.startsWith("opensuse") || it == "suse" } -> "sudo zypper install libwebkit2gtk-4_1-0"
            else -> "your package manager (the package is often called webkit2gtk-4.1)"
        }
    }

    private var isLoaded = false

    private fun isLoadable(library: String): Boolean {
        val handle = DynamicLinkLoader.dlopen(library, DynamicLinkLoader.RTLD_LAZY or DynamicLinkLoader.RTLD_LOCAL)
        if (handle == 0L) {
            return false
        }
        DynamicLinkLoader.dlclose(handle)
        return true
    }

    /**
     * Extracts the library into [folder], under a name of its own content so an update never meets a loaded copy.
     */
    fun load(folder: File) {
        if (isLoaded) {
            return
        }
        check(isSupported) { "Wry has no library for ${System.getProperty("os.name")} on ${System.getProperty("os.arch")}" }

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
        if (!isAndroid) {
            return folder
        }
        val writable = System.getenv("TMPDIR")?.takeIf(String::isNotBlank)
            ?: System.getProperty("java.io.tmpdir")?.takeIf(String::isNotBlank)
        return writable?.let { File(it, "wry-natives") } ?: folder
    }

    const val EVENT_LOG = 0
    const val EVENT_LOADING = 1
    const val EVENT_LOADED = 2
    const val EVENT_FAILED = 3
    const val EVENT_URL = 4
    const val EVENT_CONSOLE = 5
    const val EVENT_CURSOR = 6
    const val EVENT_BUFFER_GONE = 7

    const val FRAME_NONE = 0
    const val FRAME_PIXELS = 1
    const val FRAME_DMA_BUF = 2
    const val FRAME_SHARED_TEXTURE = 3
    const val FRAME_IO_SURFACE = 4

    /**
     * @param formats pairs of DRM fourcc and modifier the game can import as dmabufs, empty for any
     */
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
    external fun setFps(id: Long, fps: Int)

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

    @JvmStatic
    external fun focus(id: Long)

    /**
     * A key as SDL reports it: its keycode, scancode and modifiers.
     */
    @JvmStatic
    external fun key(id: Long, pressed: Boolean, keycode: Int, scancode: Int, modifiers: Int)

    @JvmStatic
    external fun text(id: Long, text: String)

    @JvmStatic
    external fun pollEvents(): Array<WryEvent>?

    /**
     * Writes the newest frame of a browser into [out], see the native library for the layout.
     *
     * @return one of the `FRAME_` kinds
     */
    @JvmStatic
    external fun takeFrame(id: Long, out: LongArray): Int

    /**
     * macOS: binds an IOSurface to the rectangle texture bound on the game's OpenGL context.
     *
     * @return the CGL error, 0 for none
     */
    @JvmStatic
    external fun bindIoSurface(surface: Long, width: Int, height: Int): Int

}

/**
 * Something that happened in the native library, see `EventKind` there.
 */
class WryEvent(
    val browser: Long,
    val kind: Int,
    val code: Int,
    val value: Long,
    val text: String,
    val detail: String
)
