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

package com.cefrium

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Cefrium Gradle Plugin（AGP 9 版）。
 *
 * Chromium 的 Java 代码运行时引用 org.chromium.*.R 等包的资源常量
 * （例如 org.chromium.ui.R$integer）。标准 AAR 只为 AAR 自身包生成 R，
 * 这里用 variant.sources.java.addGeneratedSourceDirectory（AGP 9 的
 * 正式生成源码通道，javac/KSP 输入与任务顺序由 AGP 自动接线）
 * 把 runtime R.txt 展开成每个包的 R.java。
 */
class CefriumPlugin implements Plugin<Project> {

    // Chromium 运行时真正引用到的 R 包（保持最小集，避免 D8 OOM）。
    static final CHROMIUM_R_PACKAGES = [
        'gen.base_module',
        'org.chromium.base',
        'org.chromium.chrome',
        'org.chromium.chrome.browser.notifications',
        'com.google.android.gms.common',
        'org.chromium.components.browser_ui.styles',
        'org.chromium.components.browser_ui.widget',
        'org.chromium.components.browser_ui.contacts_picker',
        'org.chromium.components.embedder_support.delegate',
        'org.chromium.chrome.browser.ui.android.webid',
        'org.chromium.components.infobars',
        'org.chromium.components.input',
        'org.chromium.components.permissions',
        'org.chromium.components.webapps',
        'org.chromium.content',
        'org.chromium.media',
        'org.chromium.ui',
    ]

    void apply(Project project) {
        // Chromium 通过 mmap/文件描述符读取这些 assets，必须未压缩。
        //
        // 第四个后缀 '+com.cefrium+' 是 0.18.0 起的命名空间标记：引擎资产在 AAR 里
        // 叫 icudtl.dat+com.cefrium+ 这种形式，避免与宿主 App 自己的 assets 撞名。
        // 少了这一项，.dat/.pak 会因为文件名以 '+com.cefrium+' 结尾而匹配不上
        // noCompress，被打进 APK 时压缩成 Defl:N，运行时 Cefrium.initialize 报
        // "engine assets are missing or compressed in this APK"。
        project.android.androidResources {
            noCompress 'dat', 'pak', 'bin', '+com.cefrium+'
        }

        def packagesCsv = CHROMIUM_R_PACKAGES.join(',')
        def androidComponents = project.extensions.getByName('androidComponents')

        // Groovy 无 Kotlin 默认参数，onVariants 必须显式传 selector
        androidComponents.onVariants(androidComponents.selector().all()) { variant ->
            def vname = variant.name
            def cap = vname.substring(0, 1).toUpperCase() + vname.substring(1)

            def taskProvider = project.tasks.register(
                "generateCefriumR${cap}", GenerateCefriumRTask) { t ->
                t.variantName.set(vname)
                t.packageList.set(packagesCsv)
                t.outDir.set(project.layout.buildDirectory.dir("generated/cefrium_r/${vname}"))
                t.outputs.upToDateWhen { false } // 输入是 AGP 中间产物，不缓存
                t.dependsOn("process${cap}Resources")
            }

            def javaSources = variant.sources?.java
            if (javaSources != null) {
                javaSources.addGeneratedSourceDirectory(
                    taskProvider, GenerateCefriumRTask::getOutDir)
                project.logger.info(
                    "Cefrium: variant ${vname} wired via sources.java")
            } else {
                // 兜底：main sourceSet + 显式编译任务依赖（旧 AGP）
                project.android.sourceSets.getByName('main').java.srcDir(
                    { new File(project.layout.buildDirectory.get().asFile,
                               "generated/cefrium_r/${vname}") })
                project.tasks.configureEach { t ->
                    if (t.name == "compile${cap}JavaWithJavac" || t.name == "compile${cap}Kotlin"
                        || t.name == "ksp${cap}Kotlin") {
                        t.dependsOn(taskProvider)
                    }
                }
                project.logger.warn(
                    "Cefrium: variant ${vname} sources.java unavailable; used sourceSet fallback")
            }
        }
    }
}
