/*
 * MCEF Engine Plugin —— Minecraft 内置浏览器的引擎侧
 * Copyright (C) 2026 Rain_Clay and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

/*
 * MCEF Android/Cefrium host bridge.
 *
 * 作用：运行在 Zalith Android 进程内，提供 Android Context / Looper / Manifest
 * 环境，承接 libmcef.so 从 Minecraft 侧发来的 IPC 命令，并驱动 CefriumBrowser。
 */
package liquidbounce.droidbridge.host.cef

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import androidx.core.app.NotificationCompat
import com.cefrium.Cefrium
import com.cefrium.CefriumBrowser
import liquidbounce.droidbridge.host.cef.HostSettings
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.OutputStream
import java.io.File
import java.io.StringWriter
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 第一版 Cefrium Host：
 *
 * - 监听 127.0.0.1:39090；
 * - 接收 libmcef.so 的 JSON 行协议；
 * - 创建 OSR CefriumBrowser；
 * - 执行 loadUrl/eval/elementsAtPoint/domSnapshot；
 * - 通过 cefriumQuery 把 DOM JSON 回给 libmcef.so；
 * - OnPaint 帧暂时只记录日志，后续升级为二进制 frame IPC 或共享内存。
 */
class CefriumHostService : Service() {
    private val running = AtomicBoolean(false)
    private val cefReady = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val browsers = ConcurrentHashMap<Long, BrowserEntry>()
    private val fileLogFile by lazy {
        File(getExternalFilesDir(null), "mcef-cefrium.log")
    }

    /**
     * 运行状态快照，供插件界面（HUD / 设置页）轮询。
     *
     * 数据都在本进程内、字段少，直接读 volatile 就够；不需要 Binder/AIDL。
     * 界面拿不到 Service 实例时用 [readSnapshot] 从静态原子量读 ——
     * 见 companion 里的 currentSnapshot。
     */
    data class Snapshot(
        val running: Boolean,
        val cefReady: Boolean,
        val browsers: Int,
        val paints: Long,
        val framesSent: Long,
        val clients: Int,
    )

    private fun snapshot(): Snapshot = Snapshot(
        running = running.get(),
        cefReady = cefReady.get(),
        browsers = browsers.size,
        paints = paintCount,
        framesSent = frameSentCount,
        clients = if (clientWriter != null) 1 else 0,
    )

    // 日志异步化：fileLog() 会被主线程、CEF 回调线程、IPC 线程、socket writer 高频调用，
    // 同步 appendText（每次 open/append/close 走外部存储）可能自己把调用线程卡住，
    // 从而干扰正在观测的卡顿。这里只入队，由单写线程落盘。
    private val logQueue = LinkedBlockingQueue<String>(8192)
    private val logDropped = java.util.concurrent.atomic.AtomicLong(0L)

    @Volatile
    private var loggerRunning = false

    private fun fileLog(message: String, throwable: Throwable? = null) {
        val details = throwable?.let {
            val sw = StringWriter()
            it.printStackTrace(PrintWriter(sw))
            "\n" + sw.toString()
        } ?: ""
        // logcat 保留：文件读不到时（scoped storage）仍能看到关键事件。
        if (throwable == null) Log.i(TAG, message) else Log.e(TAG, message, throwable)
        if (!logQueue.offer("${System.currentTimeMillis()} $message$details")) {
            logDropped.incrementAndGet() // 队列满则丢弃，绝不在调用线程上阻塞
        }
    }

    private fun startFileLogger() {
        loggerRunning = true
        Thread({
            var writer: BufferedWriter? = null
            // 公共目录镜像的第二写入器。与主写入器同生共死：打不开就放弃镜像，
            // 绝不允许它影响主日志。
            var publicWriter: BufferedWriter? = null
            var publicOpenFailed = false
            var written = 0L
            var unflushed = 0L
            var openFailed = false
            try {
                while (loggerRunning || logQueue.isNotEmpty()) {
                    val line = try {
                        logQueue.poll(200L, java.util.concurrent.TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                        break
                    }
                    if (line == null) {
                        runCatching { writer?.flush() }
                        runCatching { publicWriter?.flush() }
                        unflushed = 0L
                        continue
                    }
                    if (writer == null && !openFailed) {
                        runCatching {
                            fileLogFile.parentFile?.mkdirs()
                            writer = BufferedWriter(
                                OutputStreamWriter(
                                    java.io.FileOutputStream(fileLogFile, true),
                                    Charsets.UTF_8
                                )
                            )
                        }.onFailure { openFailed = true }
                    }
                    if (publicWriter == null && !publicOpenFailed) {
                        val pf = publicLogFile
                        if (pf == null) {
                            publicOpenFailed = true
                        } else {
                            runCatching {
                                // 封顶：超过 8MB 就重开一个，避免多轮运行把它撑爆。
                                if (pf.length() > 8L * 1024L * 1024L) pf.delete()
                                publicWriter = BufferedWriter(
                                    OutputStreamWriter(
                                        java.io.FileOutputStream(pf, true),
                                        Charsets.UTF_8
                                    )
                                )
                            }.onFailure { publicOpenFailed = true }
                        }
                    }
                    val dropped = logDropped.getAndSet(0L)
                    runCatching {
                        val w = writer
                        if (w != null) {
                            if (dropped > 0L) {
                                w.write("${System.currentTimeMillis()} logDropped=$dropped\n")
                            }
                            w.write(line)
                            w.newLine()
                        }
                    }
                    // 公共目录镜像：同一行再写一份，供无特权通道直接读取。
                    // 单独 runCatching：镜像出任何问题都不能影响主日志。
                    runCatching {
                        val pw = publicWriter ?: return@runCatching
                        if (dropped > 0L) {
                            pw.write("${System.currentTimeMillis()} logDropped=$dropped\n")
                        }
                        pw.write(line)
                        pw.newLine()
                    }
                    written += line.length + 1
                    unflushed += line.length + 1
                    // 追平时立刻 flush：native 崩溃会把缓冲区里没落盘的行一起带走，
                    // 丢掉的恰好是崩前最后几行——最需要的部分。
                    // 只有积压（突发日志）时才批量写，避免每行一次系统调用。
                    if (unflushed >= 32L * 1024L || logQueue.isEmpty()) {
                        runCatching { writer?.flush() }
                        runCatching { publicWriter?.flush() }
                        unflushed = 0L
                    }
                    if (written > 8L * 1024L * 1024L) {
                        runCatching { writer?.flush() }
                        runCatching { publicWriter?.flush() }
                        runCatching { writer?.close() }
                        writer = null
                        runCatching {
                            val rotated = File(fileLogFile.parentFile, "mcef-cefrium.log.1")
                            rotated.delete()
                            fileLogFile.renameTo(rotated)
                        }
                        written = 0L
                        unflushed = 0L
                    }
                }
            } catch (t: Throwable) {
                // 日志线程若静默死掉，会把"日志停止"伪装成"进程死亡"，直接毁掉现场判断。
                // 这里必须让它在 logcat 里可见。
                Log.e(TAG, "MCEF-FileLogger 异常退出，后续文件日志会丢失", t)
            } finally {
                runCatching { writer?.flush() }
                runCatching { writer?.close() }
                runCatching { publicWriter?.flush() }
                runCatching { publicWriter?.close() }
            }
        }, "MCEF-FileLogger").apply { isDaemon = true }.start()
    }

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var clientSocket: Socket? = null

    @Volatile
    private var clientWriter: BufferedWriter? = null

    // 帧二进制通道：与 clientWriter 同一条 socket 的输出流。
    @Volatile
    private var clientOutput: OutputStream? = null

    @Volatile
    private var firstFrameSentLogged = false

    private var nonMoveInputLogCount = 0
    private var moveInputLogCount = 0
    private var unsupportedInputLogCount = 0

    // 心跳：每 2 秒一条，带 OnPaint/发帧计数。
    // 用来区分"进程死了(日志戛然而止)"和"进程活着但 CEF 不再出帧(心跳继续、计数不涨)"。
    @Volatile
    private var paintCount = 0L

    // 上一次 paint 增长的时刻。create 新浏览器时会被重置，
    // 否则计时会从 service 启动开始算，导致首帧之前的误报。
    @Volatile
    private var lastPaintProgressAt = System.currentTimeMillis()

    @Volatile
    private var frameSentCount = 0L

    @Volatile
    private var heartbeatRunning = false

    @Volatile
    private var lastMainTickMs = System.currentTimeMillis()

    // async tick 计数：async 消息可以穿过 MessageQueue 的 sync barrier。
    // 冻结期间它仍在涨 → 主线程本身没被占住，问题在队列/渲染管线；
    // 它一起停 → 主线程真的不可用（Java 栈只剩 looper 空转帧）。
    @Volatile
    private var asyncTickCount = 0L

    // Choreographer 帧回调计数：判断 UI 帧管线（vsync → traversal → draw）是否停了。
    @Volatile
    private var frameCallbackCount = 0L

    private var lastThreadDumpAt = 0L

    // CEF 调用在主线程上的最后进入点。冻结时在 watchdog 行里给出，不对刷输入日志。
    @Volatile
    private var lastMainNativeCall = "none"

    @Volatile
    private var lastMainNativeCallAt = 0L

    private inline fun <T> mainNative(what: String, block: () -> T): T {
        lastMainNativeCall = what
        lastMainNativeCallAt = System.currentTimeMillis()
        try {
            return block()
        } finally {
            lastMainNativeCall = "$what(done)"
        }
    }

    // 诊断专用：async Handler（API 28+），普通消息会被 sync barrier 挡住。
    private val mainAsyncHandler: Handler =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Handler.createAsync(Looper.getMainLooper())
        } else {
            Handler(Looper.getMainLooper())
        }

    private fun startMainLooperWatchdog() {
        val tick = object : Runnable {
            override fun run() {
                lastMainTickMs = System.currentTimeMillis()
                asyncTickCount++
                mainAsyncHandler.postDelayed(this, 1000L)
            }
        }
        mainAsyncHandler.post(tick)
    }

    private fun startChoreographerProbe() {
        val cb = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                frameCallbackCount++
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
        mainHandler.post {
            runCatching { Choreographer.getInstance().postFrameCallback(cb) }
                .onFailure { fileLog("Choreographer probe failed", it) }
        }
    }

    private fun dumpAllThreads(reason: String) {
        runCatching {
            val all = Thread.getAllStackTraces()
            val sb = StringBuilder()
            sb.append("\n--- threads total=").append(all.size)
            for ((t, st) in all) {
                sb.append("\n== ").append(t.name)
                    .append(" state=").append(t.state)
                    .append(" daemon=").append(t.isDaemon)
                    .append(" frames=").append(st.size)
                st.take(20).forEach { sb.append("\n\tat ").append(it.toString()) }
            }
            fileLog("$reason THREAD DUMP$sb")
        }.onFailure { fileLog("dumpAllThreads failed", it) }
    }

    /**
     * Chromium 有多个入口会走 ServiceLoaderUtil.loadClass → ServiceLoader →
     * ClassLoader.getResources → DexPathList$Element.maybeInit → JarFile/ZipFile.initCEN，
     * 即把 classpath 上每个 jar/apk 的中央目录解析一遍。首次命中实测要数秒，
     * 而且它发生在主线程（含 native 反向回调进 Java 的情形）。
     *
     * 这里先在后台线程把同一条懒加载路径走一遍，让 DexPathList$Element 的
     * ClassPathURLStreamHandler/JarFile 缓存建立起来，把这几秒开销挪出主线程。
     */
    private fun prewarmClasspathResources() {
        val t0 = System.currentTimeMillis()
        runCatching {
            val cl = Thread.currentThread().contextClassLoader
                ?: javaClass.classLoader
                ?: ClassLoader.getSystemClassLoader()
            val names = arrayOf(
                "META-INF/services/org.chromium.base.AconfigFlaggedApiDelegate",
                "META-INF/services/org.chromium.base.CommandLine",
                "META-INF/services/org.chromium.base.FeatureMap",
                "META-INF/services/org.chromium.base.library_loader.LibraryLoader",
                "META-INF/services/org.chromium.components.webauthn.cred_man.CredManSupportProvider"
            )
            var hits = 0
            for (n in names) {
                val e = cl.getResources(n)
                while (e.hasMoreElements()) {
                    e.nextElement()
                    hits++
                }
            }
            fileLog(
                "classpath prewarm done hits=$hits cost=${System.currentTimeMillis() - t0}ms " +
                    "loader=${cl.javaClass.name}"
            )
        }.onFailure {
            fileLog("classpath prewarm failed cost=${System.currentTimeMillis() - t0}ms", it)
        }
    }

    private fun startHeartbeat() {
        heartbeatRunning = true
        Thread({
            var lastPaints = -1L
            var lastFrozenLogAt = 0L
            try {
                while (heartbeatRunning) {
                    try {
                        Thread.sleep(2000L)
                    } catch (_: InterruptedException) {
                        break
                    }
                    publishSnapshot(this@CefriumHostService)
                    val paints = paintCount
                    val now = System.currentTimeMillis()
                    if (paints != lastPaints) {
                        lastPaints = paints
                        lastPaintProgressAt = now
                    }
                    // 最后进入的 CEF 调用也带在心跳里：进程若在两次心跳之间直接死掉
                    // （native crash / 被系统杀掉），上一行心跳就是唯一的现场记录。
                    fileLog(
                        "hb pid=${android.os.Process.myPid()} paints=$paints frames=$frameSentCount " +
                            "frameCb=$frameCallbackCount asyncTick=$asyncTickCount " +
                            "lastNative=${lastMainNativeCall}"
                    )
                    val stalledMs = now - lastPaintProgressAt
                    // 只有"已经出过帧、随后停住"才算冻结。
                    // 单看 browsers.isNotEmpty() 会在 create 之后、首帧之前误报
                    // （实测出现过 PAINT FROZEN 105144ms 而 paints 一直是 0）。
                    if (stalledMs >= 6000L && now - lastFrozenLogAt >= 10000L &&
                        paintCount > 0L && browsers.isNotEmpty()
                    ) {
                        lastFrozenLogAt = now
                        fileLog(
                            "PAINT FROZEN ${stalledMs}ms paints=$paints frames=$frameSentCount " +
                                "frameCb=$frameCallbackCount asyncTick=$asyncTickCount " +
                                "client=${clientWriter != null}"
                        )
                        // 冻结时主动要一帧。这是判定"OSR 死了"还是"页面没变化"的唯一手段：
                        // 前面几轮一直没调 invalidate，于是永远只有冻结记录、没有结论。
                        // 单次、有上限；失败也只会多一行日志，不会改变现有行为。
                        runCatching {
                            val b = browsers.values.firstOrNull()
                            if (b != null) {
                                b.browser.invalidate()
                                fileLog("PAINT FROZEN -> invalidate() sent for id=${b.id}")
                            } else {
                                fileLog("PAINT FROZEN -> no browser to invalidate")
                            }
                        }.onFailure { fileLog("PAINT FROZEN -> invalidate failed", it) }
                    }
                    val mainBlockedMs = now - lastMainTickMs
                    if (mainBlockedMs >= 3000L) {
                        val mt = Looper.getMainLooper().thread
                        val stack = mt.stackTrace.joinToString("\n") { "\tat $it" }
                        fileLog(
                            "MAIN LOOP STALLED ${mainBlockedMs}ms (paint stalled=${stalledMs}ms) " +
                                "mainState=${mt.state} asyncTick=$asyncTickCount frameCb=$frameCallbackCount " +
                                "lastNative=${lastMainNativeCall} age=${now - lastMainNativeCallAt}ms " +
                                "-- main thread stack:\n${stack}"
                        )
                        // 阈值必须低：进程被 ANR 杀掉之后就没有机会再 dump 了。
                        if (now - lastThreadDumpAt >= 15000L) {
                            lastThreadDumpAt = now
                            // Java 栈是跨 Android 版本可靠的最低公分母；/proc 诊断不作为必需条件。
                            dumpAllThreads("MAIN LOOP STALLED ${mainBlockedMs}ms")
                        }
                    }
                }
            } catch (th: Throwable) {
                fileLog("FATAL crash thread=Heartbeat", th)
            }
        }, "MCEF-Heartbeat").apply { isDaemon = true }.start()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        appContext = applicationContext
        startFileLogger()
        fileLog("service onCreate pid=${android.os.Process.myPid()}")
        logBuildIdentity()
        startForegroundSafe()
        startHeartbeat()
        startMainLooperWatchdog()
        startChoreographerProbe()
        startHost()
    }

    override fun onDestroy() {
        instance = null
        heartbeatRunning = false
        fileLog("service onDestroy")
        stopHost()
        // 停日志线程前留出排空时间，避免丢掉最后几行（正是卡死现场最需要的部分）。
        loggerRunning = false
        runCatching { logQueue.offer("${System.currentTimeMillis()} logger stopped") }
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
    }

    /**
     * 把构建身份写进日志。
     *
     * 调查中连续两次卡在"这份日志是哪个包跑出来的"上：versionName 恒为 2.4.12-debug，
     * 日志里没有任何字段能区分两次安装，于是无法判断某个修复到底部署没有。
     * lastUpdateTime 每次安装都会变，用它就能把一份日志对应到一次具体安装，
     * 也就回答了"这个包到底含不含某个提交"。
     */
    private fun logBuildIdentity() {
        runCatching {
            @Suppress("DEPRECATION")
            val pi = packageManager.getPackageInfo(packageName, 0)
            val fmt = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
            fileLog(
                "build identity: versionName=${pi.versionName} versionCode=${pi.versionCode} " +
                    "lastUpdateTime=${fmt.format(java.util.Date(pi.lastUpdateTime))} " +
                    "firstInstallTime=${fmt.format(java.util.Date(pi.firstInstallTime))}"
            )
        }.onFailure { fileLog("build identity unavailable", it) }
    }

    private fun startForegroundSafe() {
        runCatching {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID, "MCEF 引擎", NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "承载 Minecraft 内置浏览器的进程"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
            startForeground(NOTIF_ID, buildNotification())
            fileLog("startForeground ok")
        }.onFailure { fileLog("startForeground failed", it) }
    }

    /**
     * 保活通知：既是前台服务凭据，也是运行状态的可视化入口。
     *
     * 点它回到插件的设置页 —— 用户能在那儿看引擎状态、开关选项。
     * 状态文字随 IPC 连接数和浏览器数量变化，由 updateNotification() 刷新。
     */
    private fun buildNotification(): android.app.Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, Class.forName("liquidbounce.droidbridge.host.SettingsActivity")),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val active = browsers.size
        val status = when {
            !cefReady.get() -> "引擎初始化中…"
            active == 0 -> "待命 · 等待 Minecraft 连接"
            else -> "运行中 · $active 个页面"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("MCEF 引擎")
            .setContentText(status)
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    /** 状态变化时刷新保活通知。browsers 增减、CEF 初始化完成时调。 */
    private fun updateNotification() {
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID, buildNotification())
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startHost() {
        if (!running.compareAndSet(false, true)) return

        // Start IPC immediately. Chromium initialization may block for a while and
        // must not prevent the service from publishing its port or writing diagnostics.
        thread(name = "MCEF-CefriumHost", isDaemon = true) {
            Thread.currentThread().setUncaughtExceptionHandler { t, th ->
                fileLog("FATAL crash thread=MCEF-CefriumHost", th)
            }
            runServerLoop()
        }
        // 不再在 Service 里直接初始化 CEF——Service 没有 Window/ViewRootImpl，
        // CEF 的 Looper 回调会访问这些对象 → null deref → SIGSEGV。
        // 启动透明 Activity 提供 Window 上下文，Activity ready 后再初始化 CEF。
        runCatching {
            val intent = Intent(this, CefriumHostActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            fileLog("started CefriumHostActivity for Window context")
        }.onFailure { fileLog("failed to start CefriumHostActivity", it) }
        armReadyWatchdog()
    }

    /**
     * ready 看门狗。
     *
     * pid=455 那次：startActivity 正常返回、日志也写了 "started CefriumHostActivity"，
     * 但 ready 回调始终没来 → CEF 从未初始化 → 客户端 create 无限 deferred 直到进程结束。
     * 整条路径没有任何一处报错，是纯静默失败，事后完全无法判断卡在哪。
     * 这里把"等了 N 秒还没 ready"变成一条显式日志。
     */
    private fun armReadyWatchdog() {
        thread(name = "MCEF-ReadyWatchdog", isDaemon = true) {
            var waited = 0L
            while (waited < READY_WATCHDOG_MS) {
                try {
                    Thread.sleep(1000L)
                } catch (_: InterruptedException) {
                    return@thread
                }
                waited += 1000L
                if (hostActivity != null) return@thread // 已 ready，正常路径，不打扰
            }
            fileLog(
                "!! CefriumHostActivity 启动后 ${READY_WATCHDOG_MS / 1000}s 仍未 ready " +
                    "(hostActivity=null) → CEF 不会初始化，客户端 create 将持续 deferred。" +
                    " 若日志里没有 'CefriumHostActivity.onCreate entered'，说明 Activity 压根没被创建" +
                    "（后台启动 Activity 被系统拦截）；有的话则是 ready 回调被丢弃或 onCreate 中途抛异常。"
            )
        }
    }

    // ── Activity 回调 ──

    @Volatile
    private var hostActivity: android.app.Activity? = null

    private fun onHostActivityReadyInternal(activity: android.app.Activity) {
        hostActivity = activity
        fileLog("CefriumHostActivity ready, initializing CEF with Window context")
        // 强杀/崩溃后 CEF 私有数据目录会留下锁和损坏的单例状态。
        runCatching {
            val cefDir = File(filesDir, "cefrium")
            if (cefDir.exists()) {
                cefDir.deleteRecursively()
                fileLog("wiped cef data dir ${cefDir.absolutePath}")
            }
        }.onFailure { fileLog("wipe cef dir failed", it) }
        // 先在后台把 Chromium 会触发的 classpath 资源懒加载走一遍
        // （ServiceLoader → JarFile/initCEN，实测每次数秒），再回主线程做初始化。
        thread(name = "MCEF-ClasspathPrewarm", isDaemon = true) {
            prewarmClasspathResources()
            // CEF 浏览器进程初始化必须有 Looper（主线程）；放到无 Looper 的普通
            // 线程会卡死在 nativeInitialize。初始化耗时较长，所以才需要前台服务保活。
            mainHandler.post {
                fileLog("before Cefrium.initialize mainThread=${Looper.myLooper() === Looper.getMainLooper()} process=${android.os.Process.myPid()}")
                // 在 Cefrium.initialize 之前注入可配置标志，避免重编 AAR 就能 A/B 测试 GPU 路径。
                // 文件每行一个 switch（不带 --），# 开头是注释；Cefrium.initialize 自己还会追加
                // in-process-gpu + no-sandbox，删不掉，但 --disable-gpu 能覆盖它。
                runCatching {
                    if (!org.chromium.base.CommandLine.isInitialized()) {
                        org.chromium.base.CommandLine.init(null)
                    }
                    val cl = org.chromium.base.CommandLine.getInstance()
                    // 两个来源都读：应用私有目录（原路径，保持兼容）与公共目录镜像。
                    // 公共目录那份是为了让无特权通道也能改 flag —— 不必每次 A/B 都钻 Android/data。
                    val flagFiles = listOfNotNull(
                        getExternalFilesDir(null)?.let { File(it, "mcef_flags.txt") }?.takeIf { it.exists() },
                        publicFlagsFile()?.takeIf { it.exists() }
                    )
                    val switches = flagFiles.flatMap { f ->
                        f.readText().trim().split("\n")
                            .map { it.trim() }
                            .filter { it.isNotEmpty() && !it.startsWith("#") }
                    }.distinct()
                    switches.forEach { cl.appendSwitch(it) }
                    // 无条件记一行：包括"一个 flag 都没有"的情况，否则无法区分"没配"和"没读到"。
                    fileLog(
                        "injected ${switches.size} CEF flags from " +
                            (if (flagFiles.isEmpty()) "(no flag file)" else flagFiles.joinToString { it.name }) +
                            ": ${switches.joinToString(" ")}"
                    )
                }.onFailure { fileLog("flag injection failed", it) }
                runCatching {
                    val ok = Cefrium.initialize(applicationContext)
                    cefReady.set(ok && Cefrium.isInitialized())
                    fileLog("Cefrium.initialize returned=$ok isInitialized=${Cefrium.isInitialized()} ready=${cefReady.get()} process=${android.os.Process.myPid()}")
                }.onFailure { fileLog("Cefrium.initialize failed", it) }
            }
        }
    }

    private fun onHostActivityDestroyedInternal() {
        fileLog("CefriumHostActivity destroyed")
        hostActivity = null
    }

    private fun stopHost() {
        running.set(false)
        runCatching { serverSocket?.close() }
        runCatching { clientSocket?.close() }
        serverSocket = null
        clientSocket = null
        clientWriter = null
        clientOutput = null
        pendingFrame.getAndSet(null)?.let { returnFrameBuffer(it.payload) }
        framePumpScheduled.set(false)
        runCatching { writerExecutor.shutdownNow() }

        mainHandler.post {
            browsers.values.forEach { entry ->
                runCatching { mainNative("service close browser id=${entry.id}") { entry.browser.close() } }
            }
            browsers.clear()
        }
    }

    private fun runServerLoop() {
        try {
            ServerSocket(HOST_PORT, 1, InetAddress.getByName(HOST_ADDR)).use { server ->
                serverSocket = server
                fileLog("Host listening on $HOST_ADDR:$HOST_PORT file=${fileLogFile.absolutePath}")

                while (running.get()) {
                    try {
                        val socket = server.accept()
                        // 每个客户端在独立线程里处理：handleClient 会阻塞在 readLine()，
                        // 放在 accept 循环里同步跑会让 server 再也回不到 accept ——
                        // MC 侧掉线重连时新连接只能排在 backlog 里，永远没人接。
                        // 现象是 host 一直"活着"（paints 在涨）却再也发不出帧。
                        thread(name = "MCEF-Client-${socket.port}", isDaemon = true) {
                            try {
                                handleClient(socket)
                            } catch (t: Throwable) {
                                fileLog("client handler 异常: ${t.javaClass.simpleName}: ${t.message}")
                            }
                        }
                    } catch (t: Throwable) {
                        if (running.get()) {
                            // accept 本身出错（例如被 close）不该杀掉整个循环，
                            // 但如果是 socket 已关闭就退出。
                            if (serverSocket == null || server.isClosed) break
                            Log.e(TAG, "accept 失败", t)
                        } else break
                    }
                }
            }
        } catch (t: Throwable) {
            if (running.get()) Log.e(TAG, "Host server 异常", t)
        }
    }

    private fun handleClient(socket: Socket) {
        fileLog("client connected remote=${socket.remoteSocketAddress}")
        socket.use { s ->
            clientSocket = s
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            val writer = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))
            clientWriter = writer
            clientOutput = s.getOutputStream()

            while (running.get()) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                handleCommand(line, writer)
            }
        }
        if (clientSocket === socket) clientSocket = null
        // 必须无条件清空：写线程会一直读这两个引用，
        // 留着已关闭的 socket 会让每帧都走一次 SocketException。
        clientWriter = null
        clientOutput = null
        fileLog("client disconnected remote=${runCatching { socket.remoteSocketAddress }.getOrNull()}")
    }

    private fun handleCommand(line: String, writer: BufferedWriter) {
        val json = runCatching { JSONObject(line) }.getOrElse {
            Log.w(TAG, "非法 JSON: $line", it)
            return
        }
        val cmd = json.optString("cmd")
        if (cmd != "input") fileLog("command=$cmd id=${json.optLong("id", 0L)}")
        when (cmd) {
            "create" -> handleCreate(json, writer)
            "destroy" -> handleDestroy(json)
            "resize" -> handleResize(json)
            "loadUrl" -> handleLoadUrl(json)
            "eval" -> handleEval(json)
            "domSnapshot" -> {
                fileLog("DOM snapshot requested id=${json.optLong("id", 0L)}")
                handleDomSnapshot(json)
            }
            "elementsAtPoint" -> handleElementsAtPoint(json)
            "input" -> handleInput(json)
            // 导航类命令走 CefriumBrowser 真实 API，不再用 JS 模拟：
            // location.reload()/history.back() 会额外触发一次导航，和 OSR 重绘叠在一起；
            // browser.reload()/goBack()/goForward()/stopLoading() 才是 CEF 自己的导航路径。
            "reload" -> handleBrowserAction(json, "reload") { it.reload() }
            "goBack" -> handleBrowserAction(json, "goBack") { it.goBack() }
            "goForward" -> handleBrowserAction(json, "goForward") { it.goForward() }
            "stopLoading" -> handleBrowserAction(json, "stopLoading") { it.stopLoading() }
            "queryReply" -> handleQueryReply(json)
            else -> Log.w(TAG, "未知命令: $cmd line=$line")
        }
    }

    private fun handleCreate(json: JSONObject, writer: BufferedWriter) {
        val id = json.optLong("id", 0L)
        fileLog("create enter id=$id")
        if (id <= 0L) return
        val url = json.optString("url", "about:blank")
        val width = json.optInt("width", 800).coerceAtLeast(1)
        val height = json.optInt("height", 600).coerceAtLeast(1)
        val scale = json.optDouble("scale", 1.0).toFloat().coerceAtLeast(0.1f)
        // MC 侧（mcef_bridge）一直在 create 命令里带 transparent，之前没人消费。
        // 它最终落到 CefriumBrowser.setBackgroundColor：0 = 全透明，页面未绘制处 alpha 为 0。
        val transparent = json.optBoolean("transparent", false)

        mainHandler.post {
            if (!cefReady.get()) {
                fileLog("create deferred id=$id cefReady=${cefReady.get()}")
                mainHandler.postDelayed({ handleCreate(json, writer) }, 1000L)
                return@post
            }
            runCatching {
                mainNative("close previous browser id=$id") { browsers.remove(id)?.browser?.close() }

                fileLog("create constructing id=$id size=${width}x${height}")
                val browser = mainNative("create browser id=$id") {
                    CefriumBrowser(width, height, scale)
                }
                fileLog("create browser constructed id=$id")

                // 透明背景：必须在首次 loadUrl 之前设置，否则新版 SDK 会抛
                // IllegalStateException（它只在浏览器创建那一刻生效）。
                //
                // 背景：cefrium 0.8.9 之前把 background_color 硬编码成 ARGB(255,255,255,255)，
                // 于是每一帧 OSR 的 alpha 恒为 255 —— 页面即使 CSS 全透明也出不来镂空，
                // HUD/覆盖层只能是一整块不透明矩形（issue #13）。作者在 0.8.9 之后加了
                // CefriumBrowser.setBackgroundColor(int argb)：传 0x00000000 时透明区域的
                // alpha 会如实变成 0。
                //
                // transparent 由 MC 侧经 IPC 传进来（mcef_bridge 的 create 命令里一直带着
                // 这个字段，之前没人消费）。现在真正接上了。
                if (transparent) {
                    runCatching {
                        browser.setBackgroundColor(0)
                        fileLog("create transparent background set id=$id")
                    }.onFailure {
                        // 老版本 SDK 没有这个方法：让页面照常不透明渲染，不影响其它功能。
                        fileLog("create setBackgroundColor failed id=$id", it)
                    }
                }

                val entry = BrowserEntry(id, browser, width, height, navigationGeneration = 1L)
                browsers[id] = entry
                // 新浏览器重新起算"多久没出帧"，首帧之前的空白不算冻结。
                lastPaintProgressAt = System.currentTimeMillis()
                // 立刻登记"已按这个 URL 导航"，不要等到下面真正 loadUrl 时再登记：
                // 客户端的 loadUrl 往往在 create 处理到一半就到达（实测早 9ms），
                // 那时 lastDispatchedUrl 还是 null，去重就失效，结果同一次打开会
                // 连着导航两遍。提前登记后这条重复命令会被 handleLoadUrl 丢弃。
                entry.lastDispatchedUrl = url
                entry.lastLoadAtMs = System.currentTimeMillis()

                fileLog("create registering query handler id=$id")
                // QueryOriginPolicy 限制哪些页面来源允许调用 window.cefriumQuery 桥。
                // 之前用 loopback()（只放行 127.0.0.1），但 HUD 页面一直拿不到数据、
                // 整帧全透明，为排除策略误伤（比如 origin 解析差异导致 fetch/桥被拒），
                // 直接放开为 anyOrigin()。网页 mod 的目标场景本来就是要加载任意
                // 远端页面并允许它们调用桥，最终形态也是全开放，这里就一步到位。
                // 认证仍由 LB 自己的 lb_code 中间件负责，不依赖这个策略。
                // 页面 cefQuery 的入口。
                //
                // 之前这里把 request 当 DOM 快照 sendDom 出去、再 callback.success("ok")，
                // 页面拿到的永远是字符串 "ok" —— WebDisplays 解析失败，请求直接作废。
                //
                // 现在改成：存下 callback，把 request 推给 MC 侧处理器，
                // 等它算完用 queryReply 回来再答复（见 handleQueryReply）。
                browser.setQueryHandler(
                    CefriumBrowser.QueryOriginPolicy.anyOrigin()
                ) { queryId, request, origin, callback ->
                    fileLog("cefriumQuery id=$id queryId=$queryId origin=$origin bytes=${request.length}")
                    entry.pendingQueries[queryId] = callback
                    sendQuery(id, queryId, request, origin)
                    true
                }

                fileLog("create registering render handler id=$id")
                browser.setRenderHandler { w, h, pixels ->
                    entry.width = w
                    entry.height = h
                    entry.lastFrameBytes = pixels.remaining()
                    entry.paintCount++
                    paintCount++
                    if (!entry.firstPaintLogged) {
                        entry.firstPaintLogged = true
                        fileLog("first paint id=$id ${w}x${h} bytes=${pixels.remaining()}")
                    }
                    // 节流 ~33fps：OSR 有重绘时还会再回调，丢帧无副作用。
                    val now = System.nanoTime()
                    if (now - entry.lastPaintNs < 30_000_000L) return@setRenderHandler
                    entry.lastPaintNs = now
                    // 只按 stride*h 取字节：桥协议要求 header 的 size 与 stride*height
                    // 严格相等，多出来的 padding 不能算进 payload，否则 native 会拒收整帧。
                    val stride = w * 4
                    val expected = stride * h
                    val available = pixels.remaining()
                    if (w <= 0 || h <= 0 || available < expected) return@setRenderHandler
                    val dst = borrowFrameBuffer(expected)
                    pixels.get(dst, 0, expected)

                    // 采样 alpha 通道（BGRA 里每 4 字节的第 4 个），判断 CEF 有没有保留
                    // 页面的透明信息。若全是 0xFF 说明走的是不透明渲染（transparent
                    // painting 未开），客户端再怎么混合也没用；若出现 <0xFF 的值，
                    // 就说明帧里带 alpha，MC 侧可以按 alpha 合成。
                    // 只在首帧和之后每 60 帧采一次，避免拖慢渲染。
                    if (entry.paintCount == 1L || entry.paintCount % 60L == 0L) {
                        var minA = 255
                        var maxA = 0
                        var transparent = 0
                        var i = 3
                        val limit = minOf(expected, dst.size)
                        while (i < limit) {
                            val a = dst[i].toInt() and 0xFF
                            if (a < minA) minA = a
                            if (a > maxA) maxA = a
                            if (a < 255) transparent++
                            i += 4
                        }
                        fileLog(
                            "alpha probe id=$id paints=${entry.paintCount} " +
                                "min=$minA max=$maxA nonOpaquePx=$transparent total=${limit / 4}"
                        )
                    }

                    postFrame(id, w, h, stride, dst)
                }

                // 诊断监听：JS 是否真的执行、页面是否加载完成、renderer 是否崩了。
                // title 是不依赖 window.cefriumQuery 的回传通道，能区分"没执行"和"桥缺失"。
                fileLog("create registering listeners id=$id")
                browser.setOnTitleChangedListener { title ->
                    fileLog("title changed id=$id title=$title")
                }
                browser.setOnUrlChangedListener { u ->
                    fileLog("url changed id=$id url=$u")
                }
                browser.setOnLoadingStateChangedListener { loading, _, _ ->
                    fileLog("load state id=$id loading=$loading")
                    // 后备注入：document-start 若没生效（Cefrium 的 origins 语义
                    // 曾让脚本静默不执行），在"开始加载"这一刻再补一次。
                    // 这个时机页面 DOM 还没解析完，抢在站点自检脚本前仍有机会。
                    if (loading && HostSettings.desktopUserAgent) {
                        val e = browsers[id]
                        if (e != null && e.browser === browser && !e.fallbackInjected) {
                            e.fallbackInjected = true

                            // CDP 必须等 browser 真正存在之后才能调。
                            // 之前在 create 阶段调用，拿到的是
                            // {"message":"the browser does not exist yet or is closed"}。
                            // loading=true 是首个能保证 browser 活着的时机。
                            runCatching {
                                browser.executeDevToolsMethod(
                                    "Emulation.setUserAgentOverride",
                                    DESKTOP_UA_CDP_PARAMS
                                ) { ok, json ->
                                    fileLog("cdp setUserAgentOverride id=$id ok=$ok json=$json")
                                    // UA 覆盖只对"下一个"文档生效，本次导航已经带着
                                    // 手机 UA 发出去了，所以设完必须 reload 一次，
                                    // 让新身份进到首个请求里。
                                    if (ok) {
                                        mainHandler.post {
                                            val e2 = browsers[id]
                                            if (e2?.browser === browser) {
                                                fileLog("cdp reload to apply id=$id")
                                                runCatching { browser.reload() }
                                            }
                                        }
                                    }
                                }
                            }.onSuccess {
                                fileLog("cdp dispatched id=$id")
                            }.onFailure {
                                fileLog("cdp failed id=$id", it)
                            }

                            // ── 兜底注入：只在这条路径真的需要时才发 ──────────
                            //
                            // document-start 正常工作时，它已经在本导航的文档创建
                            // 之前完成了改写。此时再走 evaluateJavaScript 不只是多余：
                            // 旧脚本开头的幂等守卫会让它静默 return，而末尾的 alert
                            // 永远到不了 —— 于是"document-start 跑了"和"只有 fallback
                            // 跑了"在日志里完全一样，我据此误判过一次（cefrium#18）。
                            //
                            // 现在两件事一起做：
                            //   1) 先查 document-start 有没有留下完成标记；
                            //      有就跳过，绝不重复注入。
                            //   2) 真要注入时，先塞 __MCEF_INJECT_SRC__ = 'fallback'，
                            //      脚本头部的探针会把它带进 alert，来源一目了然。
                            val script = DESKTOP_SPOOF_JS
                                .replace("__MCEF_W__", e.width.toString())
                                .replace("__MCEF_H__", e.height.toString())
                            // 来源标记必须在脚本执行前落下，脚本头部据此选择告警文本。
                            val fallbackBody =
                                "window.__MCEF_INJECT_SRC__='fallback';\n" + script

                            runCatching {
                                // ValueCallback<String> 只有一个参数：返回值本身。
                                browser.evaluateJavaScript(
                                    "(function(){return (window.__mcefSpoofSrc||'none');})()"
                                ) { probe ->
                                    val already = probe?.contains("docstart") == true
                                    if (already) {
                                        fileLog("fallback skipped id=$id (document-start already applied)")
                                    } else {
                                        runCatching { browser.evaluateJavaScript(fallbackBody) }
                                            .onSuccess { fileLog("fallback inject id=$id") }
                                            .onFailure { fileLog("fallback inject failed id=$id", it) }
                                    }
                                }
                            }.onFailure {
                                // 读不到状态就当没生效，照常注入 —— 宁可重复也不要漏。
                                fileLog("fallback probe failed id=$id, injecting anyway", it)
                                runCatching { browser.evaluateJavaScript(fallbackBody) }
                                    .onSuccess { fileLog("fallback inject id=$id (blind)") }
                                    .onFailure { fileLog("fallback inject failed id=$id", it) }
                            }
                        }
                    }
                    if (!loading && !entry.loadSnapshotFired) {
                        entry.loadSnapshotFired = true
                        val generation = entry.navigationGeneration
                        fileLog("load finished id=$id generation=$generation -> snapshot")
                        scheduleDomSnapshot(id, browser, 300L, generation)
                        // HUD 诊断：页面加载完后，实际去 fetch 页面依赖的四个请求，
                        // 把 HTTP 状态码写进 document.title —— title changed 会进日志，
                        // 这样不用浏览器 DevTools 就能看到 HUD 页面内部的数据获取结果。
                        // 之前所有分析都停在"帧全透明"，但页面内部哪个请求挂了一直无法确认。
                        if (entry.lastDispatchedUrl?.contains("#/hud") == true) {
                            mainHandler.postDelayed({
                                val e2 = browsers[id]
                                if (e2?.browser !== browser) return@postDelayed
                                runCatching {
                                    browser.evaluateJavaScript("""
                                        (async () => {
                                            const out = [];
                                            const t = async (n, p) => {
                                                try { const r = await fetch(p); out.push(n + '=' + r.status); }
                                                catch (e) { out.push(n + '=ERR'); }
                                            };
                                            await t('meta', 'metadata.json');
                                            await t('info', '/api/v1/client/info');
                                            await t('win', '/api/v1/client/window');
                                            await t('comp', '/api/v1/client/components/liquidbounce');
                                            document.title = 'HUDDIAG|' + out.join('|');
                                        })()
                                    """.trimIndent())
                                    fileLog("HUD diag injected id=$id")
                                }
                            }, 3000L)
                        }
                        // 5 秒后仍无 OnPaint 就记录 OSR 状态；
                        // 再 3 秒还没有就明确记录，便于定位渲染链。
                        mainHandler.postDelayed({
                            val e = browsers[id]
                            if (e?.browser === browser &&
                                e.navigationGeneration == generation &&
                                !e.firstPaintLogged
                            ) {
                                fileLog("no paint 5s after load id=$id generation=$generation (invalidate skipped)")
                            }
                        }, 5000L)
                    }
                }
                browser.setOnRenderProcessTerminatedListener { status, code ->
                    fileLog("RENDER PROCESS TERMINATED id=$id status=$status code=$code")
                }
                browser.setJavaScriptEnabled(true)

                // 媒体自动播放：Chromium 默认要用户手势，OSR 里"点一下"未必点得中
                // 播放器控件（输入是 mouse down/up 转发，坐标来自 MC 的 raycast）。
                // 设置里可关，默认开。Cefrium 0.16.0 起有这个方法，老版本没有 ——
                // 所以 runCatching 包一层，失败就退回 Chromium 默认行为。
                if (HostSettings.mediaPlaybackWithoutGesture) {
                    runCatching {
                        browser.setMediaPlaybackRequiresUserGesture(false)
                    }.onSuccess {
                        fileLog("create media playback without gesture id=$id")
                    }.onFailure {
                        fileLog("create setMediaPlaybackRequiresUserGesture failed id=$id", it)
                    }
                }

                // ── 桌面化：CDP 一把梭 ──────────────────────────────────────
                //
                // 0.18.0 起有 executeDevToolsMethod，这条能同时改 UA 串和
                // Client Hints（userAgentMetadata）。光有 setUserAgentOverride
                // 改不到 navigator.userAgentData.mobile —— bilibili 恰恰看这个，
                // 所以 UA 明明是 Windows 还照样跳 m 站。
                if (HostSettings.desktopUserAgent) {
                    runCatching {
                        browser.setUserAgentOverride(DESKTOP_USER_AGENT)
                    }.onSuccess {
                        fileLog("create desktop user agent id=$id")
                    }.onFailure {
                        fileLog("create setUserAgentOverride failed id=$id", it)
                    }

                    // JS 层兜底：Client Hints 万一改不到，至少把 navigator 骗过去。
                    // 0.18.0 修了 document-start 的静默失败，能跑就跑。
                    runCatching {
                        val script = DESKTOP_SPOOF_JS
                            .replace("__MCEF_W__", width.toString())
                            .replace("__MCEF_H__", height.toString())
                        browser.addDocumentStartJavaScript(script, DESKTOP_SPOOF_ORIGINS)
                    }.onSuccess { handler ->
                        entry.spoofHandler = handler
                        fileLog("create desktop spoof js id=$id size=${DESKTOP_SPOOF_JS.length}")
                    }.onFailure {
                        fileLog("create addDocumentStartJavaScript failed id=$id", it)
                    }
                }

                // ── 诊断：dialog 回调必须在 create 阶段就挂上 ──
                // 之前放在 postDelayed 里，等执行时页面正在导航，alert 被丢弃。
                // 这里无条件注册（不依赖桌面 UA 开关），保证任何 alert 都能落盘。
                runCatching {
                    browser.setJsDialogHandler { type, url, message, callback ->
                        fileLog("JSDIAG type=$type url=$url msg=$message")
                        runCatching { callback.continueDialog(false, "") }
                        true // 吞掉，不弹窗
                    }
                }.onFailure { fileLog("setJsDialogHandler failed id=$id", it) }

                fileLog("create loading url id=$id url=$url")
                mainNative("create loadUrl id=$id") { browser.loadUrl(url) }
                entry.lastDispatchedUrl = url
                entry.lastLoadAtMs = System.currentTimeMillis()
                fileLog("create scheduling DOM id=$id")
                // 页面本身未必主动调用 cefriumQuery；加载完成后主动抓一次 DOM，
                // 让 MC 侧 getDomButtons() 有可读缓存，也方便验证整条回传链。
                scheduleDomSnapshot(id, browser, 2000L, entry.navigationGeneration)
                scheduleDomSnapshot(id, browser, 5000L, entry.navigationGeneration)
                scheduleDomSnapshot(id, browser, 10000L, entry.navigationGeneration)
                sendEvent(writer, JSONObject().apply {
                    put("event", "created")
                    put("id", id)
                })
                sendUrl(id, url)
            }.onFailure {
                fileLog("create failed id=$id", it)
            }
        }
    }

    private fun scheduleDomSnapshot(
        id: Long,
        browser: CefriumBrowser,
        delayMs: Long,
        generation: Long
    ) {
        fileLog("schedule DOM snapshot id=$id delayMs=$delayMs generation=$generation")
        mainHandler.postDelayed({
            val current = browsers[id]
            if (current != null && current.browser === browser && current.navigationGeneration == generation) {
                fileLog("run DOM snapshot id=$id delayMs=$delayMs")
                mainNative("DOM snapshot id=$id delayMs=$delayMs") {
                    browser.evaluateJavaScript(buildDomSnapshotScript())
                }
                fileLog("DOM snapshot dispatched id=$id delayMs=$delayMs")
            } else {
                fileLog("skip DOM snapshot id=$id delayMs=$delayMs browser gone")
            }
        }, delayMs)
    }

    private fun handleDestroy(json: JSONObject) {
        val id = json.optLong("id", 0L)
        mainHandler.post {
            mainNative("destroy browser id=$id") { browsers.remove(id)?.browser?.close() }
        }
    }

    /**
     * 改变浏览器尺寸。
     *
     * Cefrium 0.15.0 起有 {@code setSize(w,h)}（此前没有，resize 是空操作）。
     * 必须在主线程调用 —— 它内部走 {@code checkNative} + native 调用。
     *
     * 成功后才更新 entry 的宽高：这两个值是后续帧尺寸校验的依据，
     * 记下一个并未生效的尺寸会让校验失去意义。
     */
    private fun handleResize(json: JSONObject) {
        val id = json.optLong("id", 0L)
        val width = json.optInt("width", 0)
        val height = json.optInt("height", 0)
        if (width <= 0 || height <= 0) {
            fileLog("resize id=$id rejected (invalid size ${width}x$height)")
            return
        }
        val entry = browsers[id] ?: return
        if (entry.width == width && entry.height == height) return  // 尺寸没变，别惊动 CEF

        mainHandler.post {
            runCatching {
                entry.browser.setSize(width, height)
            }.onSuccess {
                entry.width = width
                entry.height = height
                // 尺寸变了，上一帧的像素数不再适用，重新起算"多久没出帧"。
                entry.lastFrameBytes = 0
                lastPaintProgressAt = System.currentTimeMillis()
                fileLog("resize id=$id ${width}x$height ok")
            }.onFailure {
                fileLog("resize id=$id ${width}x$height failed", it)
            }
        }
    }

    /**
     * MC 侧算完查询结果，回来答复页面。
     *
     * 链路：页面 cefQuery → [setQueryHandler] 把 request 推给 MC
     * → MC 的 CefQueryCallbackHandler 处理 → IPC {"cmd":"queryReply"} 到这里
     * → 取出当初存的 [CefriumBrowser.QueryCallback] 调 success/failure。
     *
     * 只有本进程持有这个 callback，所以绕不开这一跳。
     */
    private fun handleQueryReply(json: JSONObject) {
        val id = json.optLong("id", 0L)
        val queryId = json.optLong("queryId", 0L)
        val success = json.optBoolean("success", false)
        val errorCode = json.optInt("errorCode", 0)
        val result = json.optString("result", "")

        val entry = browsers[id]
        if (entry == null) {
            fileLog("queryReply id=$id queryId=$queryId ignored (browser gone)")
            return
        }
        val callback = entry.pendingQueries.remove(queryId)
        if (callback == null) {
            // 超时被清掉、或页面已经走了。不是错误，记一笔就够。
            fileLog("queryReply id=$id queryId=$queryId ignored (no pending callback)")
            return
        }
        runCatching {
            if (success) callback.success(result)
            else callback.failure(errorCode, result)
        }.onFailure {
            fileLog("queryReply id=$id queryId=$queryId failed", it)
        }
    }

    private fun handleLoadUrl(json: JSONObject) {
        val id = json.optLong("id", 0L)
        val url = json.optString("url", "")
        if (url.isBlank()) return
        val entry = browsers[id] ?: return
        val browser = entry.browser
        // 同一浏览器短时间内重复请求同一 URL 直接忽略：create 已经加载过一次，
        // 客户端紧接着又会因 isInitialized/url setter 再发 1~2 次。连续重导航会
        // 打断 CEF OSR 的首帧绘制，是界面变空白/卡死的高概率诱因。
        val now = System.currentTimeMillis()
        if (url == entry.lastDispatchedUrl && now - entry.lastLoadAtMs < 1000L) {
            fileLog("loadUrl deduped id=$id (same url within ${now - entry.lastLoadAtMs}ms)")
            return
        }
        entry.lastDispatchedUrl = url
        entry.lastLoadAtMs = now
        val generation = ++entry.navigationGeneration
        entry.loadSnapshotFired = false
        mainHandler.post {
            val current = browsers[id]
            if (current?.browser !== browser || current.navigationGeneration != generation) {
                fileLog("drop stale loadUrl id=$id generation=$generation")
                return@post
            }
            mainNative("loadUrl id=$id gen=$generation") { browser.loadUrl(url) }
            sendUrl(id, url)
        }
    }

    private fun handleEval(json: JSONObject) {
        val script = json.optString("script", "")
        if (script.isBlank()) return
        handleEval(json, script)
    }

    private fun handleEval(json: JSONObject, script: String) {
        val id = json.optLong("id", 0L)
        val browser = browsers[id]?.browser ?: return
        mainHandler.post {
            if (browsers[id]?.browser !== browser) {
                fileLog("drop stale eval id=$id")
                return@post
            }
            mainNative("eval id=$id") { browser.evaluateJavaScript(script) }
        }
    }

    // 通用导航动作（reload/goBack/goForward/stopLoading）：
    // 统一走主线程 + 过期 browser 检查 + native 打点，和其余 CEF 调用保持一致。
    private fun handleBrowserAction(
        json: JSONObject,
        name: String,
        action: (CefriumBrowser) -> Unit
    ) {
        val id = json.optLong("id", 0L)
        val browser = browsers[id]?.browser ?: return
        mainHandler.post {
            if (browsers[id]?.browser !== browser) {
                fileLog("drop stale $name id=$id")
                return@post
            }
            runCatching { mainNative("$name id=$id") { action(browser) } }
                .onFailure { fileLog("$name failed id=$id", it) }
        }
    }

    private fun handleDomSnapshot(json: JSONObject) {
        val id = json.optLong("id", 0L)
        val script = buildDomSnapshotScript()
        val browser = browsers[id]?.browser ?: return
        fileLog("dispatch DOM snapshot id=$id")
        // IPC 线程不可直接进入 CEF；统一交给初始化 CEF 的主 Looper。
        mainHandler.post {
            if (browsers[id]?.browser !== browser) {
                fileLog("drop stale DOM snapshot id=$id")
                return@post
            }
            mainNative("DOM snapshot direct id=$id") { browser.evaluateJavaScript(script) }
        }
    }

    private fun handleElementsAtPoint(json: JSONObject) {
        val id = json.optLong("id", 0L)
        val x = json.optInt("x", 0)
        val y = json.optInt("y", 0)
        browsers[id]?.browser ?: return
        handleEval(json, buildElementsAtPointScript(x, y))
    }

    // libmcef INPUT_* 常量 → CefriumBrowser 注入。
    //
    // 与 native bridge 的契约（两边必须一致）：
    //   0 MOUSE_MOVE / 1 MOUSE_DOWN / 2 MOUSE_UP → sendTouchEvent(action, x, y, 0)
    //   3 MOUSE_WHEEL                            → JS window.scrollBy（SDK 没有 wheel API）
    //   4 KEY_DOWN / 5 KEY_UP / 6 CHAR           → 后端不支持（SDK 没有键盘 API）
    // CefriumBrowser.sendTouchEvent 只接受 (action, x, y, pointerId)，所以协议里的
    // button / keyCode / scanCode / modifiers / codepoint 目前一律忽略，只有 x/y/delta 生效。
    private fun handleInput(json: JSONObject) {
        val id = json.optLong("id", 0L)
        val entry = browsers[id] ?: return
        val type = json.optInt("type", -1)
        val x = json.optDouble("x", 0.0).toFloat()
        val y = json.optDouble("y", 0.0).toFloat()
        // 输入诊断：移动事件量大且不关键（只记 3 条），按下/抬起/滚轮/按键永不漏。
        if (type != 0) {
            if (nonMoveInputLogCount < 30) {
                nonMoveInputLogCount++
                fileLog("input id=$id type=$type x=$x y=$y button=${json.optInt("button", -1)}")
            }
        } else if (moveInputLogCount < 3) {
            moveInputLogCount++
            fileLog("input(MOVE) id=$id x=$x y=$y")
        }
        mainHandler.post {
            if (browsers[id]?.browser !== entry.browser) {
                fileLog("drop stale input id=$id type=$type")
                return@post
            }
            runCatching {
                // 协议本身就是鼠标语义（libmcef 的 INPUT_MOUSE_*，带 button / modifiers），
                // 之前这里却全部翻译成 sendTouchEvent —— 语义错配，所以只有点击能过、
                // 拖动和滑块全部失效（页面监听的是 mousedown/mousemove/mouseup）。
                // 现在直接走 CefriumBrowser 的鼠标 API，button 也从 IPC 透传。
                val button = json.optInt("button", 0).let { if (it < 0) 0 else it }
        val keyCode = json.optInt("keyCode", 0)
        val scanCode = json.optInt("scanCode", 0)
        val modifiers = json.optInt("modifiers", 0)
        val codepoint = json.optInt("codepoint", 0)
                when (type) {
                    0 -> mainNative("mouse MOVE id=$id") {
                        entry.browser.sendMouseMove(x.toInt(), y.toInt())
                    }
                    1 -> mainNative("mouse DOWN id=$id button=$button") {
                        entry.browser.sendMouseDown(x.toInt(), y.toInt(), button, 1)
                    }
                    2 -> mainNative("mouse UP id=$id button=$button") {
                        entry.browser.sendMouseUp(x.toInt(), y.toInt(), button, 1)
                    }
                    3 -> mainNative("mouse WHEEL id=$id") {
                        // SDK 有真实 wheel API，不再用 JS scrollBy 凑 ——
                        // JS 回退对不滚动的 HUD 面板完全无效。
                        entry.browser.sendMouseWheel(
                            x.toInt(), y.toInt(),
                            json.optInt("deltaX", 0), json.optInt("deltaY", 0)
                        )
                    }
                    // ── 键盘 ────────────────────────────────────────────
                    //
                    // Cefrium 0.18.0 有真正的键盘 API，两条路各管一段：
                    //
                    //   KEY_CHAR (6) → imeCommitText(字符, 0)
                    //      直接提交文本，语义明确，不依赖 keycode 映射。
                    //      打字、中文、符号都走这条。
                    //
                    //   KEY_DOWN/UP (4/5) → sendKeyEvent(...)
                    //      方向键、退格、回车这类"按键"必须走 key event，
                    //      否则页面收不到 keydown/keypress，输入框的光标移动、
                    //      删除都不会工作。
                    // 按键：走 sendKeyEvent(android.view.KeyEvent) 重载。
                    //
                    // 上游源码（cef-android / CefriumBrowser.java）写明：
                    //   sendKeyEvent(int type, int modifiers, int windowsKeyCode,
                    //                int nativeKeyCode, char character)
                    // 第三个参数是 **Windows 虚拟键码**，不是 Android 键码；
                    // 而 sendKeyEvent(KeyEvent) 内部用 Chromium 自己的映射表做
                    // Android→Windows 转换，并发出 RAWKEYDOWN(+CHAR)/KEYUP
                    // 的正确序列。注释原文：
                    //   "Prefer this over the five-argument sendKeyEvent."
                    //
                    // 之前按 (keyCode, scanCode, modifiers, action, char) 传五参版，
                    // type 位置收到的是键码（29），非法 type 让 native 直接崩，
                    // 表现为一按特殊键整个进程被杀死、无 Java 异常、无 hs_err。
                    4, 5 -> mainNative("key ${if (type == 4) "DOWN" else "UP"} id=$id key=$keyCode") {
                        val androidKey = GlfwKey.map(keyCode)
                        if (androidKey != 0) {
                            val action = if (type == 4) {
                                android.view.KeyEvent.ACTION_DOWN
                            } else {
                                android.view.KeyEvent.ACTION_UP
                            }
                            val now = android.os.SystemClock.uptimeMillis()
                            val ev = android.view.KeyEvent(
                                now, now, action, androidKey, 0, modifiers
                            )
                            entry.browser.sendKeyEvent(ev)
                        }
                    }
                    6 -> mainNative("key CHAR id=$id cp=$codepoint") {
                        val text = if (codepoint > 0) {
                            String(Character.toChars(codepoint))
                        } else {
                            ""
                        }
                        if (text.isNotEmpty()) {
                            entry.browser.imeCommitText(text, 0)
                        }
                    }
                }
            }.onFailure { Log.w(TAG, "input 注入失败 type=$type", it) }

            // 判定探针：CEF 到底是"静止无 damage"还是"OSR 卡死"。
            // CEF OSR 只在页面有 damage 时回调 OnPaint；页面不动就没有新帧，
            // 这本身是正常的。所以冻结有两种完全不同的原因，必须分开：
            //   主动 invalidate() 后立刻出帧 → CEF 正常，问题在"输入没让页面变化"
            //   invalidate() 后仍不出帧       → OSR/渲染真的停了
            if (type == 1 || type == 2) {
                val paintsBefore = entry.paintCount
                val generation = entry.navigationGeneration
                mainHandler.postDelayed({
                    val current = browsers[id]
                    if (current?.browser !== entry.browser || current.navigationGeneration != generation) return@postDelayed
                    if (entry.paintCount != paintsBefore) {
                        fileLog("paint after input type=$type paints=${entry.paintCount}")
                        return@postDelayed
                    }
                    // 这里必须真的 invalidate，不能只记日志。
                    // 目的：把"页面静止无 damage"和"OSR 真的停了"分开 ——
                    //   主动 invalidate 后出帧 → CEF 正常，只是没有新内容
                    //   invalidate 后仍不出帧   → OSR 停摆，这才是要治的
                    // 先前的注释担心 invalidate 会放大卡死，所以一直没调；结果是这条探针
                    // 永远得不出结论（两种情形都只会打印同一句 no paint）。现在改为真调用，
                    // 单次、有上限、失败也只会多一行日志。
                    runCatching {
                        entry.browser.invalidate()
                        fileLog("invalidate sent after no-paint id=$id type=$type")
                    }.onFailure { fileLog("invalidate failed id=$id", it) }
                    mainHandler.postDelayed({
                        val currentAfterInvalidate = browsers[id]
                        if (currentAfterInvalidate?.browser !== entry.browser ||
                            currentAfterInvalidate.navigationGeneration != generation
                        ) return@postDelayed
                        if (entry.paintCount != paintsBefore) {
                            fileLog("paint after input invalidate type=$type paints=${entry.paintCount} (CEF alive, page was static)")
                        } else {
                            fileLog("STILL no paint after input invalidate type=$type (OSR really stuck)")
                        }
                    }, 600L)
                }, 400L)
            }
        }
    }

    /**
     * 把页面的 cefQuery 请求推给 MC 侧。
     *
     * 只推请求本身，结果要等 MC 侧算完从 queryReply 回来 —— Cefrium 的 callback
     * 留在这边（pendingQueries），只有本进程能答复页面。
     */
    private fun sendQuery(id: Long, queryId: Long, request: String, origin: String) {
        fileLog("sending query id=$id queryId=$queryId bytes=${request.length}")
        sendEvent(clientWriter ?: return, JSONObject().apply {
            put("event", "query")
            put("id", id)
            put("queryId", queryId)
            put("request", request)
            put("origin", origin)
            put("persistent", 0)
        })
    }

    private fun sendDom(id: Long, domJson: String) {
        fileLog("sending DOM id=$id bytes=${domJson.length}")
        sendEvent(clientWriter ?: return, JSONObject().apply {
            put("event", "dom")
            put("id", id)
            put("json", domJson)
        })
    }

    private fun sendUrl(id: Long, url: String) {
        sendEvent(clientWriter ?: return, JSONObject().apply {
            put("event", "url")
            put("id", id)
            put("url", url)
        })
    }

    // 帧传输：JSON header 行 + 紧跟的 size 字节裸 BGRA（与 libmcef 客户端约定一致）。
    // 所有 socket 写入集中到这个单线程执行器：
    // 1) Android 主线程禁止网络 I/O（NetworkOnMainThreadException）；
    // 2) 单线程天然串行化，帧与 DOM 事件不会交错写坏行协议。
    private val writerExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "MCEF-SocketWriter").apply {
            isDaemon = true
            setUncaughtExceptionHandler { t, th ->
                fileLog("FATAL crash thread=MCEF-SocketWriter", th)
            }
        }
    }

    private class FramePayload(
        val id: Long,
        val w: Int,
        val h: Int,
        val stride: Int,
        val payload: ByteArray
    )

    // 单槽覆盖式帧管道：OSR 回调比 socket 写入快时，旧帧直接被新帧覆盖丢弃。
    // 否则积压会让画面延迟无上限增长（表现为"越用越卡、点了半天才响应"）。
    private val pendingFrame = java.util.concurrent.atomic.AtomicReference<FramePayload?>(null)
    private val framePumpScheduled = java.util.concurrent.atomic.AtomicBoolean(false)

    // 全屏帧 2800x1260x4 ≈ 14MB；按 33fps 逐帧分配 = 约 460MB/s 垃圾，
    // 会持续触发 GC 卡住宿主。这里复用缓冲，最多保留 3 块。
    private val framePool = ArrayDeque<ByteArray>()
    private val framePoolLock = Any()

    private fun borrowFrameBuffer(size: Int): ByteArray = synchronized(framePoolLock) {
        while (framePool.isNotEmpty()) {
            val buf = framePool.removeLast()
            if (buf.size == size) return buf
        }
        ByteArray(size)
    }

    private fun returnFrameBuffer(buf: ByteArray) {
        synchronized(framePoolLock) {
            if (framePool.size < 3) framePool.addLast(buf)
        }
    }

    private fun postFrame(id: Long, w: Int, h: Int, stride: Int, payload: ByteArray) {
        val replacement = FramePayload(id, w, h, stride, payload)
        // 单槽覆盖时必须回收被丢弃的旧帧，否则 socket 慢时每帧都会分配大数组。
        pendingFrame.getAndSet(replacement)?.let { returnFrameBuffer(it.payload) }
        if (framePumpScheduled.compareAndSet(false, true)) {
            writerExecutor.execute { pumpFrame() }
        }
    }

    private fun pumpFrame() {
        val frame = pendingFrame.getAndSet(null)
        try {
            if (frame != null) writeFrame(frame)
        } finally {
            framePumpScheduled.set(false)
            // 竞态兜底：pump 退出的瞬间可能刚好又压入了一帧。
            if (pendingFrame.get() != null && framePumpScheduled.compareAndSet(false, true)) {
                writerExecutor.execute { pumpFrame() }
            }
        }
    }

    private fun writeFrame(frame: FramePayload) {
        val writer = clientWriter
        val out = clientOutput
        if (writer == null || out == null) {
            returnFrameBuffer(frame.payload)
            return
        }
        runCatching {
            writer.write(JSONObject().apply {
                put("event", "frame")
                put("id", frame.id)
                put("w", frame.w)
                put("h", frame.h)
                put("stride", frame.stride)
                put("size", frame.payload.size)
            }.toString())
            writer.write("\n")
            writer.flush()
            out.write(frame.payload)
            out.flush()
            frameSentCount++
            if (!firstFrameSentLogged) {
                firstFrameSentLogged = true
                fileLog("first frame sent id=${frame.id} ${frame.w}x${frame.h} bytes=${frame.payload.size}")
            }
        }.onFailure {
            fileLog("sendFrame failed id=${frame.id}", it)
        }
        returnFrameBuffer(frame.payload)
    }

    private fun sendEvent(writer: BufferedWriter, event: JSONObject) {
        writerExecutor.execute {
            runCatching {
                writer.write(event.toString())
                writer.write("\n")
                writer.flush()
            }.onFailure {
                fileLog("sendEvent failed event=${event.optString("event")}", it)
            }
        }
    }

    private data class BrowserEntry(
        val id: Long,
        val browser: CefriumBrowser,
        @Volatile var width: Int,
        @Volatile var height: Int,
        @Volatile var lastFrameBytes: Int = 0,
        @Volatile var paintCount: Long = 0L,
        @Volatile var lastPaintNs: Long = 0L,
        @Volatile var loadSnapshotFired: Boolean = false,
        @Volatile var navigationGeneration: Long = 0L,
        /** document-start 注入脚本的句柄，browser 关闭时随之失效。 */
        @Volatile var spoofHandler: com.cefrium.CefriumBrowser.ScriptHandler? = null,
        /** 后备注入是否已执行，避免每次 loading 事件重复注入。 */
        @Volatile var fallbackInjected: Boolean = false,
        @Volatile var firstPaintLogged: Boolean = false,
        // 去重：客户端会为同一 URL 连发 2~3 次 loadUrl（create 自带一次 + isInitialized
        // setter 一次 + ThemeManager.updateImmediate 改 url 一次）。连续重导航会让 CEF OSR
        // 在首帧绘制中途被打断，是"点一下界面就白/卡住"的高概率诱因。
        @Volatile var lastDispatchedUrl: String? = null,
        @Volatile var lastLoadAtMs: Long = 0L,
        // 已推给 MC 侧、还没答复的查询。Cefrium 的 callback 只在这里有效 ——
        // MC 算完结果得回来调它，页面才收得到 onSuccess/onFailure。
        val pendingQueries: MutableMap<Long, CefriumBrowser.QueryCallback> =
            java.util.concurrent.ConcurrentHashMap()
    )

    companion object {
        private const val CHANNEL_ID = "mcef_cefrium"
        private const val NOTIF_ID = 9527

        private const val TAG = "MCEF/CefriumHost"
        private const val HOST_ADDR = "127.0.0.1"
        private const val HOST_PORT = 39090

        /** ready 回调的等待上限。超时即判定 Activity 起不来或回调被丢，必须显式记一笔。 */
        private const val READY_WATCHDOG_MS = 30_000L

        /**
         * 桌面版 User-Agent。
         *
         * 版本号必须对齐 Cefrium 内嵌的 Chromium —— 0.17.1 是 156.0.8078.80
         * （见 cefrium-sdk 的 POM）。写死旧版本号会让 UA 串和 Sec-CH-UA
         * 对不上，某些站点会判定为伪装并降级或拦截。
         *
         * 用 Windows + Win64：绝大多数站点对 Windows 桌面 UA 走宽屏布局。
         */
        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/156.0.0.0 Safari/537.36"

        /**
         * 桌面环境伪装脚本，用 addDocumentStartJavaScript 注入。
         *
         * 为什么需要它：setUserAgentOverride 只改 UA 串（HTTP 头 + navigator.userAgent），
         * 改不到 Client Hints、navigator.platform、screen 尺寸、ontouchstart 这些。
         * bilibili 这类站点不看 UA，看的是 navigator.userAgentData.mobile，
         * 于是照样跳到 m.bilibili.com。
         *
         * addDocumentStartJavaScript 在文档开始解析之前执行，赶得上站点自己的检测脚本；
         * evaluateJavaScript 是 loadUrl 之后才跑，那时跳转已经发生，来不及。
         *
         * __MCEF_W__ / __MCEF_H__ 由调用方按浏览器实际尺寸替换。
         */
        private val DESKTOP_SPOOF_JS = """
(function () {
  'use strict';

  // ── 观测必须先于一切提前返回 ────────────────────────────────────
  // 旧版本把 alert 放在脚本末尾，而中间有幂等守卫（__mcefDesktopSpoof）。
  // 谁先跑谁占守卫，第二条路径在守卫处静默 return，alert 永远不会到 ——
  // 结果"document-start 跑了"和"只有 fallback 跑了"产生完全相同的日志，
  // 我因此误判过一次（见 cefrium#18）。
  // 现在探针是第一条语句，且带上来源标记，两条路径可区分。
  var __mcefSrc = (window.__MCEF_INJECT_SRC__ === 'fallback') ? 'fallback' : 'docstart';
  try {
    alert('PROBE src=' + __mcefSrc);
  } catch (e) { /* alert 被拦就没了，不影响下面的改写 */ }

  // 只在最顶层文档改，子帧不动 —— 免得和页面自己的 iframe 逻辑打架。
  try {
    if (window.top !== window.self) return;
  } catch (e) { return; }

  // 已经改过就退出，防止 document-start 在 SPA 路由切换时重复执行。
  if (Object.prototype.hasOwnProperty.call(window, '__mcefDesktopSpoof')) return;
  try {
    Object.defineProperty(window, '__mcefDesktopSpoof', { value: 1, enumerable: false });
    // 记录是哪条路径真正完成了改写，供 document-start 与 fallback 互斥判断。
    Object.defineProperty(window, '__mcefSpoofSrc', { value: __mcefSrc, enumerable: false });
  } catch (e) { /* 拿不到就算了，继续往下改 */ }

  var D = Object.defineProperty;

  function hooked(obj, prop, value) {
    if (!obj) return;
    try {
      D(obj, prop, { get: function () { return value; }, configurable: true, enumerable: true });
    } catch (e) { /* 只读属性，放弃 */ }
  }

  // ── User-Agent ──────────────────────────────────────────────────
  // setUserAgentOverride 已经改过 HTTP 头和 navigator.userAgent 了，
  // 这里再兜一层是为了防止某些站点读的是别处的副本。
  var UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 ' +
           '(KHTML, like Gecko) Chrome/156.0.0.0 Safari/537.36';
  hooked(navigator, 'userAgent', UA);
  hooked(navigator, 'appVersion', UA.replace('Mozilla/', ''));
  hooked(navigator, 'platform', 'Win32');
  hooked(navigator, 'vendor', 'Google Inc.');
  hooked(navigator, 'productSub', '20030107');
  hooked(navigator, 'oscpu', undefined);

  // ── Client Hints：这是 setUserAgentOverride 改不到的那部分 ─────────
  // bilibili 之类就是靠 userAgentData.mobile 判断的。
  // 注意两点：
  //   1) 不能写成 if (navigator.userAgentData) —— CEF OSR 下这个属性
  //      本来就不存在（实测 mobile=none），加了判断等于整段跳过。
  //   2) 要定义在 Navigator.prototype 上，不能只定义在 navigator 实例上：
  //      实例上的普通属性会被原型链上的访问器遮蔽，读到的还是原型那套。
  var navProto = Object.getPrototypeOf(navigator) || Navigator.prototype;
  try {
    D(navProto, 'userAgentData', {
      configurable: true,
      enumerable: true,
      get: function () {
        return {
          brands: [
            { brand: 'Chromium', version: '156' },
            { brand: 'Google Chrome', version: '156' },
            { brand: 'Not?A_Brand', version: '24' }
          ],
          mobile: false,
          platform: 'Windows',
          getHighEntropyValues: function () {
            return Promise.resolve({
              architecture: 'x86',
              bitness: '64',
              brands: [
                { brand: 'Chromium', version: '156' },
                { brand: 'Google Chrome', version: '156' },
                { brand: 'Not?A_Brand', version: '24' }
              ],
              mobile: false,
              model: '',
              platform: 'Windows',
              platformVersion: '10.0.0',
              uaFullVersion: '156.0.8078.80',
              wow64: false
            });
          },
          toJSON: function () {
            return { brands: this.brands, mobile: false, platform: 'Windows' };
          }
        };
      }
    });
  } catch (e) { /* ignore */ }

  // ── 触屏痕迹：手机上 ontouchstart 存在，桌面 Chrome 没有 ──────────
  try {
    // 'ontouchstart' in window 走的是原型链，delete 实例上的同名属性
    // 删不掉继承来的那一个，判断依然为 true。要连原型一起清。
    delete window.ontouchstart;
    delete document.ontouchstart;
    var winProto = Object.getPrototypeOf(window);
    while (winProto) {
      try { delete winProto.ontouchstart; } catch (e) { /* ignore */ }
      winProto = Object.getPrototypeOf(winProto);
    }
    D(navigator, 'maxTouchPoints', { get: function () { return 0; }, configurable: true });
  } catch (e) { /* ignore */ }

  // ── 屏幕尺寸：手机是窄屏，站点会据此选布局 ───────────────────────
  // __MCEF_W__/__MCEF_H__ 由 Kotlin 侧按浏览器实际尺寸替换。
  try {
    hooked(screen, 'width', __MCEF_W__);
    hooked(screen, 'height', __MCEF_H__);
    hooked(screen, 'availWidth', __MCEF_W__);
    hooked(screen, 'availHeight', __MCEF_H__);
    hooked(screen, 'colorDepth', 24);
    hooked(screen, 'pixelDepth', 24);
  } catch (e) { /* ignore */ }

  // ── 视口 ────────────────────────────────────────────────────────
  try {
    D(window, 'innerWidth', { get: function () { return __MCEF_W__; }, configurable: true });
    D(window, 'innerHeight', { get: function () { return __MCEF_H__; }, configurable: true });
    D(window, 'outerWidth', { get: function () { return __MCEF_W__; }, configurable: true });
    D(window, 'outerHeight', { get: function () { return __MCEF_H__; }, configurable: true });
    D(window, 'devicePixelRatio', { get: function () { return 1; }, configurable: true });
  } catch (e) { /* ignore */ }

  // ── 语言 ────────────────────────────────────────────────────────
  try {
    hooked(navigator, 'language', 'zh-CN');
    hooked(navigator, 'languages', ['zh-CN', 'zh', 'en']);
  } catch (e) { /* ignore */ }

  // ── 改写结果自检（写进日志，不弹窗）────────────────────────────
  // 早期这里用 alert 报告，但 alert 会阻塞页面且在 document-start 阶段
  // 可能与页面自己的 dialog 撞车。现在只在末尾留一个可读取的摘要对象，
  // 需要时用 evaluateJavaScript 读 window.__mcefSpoofReport。
  try {
    var uad = navigator.userAgentData;
    window.__mcefSpoofReport = {
      src: __mcefSrc,
      mobile: (uad && typeof uad.mobile !== 'undefined') ? String(uad.mobile) : 'none',
      platform: navigator.platform,
      screenW: screen.width,
      screenH: screen.height,
      touch: ('ontouchstart' in window) ? 'Y' : 'N',
      ua: String(navigator.userAgent).substring(0, 70)
    };
  } catch (e) { /* ignore */ }
})();
""".trimIndent()

        /**
         * CDP 的 Emulation.setUserAgentOverride 参数。
         *
         * userAgentMetadata 是重点：navigator.userAgentData 从这里来，
         * mobile=false + platform=Windows 才会让 bilibili 这类站点走桌面布局。
         * fullVersion 要对得上底层 Chromium 156，否则 Sec-CH-UA 与引擎版本
         * 不一致，有些站点会判为伪装。
         */
        private val DESKTOP_UA_CDP_PARAMS: String = """
            {
              "userAgent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.0.0 Safari/537.36",
              "acceptLanguage": "zh-CN,zh;q=0.9,en;q=0.8",
              "platform": "Win32",
              "userAgentMetadata": {
                "brands": [
                  {"brand": "Chromium", "version": "156"},
                  {"brand": "Google Chrome", "version": "156"},
                  {"brand": "Not)A;Brand", "version": "24"}
                ],
                "fullVersionList": [
                  {"brand": "Chromium", "version": "156.0.8078.80"},
                  {"brand": "Google Chrome", "version": "156.0.8078.80"},
                  {"brand": "Not)A;Brand", "version": "24.0.0.0"}
                ],
                "fullVersion": "156.0.8078.80",
                "platform": "Windows",
                "platformVersion": "15.0.0",
                "architecture": "x86",
                "model": "",
                "mobile": false,
                "bitness": "64",
                "wow64": false
              }
            }
        """.trimIndent()

        /**
         * 注入脚本的生效域名。
         *
         * 不能传空集合 —— Cefrium 底层借的是 Chromium WebView 的
         * WEBVIEW_INSTANCE_ADD_DOCUMENT_START_JAVASCRIPT，origins 是白名单语义，
         * 空集等于"一个 origin 都不匹配"，脚本注册成功但永不执行（无异常、无日志）。
         * "*" 表示全部。
         */
        private val DESKTOP_SPOOF_ORIGINS: Set<String> = setOf("*")

        @Volatile private var instance: CefriumHostService? = null

        /** 最近一次快照；Service 没起来时是 null。 */
        @Volatile
        var currentSnapshot: Snapshot? = null
            private set

        /** 日志文件路径；界面用它做日志预览。 */
        fun logFileFor(context: android.content.Context): File =
            File(context.getExternalFilesDir(null), "mcef-cefrium.log")

        /** 由 Service 定期调用，把状态推给 UI。 */
        fun publishSnapshot(svc: CefriumHostService) {
            currentSnapshot = svc.snapshot()
        }

        /** 服务是否在跑（只读入口，界面用）。 */
        fun isRunning(): Boolean = currentSnapshot?.running == true

        /** Service 实例缺失时仍要落盘用的 Context，在 Service.onCreate 里赋值。 */
        @Volatile private var appContext: Context? = null

        /**
         * 公共目录镜像。
         *
         * 应用有 MANAGE_EXTERNAL_STORAGE（实测 appops: allow），能写这里；而
         * /storage/emulated/0/Android/data 被 scoped storage 挡着 —— 除特权通道外读不到。
         * 2026-10-05 的调查里 Shizuku 反复掉线，几次取不到日志，这一步就是为了不再被它卡住：
         * 镜像到公共目录后，普通进程（普通 shell / 文件管理器）直接就能读。
         *
         * 解析失败一律 null，主日志不受任何影响——镜像是附加的，不是替代品。
         */
        private val publicLogFile: File? by lazy {
            runCatching { File(logDir(instance) ?: return@runCatching null, "mcef-cefrium.log") }.getOrNull()
        }

        /**
         * 日志目录。
         *
         * 插件没有 MANAGE_EXTERNAL_STORAGE / 旧版外部存储权限，写不进
         * /storage/emulated/0/mcef-host-logs（EACCES）。所以先试公共目录，
         * 失败就用应用自己的外部目录 —— Android/data/<包名>/files，
         * 该路径对本应用始终可写，而且从 adb / 文件管理器也能读。
         */
        fun logDir(svc: CefriumHostService? = null): File? {
            runCatching {
                val shared = File(android.os.Environment.getExternalStorageDirectory(), "mcef-host-logs")
                if (shared.exists() || shared.mkdirs()) {
                    // 能建/能进，再确认真的可写（mkdirs 成功不代表可写）
                    val probe = File(shared, ".w")
                    if (runCatching { probe.appendText(""); probe.delete(); true }.getOrDefault(false)) {
                        return shared
                    }
                }
            }
            val appDir = (svc ?: instance)?.getExternalFilesDir(null)
            return appDir?.let { d ->
                if (d.exists() || d.mkdirs()) d else null
            }
        }

        /**
         * 同步追加一行到公共目录镜像。
         * 给低频、且必须在进程猝死时也留得下的调用用（兜底日志、崩溃记录）；
         * 高频日志走 startFileLogger 的异步双写，不走这里。
         *
         * 路径**每次重新解析**，不用 by lazy 缓存：早先用 lazy 时，主进程在
         * Application.onCreate 阶段首次访问（此时外部存储可能还没挂载好）会得到 null，
         * 而 lazy 会把这颗 null 永久记住 —— 之后所有镜像日志都静默丢失，
         * 表现为"加了诊断日志却一行都看不到"。代价只是每次多做一次 exists 检查。
         */
        fun appendPublicMirror(line: String) {
            runCatching {
                val dir = logDir(instance) ?: return@runCatching
                File(dir, "mcef-cefrium.log").appendText(line + "\n")
            }
        }

        /**
         * 公共目录下的 CEF flag 文件；与日志镜像同目录，便于无特权改写。
         *
         * 动机：改 flag 原本必须钻 Android/data（被 scoped storage 挡着），每次 A/B 都得走
         * 特权通道或文件管理器。放到公共目录后，普通进程直接可写。
         */
        fun publicFlagsFile(): File? {
            val log = publicLogFile ?: return null
            return File(log.parentFile, "mcef_flags.txt")
        }

        /**
         * 兜底日志：Service 实例为 null 时 fileLog 用不了，但这条信息恰恰最要紧。
         * 三路同时写：logcat（实例无关）、应用私有目录、公共目录镜像。
         */
        private fun staticFileLog(message: String) {
            val line = "${System.currentTimeMillis()} $message"
            Log.e(TAG, line)
            runCatching {
                val dir = appContext?.getExternalFilesDir(null) ?: return@runCatching
                File(dir, "mcef-cefrium.log").appendText(line + "\n")
            }
            appendPublicMirror(line)
        }

        /**
         * Activity.onCreate 入口标记。有无这一行，是区分"Activity 压根没被创建"和
         * "创建了但 ready 回调被丢弃"的唯一线索，见 CefriumHostActivity.onCreate 的注释。
         */
        fun noteHostActivityCreated() {
            staticFileLog("CefriumHostActivity.onCreate entered")
        }

        /**
         * Activity 生命周期打点。
         *
         * 2026-10-05 19:12 那次运行证明了它的必要性：Activity 在浏览器出生前 48 秒就被
         * pause，此后整场再没恢复 —— 浏览器是在"宿主已 pause"的状态下活完一生的。
         * 于是"拿 Activity 可见性去驱动 CefriumBrowser.onPause()/setPageVisible()"这条路
         * 对本架构（离屏渲染推帧给游戏）根本不成立：真按它做，页面会被永久置 HIDDEN，
         * OSR 直接断流，反而制造新问题。所以这里只记录事实，不替产品做决定。
         *
         * 现在最需要看的是 onStop 有没有发生 —— 即 Window 是否被系统撤走。
         */
        fun noteHostLifecycle(name: String) {
            staticFileLog("Activity.$name")
        }

        fun onHostActivityReady(activity: android.app.Activity) {
            val svc = instance
            if (svc == null) {
                // 原实现是 `instance?.onHostActivityReadyInternal(activity)`：实例为 null
                // 就把整个回调静默丢掉，一条日志都不留，CEF 永不初始化、客户端 create
                // 一直 deferred。mcef-cefrium.log 里的 pid=455 就是这么无声废掉的。
                staticFileLog(
                    "!! onHostActivityReady DROPPED: service instance is null " +
                        "(activity=${activity.javaClass.name})。CEF 不会初始化。"
                )
                return
            }
            svc.onHostActivityReadyInternal(activity)
        }

        fun onHostActivityDestroyed() {
            val svc = instance
            if (svc == null) {
                staticFileLog("onHostActivityDestroyed DROPPED: service instance is null")
                return
            }
            svc.onHostActivityDestroyedInternal()
        }

        fun start(context: Context) {
            context.applicationContext.startService(
                Intent(context.applicationContext, CefriumHostService::class.java)
            )
        }

        fun stop(context: Context) {
            context.applicationContext.stopService(
                Intent(context.applicationContext, CefriumHostService::class.java)
            )
        }

        private fun buildDomSnapshotScript(): String = """
            (() => {
              const send = (o) => {
                try {
                  if (window.cefriumQuery) {
                    window.cefriumQuery({request: JSON.stringify(o), onSuccess(){}, onFailure(){}});
                    return true;
                  }
                } catch (e) {}
                return false;
              };
              try {
              // title 探针：不依赖 cefriumQuery 桥。宿主通过 OnTitleChangedListener 收到它
              // 就证明 evaluateJavaScript 真的在页面里执行了；值还能区分桥是否存在。
              try { document.title = 'mcefjs:' + (window.cefriumQuery ? 'bridge' : 'nobridge') + ':' + document.readyState; } catch (te) {}
              send({type:'dom_probe', hasBridge: !!window.cefriumQuery, readyState: document.readyState,
                    url: location.href, title: document.title, ua: navigator.userAgent});
              const pickSelector = (el) => {
                if (!el || el.nodeType !== 1) return '';
                if (el.id) return '#' + CSS.escape(el.id);
                const parts = [];
                for (let n = el; n && n.nodeType === 1 && n !== document.documentElement; n = n.parentElement) {
                  let part = n.tagName.toLowerCase();
                  if (n.classList && n.classList.length) part += '.' + [...n.classList].slice(0, 3).map(CSS.escape).join('.');
                  const p = n.parentElement;
                  if (p) {
                    const same = [...p.children].filter(x => x.tagName === n.tagName);
                    if (same.length > 1) part += `:nth-of-type(${'$'}{same.indexOf(n) + 1})`;
                  }
                  parts.unshift(part);
                  if (parts.length >= 6) break;
                }
                return parts.join(' > ');
              };
              const items = [...document.querySelectorAll('button,a,input,textarea,select,[role="button"],[onclick],[tabindex]')]
                .map((el) => {
                  const r = el.getBoundingClientRect();
                  const s = getComputedStyle(el);
                  return {tag: el.tagName.toLowerCase(), id: el.id || '', className: String(el.className || ''),
                    text: (el.innerText || el.value || el.getAttribute('aria-label') || '').trim().slice(0, 200),
                    x: r.left, y: r.top, w: r.width, h: r.height, zIndex: s.zIndex,
                    visible: r.width > 0 && r.height > 0 && s.display !== 'none' && s.visibility !== 'hidden',
                    pointerEvents: s.pointerEvents, selector: pickSelector(el)};
                }).filter(x => x.visible);
              send({type:'dom_snapshot', dpr: devicePixelRatio, url: location.href, items});
              return JSON.stringify({type:'dom_snapshot', dpr: devicePixelRatio, url: location.href, items});
              } catch (e) {
                try { document.title = 'mcefjserr'; } catch (te2) {}
                send({type:'dom_error', error: String((e && e.stack) || e)});
                return 'error:' + String(e);
              }
            })()
        """.trimIndent()

        private fun buildElementsAtPointScript(x: Int, y: Int): String = """
            (() => {
              const pickSelector = (el) => {
                if (!el || el.nodeType !== 1) return '';
                if (el.id) return '#' + CSS.escape(el.id);
                const parts = [];
                for (let n = el; n && n.nodeType === 1 && n !== document.documentElement; n = n.parentElement) {
                  let part = n.tagName.toLowerCase();
                  if (n.classList && n.classList.length) part += '.' + [...n.classList].slice(0, 3).map(CSS.escape).join('.');
                  const p = n.parentElement;
                  if (p) {
                    const same = [...p.children].filter(v => v.tagName === n.tagName);
                    if (same.length > 1) part += `:nth-of-type(${'$'}{same.indexOf(n) + 1})`;
                  }
                  parts.unshift(part);
                  if (parts.length >= 6) break;
                }
                return parts.join(' > ');
              };
              const items = document.elementsFromPoint($x, $y).map((el) => {
                const r = el.getBoundingClientRect();
                const s = getComputedStyle(el);
                return {tag: el.tagName.toLowerCase(), id: el.id || '', className: String(el.className || ''),
                  text: (el.innerText || el.value || el.getAttribute('aria-label') || '').trim().slice(0, 200),
                  x: r.left, y: r.top, w: r.width, h: r.height, zIndex: s.zIndex,
                  pointerEvents: s.pointerEvents, selector: pickSelector(el)};
              });
              const payload = JSON.stringify({type:'elements_at_point', dpr: devicePixelRatio, x:$x, y:$y, url:location.href, items});
              if (window.cefriumQuery) window.cefriumQuery({request: payload, onSuccess(){}, onFailure(){}});
              return payload;
            })()
        """.trimIndent()
    }
}
