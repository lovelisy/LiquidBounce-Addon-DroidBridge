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

package liquidbounce.droidbridge.host.cef

import android.app.Activity
import android.os.Build
import androidx.core.view.WindowCompat
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager

/**
 * 透明 Activity，运行在 .cefhost 进程。
 *
 * 作用：给 CEF 提供 Window/ViewRootImpl 上下文。
 * CEF 的 Android 消息泵挂在主 Looper 的 native fd 回调上；回调里会访问
 * View/Window 对象。在 Service 里这些是 null → SIGSEGV。
 * 这个 Activity 用 setContentView 创建一个不可见 View，让 ViewRootImpl 存在。
 *
 * 生命周期注意：cefhost 进程只有这一个 Activity。游戏在前台时系统必然 pause 它，
 * 这是常态而不是异常。super.onPause()/onStop() 内部是
 * `mCalled = false → ...做活... → mCalled = true`，框架在返回后检查该标记，
 * 发现 false 就抛 SuperNotCalledException。
 *
 * 所以 super 中途抛异常（Chromium 的 ApplicationStatus 状态断言）时，catch 里
 * 必须把 mCalled 补回去，否则吞异常只是把原始异常换成一个 SuperNotCalledException，
 * 一样崩，而且崩点更迷惑。见 guardLifecycle / restoreCalled。
 */
class CefriumHostActivity : Activity() {

    private val TAG = "MCEF/CefriumHost"

    override fun onCreate(savedInstanceState: Bundle?) {
        // 在 super 之前落一条，用来区分两种当时无法区分的失败：
        //   有本行、无 ready → Activity 建起来了，是 ready 回调被丢弃（Service 实例为 null）
        //   连本行都没有     → Activity 压根没被创建（后台启动 Activity 被系统拦截）
        // mcef-cefrium.log 里 pid=455 只有 "started CefriumHostActivity" 而没有 ready，
        // 没有任何线索能判断属于哪种，CEF 于是永不初始化、create 无限 deferred。
        CefriumHostService.noteHostActivityCreated()
        super.onCreate(savedInstanceState)
        setContentView(View(this))
        window.setFlags(
            // 这个 Activity 只提供窗口上下文给 CEF 初始化，本身不显示任何东西，
            // 也不该抢焦点或吃掉触摸。
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        )

        // 之前这里还有 FLAG_LAYOUT_NO_LIMITS，它让窗口延伸到状态栏和刘海下面。
        // 后果有两条：CEF 用来算视口的窗口尺寸包含了系统栏区域，页面底部被
        // 导航栏压住、顶部被刘海切掉；同时状态栏一直悬在画面上方。
        // 去掉之后系统会正常扣掉 insets。
        //
        // 顺便按 Android 15+ 的建议显式声明 inset 行为：默认 edge-to-edge 下
        // 必须自己处理 insets，否则内容照样会被系统栏盖住。
        WindowCompat.setDecorFitsSystemWindows(window, true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // 刘海区域不做特殊延伸，交给系统按默认安全区裁切。
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
            }
        }

        CefriumHostService.onHostActivityReady(this)
    }

    /**
     * 生命周期守卫：super 抛异常时恢复框架契约并留下真实异常。
     *
     * 旧实现是 `catch (Throwable) { Log.w }`，只留类名和消息、不带堆栈，而且 ZLApplication
     * 用 Thread.setDefaultUncaughtExceptionHandler 替换了系统默认处理器，logcat 又抓不到，
     * 结果原始异常被永久销毁，崩溃报告里只剩 SuperNotCalledException。这里同步落文件。
     */
    private fun guardLifecycle(name: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            // 顺序要紧：原始异常是不可再生数据，恢复动作是尽力而为。
            // 先 restore 再落盘的话，restoreCalled 里任何意外都会让真因再次丢失
            // ——而"真因被销毁"正是这次要修的毛病，不能在新代码里重演。
            recordSwallowed(name, t)
            val restored = restoreCalled()
            Log.e(TAG, "$name: super 抛异常，mCalled 已补=$restored", t)
        }
    }

    /**
     * 把 Activity.mCalled 补回 true，让框架的 pauseCalled/resumeCalled 检查通过。
     *
     * 这是恢复框架契约的唯一途径——super 抛异常意味着 super 没执行完，
     * 没有别的信号能告诉框架"我已经调过 super 了"。
     * 失败时（Android 9+ 隐藏 API 限制可能挡住 setAccessible）框架契约救不回来，
     * 崩溃不可避免，但 recordSwallowed 已经把真实异常写进文件了。
     * 两种结果都比原来强。
     */
    private fun restoreCalled(): Boolean =
        try {
            val f = Activity::class.java.getDeclaredField("mCalled")
            f.isAccessible = true
            f.setBoolean(this, true)
            true
        } catch (t: Throwable) {
            // Android 9+ 的隐藏 API 限制可能挡住 setAccessible。挡不住就如实返回 false：
            // 崩溃不可避免，但真实异常已经落盘，"救不回来"这个结论本身也是有效信息。
            Log.e(TAG, "restoreCalled 失败，框架契约无法恢复: ${t.javaClass.name}: ${t.message}", t)
            false
        }

    /**
     * 同步写文件。mcef-cefrium.log 走异步队列，崩溃时可能丢；这里直接同步落盘。
     * 崩溃报告处理器是 Thread.setDefaultUncaughtExceptionHandler（替换式），
     * 拿不到这条，所以必须自己记。
     *
     * 同时镜像一份到公共目录：应用私有目录（Android/data/…）被 scoped storage 挡着，
     * 没有特权通道读不到 —— 而这条恰恰是原始异常的唯一记录，最不能取不到。
     */
    private fun recordSwallowed(name: String, t: Throwable) {
        runCatching {
            val f = java.io.File(filesDir, "mcef-swallowed.log")
            val sw = java.io.StringWriter()
            t.printStackTrace(java.io.PrintWriter(sw))
            val text = "${System.currentTimeMillis()} ${android.os.Process.myPid()} $name " +
                "${t.javaClass.name}: ${t.message}\n" +
                sw.toString() +
                "\n"
            f.appendText(text)
            // 私有目录那份可能取不到，镜像到公共目录兜底。
            CefriumHostService.appendPublicMirror("SWALLOWED $name: ${t.javaClass.name}: ${t.message}\n$sw")
        }
    }

    // 生命周期日志。此前这块完全没有，导致"崩溃前 Activity 处于什么状态"只能靠猜。
    // 2026-10-05 19:12 那次运行证明了它的必要性：Activity 在浏览器出生前 48 秒就被 pause，
    // 此后整场再没恢复过 —— 浏览器是在"宿主已 pause"的状态下活完一生的。
    // 现在最需要知道的变成：onStop 有没有发生（Window 是否被撤走）。那是最后一个没测的变量。
    //
    // 每条都在 super 之前落盘：super 中途抛异常时，至少还知道这个回调进来过。
    override fun onStart() = guardLifecycle("onStart") {
        CefriumHostService.noteHostLifecycle("onStart")
        super.onStart()
    }

    override fun onResume() = guardLifecycle("onResume") {
        CefriumHostService.noteHostLifecycle("onResume")
        super.onResume()
    }

    override fun onPause() = guardLifecycle("onPause") {
        CefriumHostService.noteHostLifecycle("onPause")
        super.onPause()
    }

    override fun onStop() = guardLifecycle("onStop") {
        CefriumHostService.noteHostLifecycle("onStop")
        super.onStop()
    }

    override fun onRestart() = guardLifecycle("onRestart") {
        CefriumHostService.noteHostLifecycle("onRestart")
        super.onRestart()
    }

    /** FLAG_NOT_FOCUSABLE 的窗口拿不到输入焦点，焦点事件本身就能反推窗口状态。 */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        CefriumHostService.noteHostLifecycle("onWindowFocusChanged($hasFocus)")
        super.onWindowFocusChanged(hasFocus)
    }

    override fun onDestroy() {
        CefriumHostService.onHostActivityDestroyed()
        super.onDestroy()
    }
}
