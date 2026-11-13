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

// AGP 由 buildSrc 的 dependencies 带到 classpath，这里不声明版本。
// 注意：AGP 9.2 内建 Kotlin 2.2，而 miuix 0.9.4 用 Kotlin 2.4 编译 →
// 元数据版本不兼容。buildSrc 带 AGP 9.2 时无法升级内建 Kotlin，
// 因此用 XskipMetadataVersionCheck 放行（miuix 只是库，元数据仅影响签名解析）。
plugins {
    id("com.android.application") apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
}
