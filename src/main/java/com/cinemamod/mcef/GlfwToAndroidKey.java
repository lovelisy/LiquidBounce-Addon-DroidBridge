/*
 * MCEF for Android —— GLFW 到 Android 键码转换
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

package com.cinemamod.mcef;

/**
 * GLFW 键码 → Android KeyEvent 键码。
 *
 * <p>Minecraft 在 Android 上跑的是桌面版代码，按键事件用的是 GLFW 键码
 * （{@code GLFW_KEY_A = 65}、{@code GLFW_KEY_ENTER = 257} …）。
 * 而 Cefrium 的 {@code sendKeyEvent} 要的是 Android
 * {@code KeyEvent.KEYCODE_*}（{@code KEYCODE_A = 29}、
 * {@code KEYCODE_ENTER = 66} …）。两套编号毫无关系，直接透传等于乱按。</p>
 *
 * <p>这里只映射浏览器里真正会用到的键：字母、数字、方向、编辑、功能键。
 * 未收录的返回 0 —— 调用方应当跳过，不要拿 0 当键码发出去
 * （{@code KEYCODE_UNKNOWN} 会让页面收到一堆无意义的 keydown）。</p>
 *
 * <p>数值取自 Android {@code android.view.KeyEvent} 的公开常量与
 * GLFW 的 {@code GLFW_KEY_*}，两边都是 ABI 稳定的整数，可以硬编码。</p>
 */
public final class GlfwToAndroidKey {

    private GlfwToAndroidKey() {
    }

    // GLFW 键码常量（只列出本表用到的）
    private static final int GLFW_APOSTROPHE = 39;
    private static final int GLFW_COMMA = 44;
    private static final int GLFW_MINUS = 45;
    private static final int GLFW_PERIOD = 46;
    private static final int GLFW_SLASH = 47;
    private static final int GLFW_0 = 48;
    private static final int GLFW_9 = 57;
    private static final int GLFW_SEMICOLON = 59;
    private static final int GLFW_EQUAL = 61;
    private static final int GLFW_A = 65;
    private static final int GLFW_Z = 90;
    private static final int GLFW_LEFT_BRACKET = 91;
    private static final int GLFW_BACKSLASH = 92;
    private static final int GLFW_RIGHT_BRACKET = 93;
    private static final int GLFW_GRAVE = 96;
    private static final int GLFW_ESCAPE = 256;
    private static final int GLFW_ENTER = 257;
    private static final int GLFW_TAB = 258;
    private static final int GLFW_BACKSPACE = 259;
    private static final int GLFW_INSERT = 260;
    private static final int GLFW_DELETE = 261;
    private static final int GLFW_RIGHT = 262;
    private static final int GLFW_LEFT = 263;
    private static final int GLFW_DOWN = 264;
    private static final int GLFW_UP = 265;
    private static final int GLFW_PAGE_UP = 266;
    private static final int GLFW_PAGE_DOWN = 267;
    private static final int GLFW_HOME = 268;
    private static final int GLFW_END = 269;
    private static final int GLFW_CAPS_LOCK = 280;
    private static final int GLFW_F1 = 290;
    private static final int GLFW_F12 = 301;
    private static final int GLFW_KP_0 = 320;
    private static final int GLFW_KP_9 = 329;
    private static final int GLFW_KP_DECIMAL = 330;
    private static final int GLFW_KP_DIVIDE = 331;
    private static final int GLFW_KP_MULTIPLY = 332;
    private static final int GLFW_KP_SUBTRACT = 333;
    private static final int GLFW_KP_ADD = 334;
    private static final int GLFW_KP_ENTER = 335;
    private static final int GLFW_KP_EQUAL = 336;
    private static final int GLFW_LEFT_SHIFT = 340;
    private static final int GLFW_LEFT_CONTROL = 341;
    private static final int GLFW_LEFT_ALT = 342;
    private static final int GLFW_LEFT_SUPER = 343;
    private static final int GLFW_RIGHT_SHIFT = 344;
    private static final int GLFW_RIGHT_CONTROL = 345;
    private static final int GLFW_RIGHT_ALT = 346;
    private static final int GLFW_RIGHT_SUPER = 347;

    /**
     * 转换。返回 0 表示未收录，调用方应跳过。
     *
     * @param glfwKey GLFW 键码
     * @return Android KeyEvent 键码，或 0
     */
    public static int map(int glfwKey) {
        // 数字 0-9：GLFW 48..57 → Android KEYCODE_0=7 .. KEYCODE_9=16
        if (glfwKey >= GLFW_0 && glfwKey <= GLFW_9) {
            return 7 + (glfwKey - GLFW_0);
        }
        // 字母 A-Z：GLFW 65..90 → Android KEYCODE_A=29 .. KEYCODE_Z=54
        if (glfwKey >= GLFW_A && glfwKey <= GLFW_Z) {
            return 29 + (glfwKey - GLFW_A);
        }
        // 小键盘 0-9：GLFW 320..329 → Android KEYCODE_NUMPAD_0=144 .. 153
        if (glfwKey >= GLFW_KP_0 && glfwKey <= GLFW_KP_9) {
            return 144 + (glfwKey - GLFW_KP_0);
        }
        // 功能键 F1-F12：GLFW 290..301 → Android KEYCODE_F1=131 .. 142
        if (glfwKey >= GLFW_F1 && glfwKey <= GLFW_F12) {
            return 131 + (glfwKey - GLFW_F1);
        }

        switch (glfwKey) {
            case GLFW_ESCAPE: return 111;        // KEYCODE_ESCAPE
            case GLFW_ENTER: return 66;          // KEYCODE_ENTER
            case GLFW_KP_ENTER: return 160;      // KEYCODE_NUMPAD_ENTER
            case GLFW_TAB: return 61;            // KEYCODE_TAB
            case GLFW_BACKSPACE: return 67;      // KEYCODE_DEL
            case GLFW_INSERT: return 124;        // KEYCODE_INSERT
            case GLFW_DELETE: return 112;        // KEYCODE_FORWARD_DEL
            case GLFW_RIGHT: return 22;          // KEYCODE_DPAD_RIGHT
            case GLFW_LEFT: return 21;           // KEYCODE_DPAD_LEFT
            case GLFW_DOWN: return 20;           // KEYCODE_DPAD_DOWN
            case GLFW_UP: return 19;             // KEYCODE_DPAD_UP
            case GLFW_PAGE_UP: return 92;        // KEYCODE_PAGE_UP
            case GLFW_PAGE_DOWN: return 93;      // KEYCODE_PAGE_DOWN
            case GLFW_HOME: return 122;          // KEYCODE_MOVE_HOME
            case GLFW_END: return 123;           // KEYCODE_MOVE_END
            case GLFW_CAPS_LOCK: return 115;     // KEYCODE_CAPS_LOCK
            case GLFW_APOSTROPHE: return 75;     // KEYCODE_APOSTROPHE
            case GLFW_COMMA: return 55;          // KEYCODE_COMMA
            case GLFW_MINUS: return 69;          // KEYCODE_MINUS
            case GLFW_PERIOD: return 56;         // KEYCODE_PERIOD
            case GLFW_SLASH: return 76;          // KEYCODE_SLASH
            case GLFW_SEMICOLON: return 74;      // KEYCODE_SEMICOLON
            case GLFW_EQUAL: return 70;          // KEYCODE_EQUALS
            case GLFW_LEFT_BRACKET: return 71;   // KEYCODE_LEFT_BRACKET
            case GLFW_BACKSLASH: return 73;      // KEYCODE_BACKSLASH
            case GLFW_RIGHT_BRACKET: return 72;  // KEYCODE_RIGHT_BRACKET
            case GLFW_GRAVE: return 68;          // KEYCODE_GRAVE
            case GLFW_KP_DECIMAL: return 158;    // KEYCODE_NUMPAD_DOT
            case GLFW_KP_DIVIDE: return 154;     // KEYCODE_NUMPAD_DIVIDE
            case GLFW_KP_MULTIPLY: return 155;   // KEYCODE_NUMPAD_MULTIPLY
            case GLFW_KP_SUBTRACT: return 156;   // KEYCODE_NUMPAD_SUBTRACT
            case GLFW_KP_ADD: return 157;        // KEYCODE_NUMPAD_ADD
            case GLFW_KP_EQUAL: return 161;      // KEYCODE_NUMPAD_EQUALS
            case GLFW_LEFT_SHIFT: return 59;     // KEYCODE_SHIFT_LEFT
            case GLFW_RIGHT_SHIFT: return 60;    // KEYCODE_SHIFT_RIGHT
            case GLFW_LEFT_CONTROL: return 113;  // KEYCODE_CTRL_LEFT
            case GLFW_RIGHT_CONTROL: return 114; // KEYCODE_CTRL_RIGHT
            case GLFW_LEFT_ALT: return 57;       // KEYCODE_ALT_LEFT
            case GLFW_RIGHT_ALT: return 58;      // KEYCODE_ALT_RIGHT
            case GLFW_LEFT_SUPER: return 117;    // KEYCODE_META_LEFT
            case GLFW_RIGHT_SUPER: return 118;   // KEYCODE_META_RIGHT
            default: return 0;
        }
    }
}
