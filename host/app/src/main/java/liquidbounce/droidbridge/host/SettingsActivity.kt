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

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import liquidbounce.droidbridge.host.cef.CefriumHostService
import liquidbounce.droidbridge.host.cef.HostSettings
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 插件主界面：引擎状态 + 开关。
 *
 * 插件不是纯数据载体 —— host（Cefrium + IPC 服务）就跑在本 APK 的 :cefhost 进程里，
 * 这样隔离子进程读的是本 APK 的 nativeLibraryDir，libcef.so 才能被 renderer 加载。
 */
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HostSettings.init(this)
        setContent {
            MiuixTheme {
                SettingsPage()
            }
        }
    }
}

@Composable
private fun SettingsPage() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scrollBehavior = MiuixScrollBehavior()
    var showHud by remember { mutableStateOf(false) }

    if (showHud) {
        HudPage(context, onBack = { showHud = false })
        return
    }
    var desktopUa by remember { mutableStateOf(HostSettings.desktopUserAgent) }
    var mediaGesture by remember { mutableStateOf(HostSettings.mediaPlaybackWithoutGesture) }

    val libDir = remember(context) {
        runCatching {
            context.packageManager.getApplicationInfo(context.packageName, 0).nativeLibraryDir
        }.getOrDefault("")
    }
    val cefFile = remember { java.io.File(libDir, "libcef.so") }
    val mcefFile = remember { java.io.File(libDir, "libmcef.so") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = "MCEF 引擎",
                scrollBehavior = scrollBehavior,
            )
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .overScrollVertical()
                .scrollEndHaptic()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(top = paddingValues.calculateTopPadding()),
            overscrollEffect = null,
        ) {
            item { Spacer(Modifier.size(12.dp)) }

            item { SmallTitle("引擎") }
            item {
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    InfoRow("Cefrium", "0.18.0")
                    HorizontalDivider(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                    InfoRow("Chromium", "156.0.8078.80")
                    HorizontalDivider(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                    InfoRow(
                        "libcef.so",
                        if (cefFile.isFile) "${cefFile.length() / 1024 / 1024} MB" else "缺失",
                    )
                    HorizontalDivider(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                    InfoRow(
                        "libmcef.so",
                        if (mcefFile.isFile) "${mcefFile.length() / 1024} KB" else "缺失",
                    )
                }
            }

            item { SmallTitle("行为") }
            item {
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    SwitchPreference(
                        title = "桌面化身份",
                        summary = "伪装成 Windows Chrome，避免站点跳转到移动版",
                        checked = desktopUa,
                        onCheckedChange = {
                            desktopUa = it
                            HostSettings.desktopUserAgent = it
                        },
                    )
                    HorizontalDivider(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                    SwitchPreference(
                        title = "允许自动播放",
                        summary = "网页视频不要求先点一下",
                        checked = mediaGesture,
                        onCheckedChange = {
                            mediaGesture = it
                            HostSettings.mediaPlaybackWithoutGesture = it
                        },
                    )
                }
            }

            item { SmallTitle("服务") }
            item {
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    // 启动 host：点一下即可，前台服务保活。
                    // MC 侧 libmcef.so 连 127.0.0.1:39090，服务不在时浏览器起不来。
                    BasicComponent(
                        title = "运行状态",
                        summary = "帧计数与最近日志",
                        onClick = { showHud = true },
                    )
                    HorizontalDivider(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                    BasicComponent(
                        title = "启动承载进程",
                        summary = "监听 127.0.0.1:39090，供 Minecraft 连接",
                        onClick = {
                            val ctx = context
                            val intent = Intent(ctx, CefriumHostService::class.java)
                            runCatching {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    ctx.startForegroundService(intent)
                                } else {
                                    ctx.startService(intent)
                                }
                            }
                        },
                    )
                }
            }

            item { Spacer(Modifier.size(24.dp)) }
        }
    }
}

@Composable
private fun InfoRow(title: String, value: String) {
    BasicComponent(
        title = title,
        summary = value,
    )
}
