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

plugins {
    id("com.android.application")
    // 展开 AAR 里的 org.chromium.*.R 常量 + 保证引擎 assets 不压缩
    id("com.cefrium")
    // AGP 9 内建 Kotlin，不要显式加 kotlin.android
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "liquidbounce.droidbridge.host"
    compileSdk = 36

    defaultConfig {
        applicationId = "liquidbounce.droidbridge.host"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.19.0"

        ndk {
            // 只打 arm64：libcef.so 一个架构就 209MB
            abiFilters += listOf("arm64-v8a")
        }
    }

    // libcef.so 必须以解压形式放进 nativeLibraryDir：
    //  1. Chromium 的 LibraryLoader 走系统路径加载；
    //  2. 隔离子进程（:sandboxed_processN）只能读本 APK 的 nativeLibraryDir ——
    //     这也是 host 必须住在插件里的原因。
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// miuix 0.9.4 的 Kotlin 元数据是 2.4，AGP 9.2 内建编译器是 2.2。
// 这些是二进制库（已编译），元数据只参与签名解析，放行不影响运行。
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}

dependencies {
    // Cefrium 引擎（AAR 里带 libcef.so，随本插件一起打包 —— 这正是关键：
    // 隔离子进程读的是插件自己的 nativeLibraryDir）
    implementation("com.cefrium:cefrium-sdk-core:0.19.0")
    implementation("com.cefrium:cefrium-native-libre-arm64-v8a:0.19.0")

    val composeBom = platform("androidx.compose:compose-bom:2026.08.00")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // Miuix：HyperOS 风格 UI
    implementation("top.yukonga.miuix.kmp:miuix-ui:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-icons:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-preference:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-squircle:0.9.4")
}
