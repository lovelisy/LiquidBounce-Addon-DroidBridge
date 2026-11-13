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

import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import liquidbounce.droidbridge.host.cef.CefriumHostService
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * HUD：引擎运行状态 + 日志预览。
 *
 * 数据来源两个：
 *   1. CefriumHostService.Companion.currentSnapshot —— 由心跳每 2 秒刷新，
 *      含 paints / frames / 浏览器数 / 连接数；
 *   2. 日志文件 —— 直接读最后 N 行，用于看最近发生了什么。
 *
 * 轮询而不是监听：host 跑在独立进程（liquidbounce.droidbridge.host.cefhost），
 * 跨进程推送要 Binder；而这是诊断界面，2 秒粒度足够。
 */
@Composable
fun HudPage(context: Context, onBack: () -> Unit) {
    val scrollBehavior = MiuixScrollBehavior()
    var snapshot by remember { mutableStateOf(CefriumHostService.currentSnapshot) }
    var logLines by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(Unit) {
        while (true) {
            snapshot = CefriumHostService.currentSnapshot
            logLines = readLogTail(context, 40)
            delay(2000)
        }
    }

    val s = snapshot
    Scaffold(
        topBar = {
            TopAppBar(
                title = "运行状态",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(MiuixIcons.Back, contentDescription = "返回")
                    }
                },
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
                    StatRow("服务", if (s?.running == true) "运行中" else "未启动")
                    HorizontalDivider(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                    StatRow("CEF", if (s?.cefReady == true) "已就绪" else "初始化中")
                    HorizontalDivider(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                    StatRow("页面", "${s?.browsers ?: 0} 个")
                    HorizontalDivider(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                    StatRow("MC 连接", if ((s?.clients ?: 0) > 0) "已连接" else "无")
                }
            }

            item { SmallTitle("帧") }
            item {
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    StatRow("OnPaint", "${s?.paints ?: 0} 次")
                    HorizontalDivider(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                    StatRow("已发送帧", "${s?.framesSent ?: 0} 帧")
                    HorizontalDivider(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                    StatRow(
                        "状态",
                        when {
                            s == null -> "无数据"
                            s.framesSent > 0 -> "正常"
                            s.paints > 0 -> "有画面但未送达"
                            else -> "等待首帧"
                        },
                    )
                }
            }

            item { SmallTitle("最近日志") }
            item {
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    if (logLines.isEmpty()) {
                        StatRow("日志", "暂无内容")
                    } else {
                        logLines.forEachIndexed { i, line ->
                            if (i > 0) {
                                HorizontalDivider(
                                    Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                                )
                            }
                            Text(
                                text = line,
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }

            item { Spacer(Modifier.size(24.dp)) }
        }
    }
}

@Composable
private fun StatRow(title: String, value: String) {
    BasicComponent(
        title = title,
        summary = value,
    )
}

/** 读日志尾部 n 行；文件不存在返回空。 */
private fun readLogTail(context: Context, n: Int): List<String> {
    return runCatching {
        val f = CefriumHostService.logFileFor(context)
        if (!f.isFile) return emptyList()
        // 只读最后 64KB，避免日志涨到几 MB 时每 2 秒全量读。
        val len = f.length()
        val start = maxOf(0L, len - 64 * 1024)
        java.io.RandomAccessFile(f, "r").use { raf ->
            raf.seek(start)
            val bytes = ByteArray((len - start).toInt())
            raf.readFully(bytes)
            String(bytes, Charsets.UTF_8)
                .lineSequence()
                .filter { it.isNotBlank() }
                .toList()
                .takeLast(n)
                // 去掉毫秒时间戳前缀，界面更干净
                .map { line -> line.replace(Regex("^\\d{13} "), "") }
        }
    }.getOrDefault(emptyList())
}
