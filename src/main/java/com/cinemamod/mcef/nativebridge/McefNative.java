/*
 * MCEF-Android (Forge 1.20.1)
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

package com.cinemamod.mcef.nativebridge;

import java.nio.ByteBuffer;

/**
 * The JNI binding of libmcef.so.
 *
 * The class name is part of the interface: libmcef.so registers its natives with
 * JNI_OnLoad and looks this class up by name.
 */
public final class McefNative {

    private McefNative() {
    }

    public static final int OK = 0;
    public static final int ERR_NOT_INITIALIZED = -1;
    public static final int ERR_INVALID_ARGUMENT = -2;
    public static final int ERR_NOT_FOUND = -3;
    public static final int ERR_BUFFER_TOO_SMALL = -4;
    public static final int ERR_CLOSED = -5;
    public static final int ERR_UNSUPPORTED = -6;
    public static final int ERR_INTERNAL = -7;

    public static final int INPUT_MOUSE_MOVE = 0;
    public static final int INPUT_MOUSE_DOWN = 1;
    public static final int INPUT_MOUSE_UP = 2;
    public static final int INPUT_MOUSE_WHEEL = 3;
    public static final int INPUT_KEY_DOWN = 4;
    public static final int INPUT_KEY_UP = 5;
    public static final int INPUT_CHAR = 6;

    private static volatile String loadedFrom = null;
    private static volatile boolean loaded = false;

    /**
     * Loads the library once. A null or empty path uses System.loadLibrary.
     */
    public static synchronized void load(String absolutePath) {
        if (loaded) {
            return;
        }
        if (absolutePath == null || absolutePath.isEmpty()) {
            System.loadLibrary("mcef");
            loadedFrom = "java.library.path";
        } else {
            System.load(absolutePath);
            loadedFrom = absolutePath;
        }
        loaded = true;
    }

    public static String getLoadedFrom() {
        return loadedFrom;
    }

    public static native int init(String runtimeDir, String cacheDir, String cefLibraryPath,
                                  boolean enableIpc, String ipcEndpoint);

    public static native void shutdown();

    public static native boolean isInitialized();

    public static native long createBrowser(String url, int width, int height, float scale,
                                           boolean transparent);

    public static native int destroyBrowser(long browser);

    public static native int resize(long browser, int width, int height);

    public static native int loadUrl(long browser, String url);

    public static native int evaluateJavaScript(long browser, String script);

    public static native int requestDomSnapshot(long browser);

    public static native int requestElementsAtPoint(long browser, int x, int y);

    public static native int clickDom(long browser, String selector);

    public static native int sendInput(long browser, int type, int x, int y, int button,
                                       int keyCode, int scanCode, int modifiers,
                                       double deltaX, double deltaY, int codepoint);

    public static native int[] getFrameInfo(long browser);

    public static native int copyFrame(long browser, ByteBuffer directBuffer);

    public static native String getDomJson(long browser);

    public static native String getUrl(long browser);

    public static native String getLastError();

    public static native int hostSetFrame(long browser, ByteBuffer directBuffer,
                                          int width, int height, int stride);

    public static native int hostSetDomJson(long browser, String json);

    public static native String[] takeQuery(long browser);

    public static native int replyQuery(long browser, long queryId, boolean success,
                                        String result, int errorCode);
}
