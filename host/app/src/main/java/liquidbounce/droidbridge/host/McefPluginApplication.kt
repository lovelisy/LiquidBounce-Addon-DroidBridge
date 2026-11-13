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

package liquidbounce.droidbridge.host

import android.app.Application
import android.content.Context
import com.cefrium.Cefrium
import org.chromium.base.ApplicationStatus

/**
 * 插件 Application。
 *
 * **存在的唯一目的是在 host Activity 起来之前预置 Chromium 的进程级状态。**
 *
 * 反汇编 Cefrium/ApplicationStatus 确认：`ApplicationStatus` 的 Window.Callback
 * 代理只在 Activity 进入 CREATED 时安装，而 onPause / onStop / onDestroy 等
 * 都用一句断言校验"它装过"。没装就抛：
 *
 *     java.lang.AssertionError
 *       at org.chromium.base.ApplicationStatus$2.onStateChanged(ApplicationStatus.java:342)
 *
 * 表现为 host Activity 每次 pause/stop 都 SWALLOWED 一条断言，CEF 状态机错乱。
 *
 * 用 SDK 自己的 `Cefrium.setupProcess()` 而不是手抄它内部那五项：
 * 手抄踩过两次（顺序错、只做一半），setupProcess 是 public 且幂等的，
 * 顺序与只做一次都由 SDK 负责。
 */
class McefPluginApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        CefriumHostLog.currentAppContext = this

        // 只在承载进程里预置 —— 子进程（renderer）走自己的初始化路径。
        // 进程名用点号形式（liquidbounce.droidbridge.host.cefhost）：
        // Cefrium.initialize 遇到带冒号的进程名会直接 return false。
        val pn = runCatching { Application.getProcessName() }
            .getOrElse { runCatching { java.io.File("/proc/self/cmdline").readText().trimEnd('\u0000') }.getOrDefault("") }

        if (pn.endsWith(".cefhost")) {
            runCatching {
                Cefrium.setupProcess(this)
                CefriumHostLog.mirror(
                    "McefPluginApplication: 已在 startHost 之前 setupProcess; " +
                        "ApplicationStatus.isInitialized=${ApplicationStatus.isInitialized()}; proc=$pn"
                )
            }.onFailure {
                CefriumHostLog.mirror(
                    "McefPluginApplication: setupProcess 失败: ${it.javaClass.name}: ${it.message}"
                )
            }
        }
    }
}

/**
 * 极简日志镜像：Application 阶段 host 还没起来，用不了它的日志设施。
 * 直接写应用外部目录，失败就算了 —— 这只是诊断，不能影响启动。
 */
object CefriumHostLog {
    fun mirror(line: String) {
        runCatching {
            val ctx = currentAppContext ?: return@runCatching
            val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
            if (!dir.exists() && !dir.mkdirs()) return@runCatching
            java.io.File(dir, "mcef-cefrium.log").appendText(line + "\n")
        }
    }

    @Volatile
    var currentAppContext: Context? = null
}
