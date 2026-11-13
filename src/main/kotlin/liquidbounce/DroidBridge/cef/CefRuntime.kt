package liquidbounce.DroidBridge.cef

import com.cinemamod.mcef.nativebridge.McefNative
import net.ccbluex.liquidbounce.config.ConfigSystem
import org.apache.logging.log4j.LogManager
import java.io.File
import java.security.MessageDigest

internal object CefRuntime {

    private val logger = LogManager.getLogger("LiquidBounce/DroidBridge/CEF")
    private val folder = ConfigSystem.rootFolder.resolve("droidbridge").resolve("cef")
    private val resource = "/natives/android-arm64/libmcef.so"
    private val endpoint = System.getProperty("droidbridge.cef.endpoint", "127.0.0.1:39090")

    var isInitialized = false
        private set

    var error: String? = null
        private set

    fun start() {
        if (isInitialized) {
            return
        }

        val library = extract()
        try {
            McefNative.load(library.absolutePath)
        } catch (error: UnsatisfiedLinkError) {
            throw IllegalStateException(
                "无法加载 libmcef.so（${library.absolutePath}）。" +
                    "它依赖 libc++_shared.so，启动器需要已经加载过该库。" +
                    "原因：${error.message}",
                error
            )
        }

        folder.mkdirs()
        val code = McefNative.init(
            folder.resolve("runtime").absolutePath,
            folder.resolve("cache").absolutePath,
            null,
            true,
            endpoint
        )
        check(code == McefNative.OK) {
            "CEF 桥初始化失败（$code）：${McefNative.getLastError() ?: "未知错误"}。" +
                "请安装 Mcef 引擎插件，并在插件里点“启动承载进程”（$endpoint）。"
        }

        isInitialized = true
        error = null
        logger.info("CEF bridge is ready through {}", endpoint)
    }

    fun stop() {
        if (!isInitialized) {
            return
        }
        runCatching { McefNative.shutdown() }
        isInitialized = false
    }

    private fun extract(): File {
        val bytes = CefRuntime::class.java.getResourceAsStream(resource)?.use { it.readBytes() }
            ?: error("DroidBridge 里没有 $resource")
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val file = loadableFolder().resolve(hash.take(16)).resolve("libmcef.so")
        if (!file.isFile || file.length() != bytes.size.toLong()) {
            file.parentFile.mkdirs()
            val temporary = File(file.parentFile, "libmcef.so.tmp")
            temporary.writeBytes(bytes)
            temporary.renameTo(file)
        }
        return file
    }

    private fun loadableFolder(): File {
        val writable = System.getenv("TMPDIR")?.takeIf(String::isNotBlank)
            ?: System.getProperty("java.io.tmpdir")?.takeIf(String::isNotBlank)
        return writable?.let { File(it, "droidbridge-cef") } ?: folder.resolve("lib")
    }

}
