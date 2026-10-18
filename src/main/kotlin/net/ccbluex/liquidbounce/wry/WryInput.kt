package net.ccbluex.liquidbounce.wry

import com.mojang.blaze3d.platform.InputConstants
import com.mojang.blaze3d.platform.cursor.CursorType
import com.mojang.blaze3d.platform.cursor.CursorTypes

internal object WryInput {

    fun mouseButton(button: Int) = when (button) {
        InputConstants.MOUSE_BUTTON_LEFT -> 0
        InputConstants.MOUSE_BUTTON_MIDDLE -> 1
        InputConstants.MOUSE_BUTTON_RIGHT -> 2
        else -> null
    }

    /**
     * The cursor of the game for a CSS cursor.
     */
    fun cursor(cursor: String): CursorType = when (cursor) {
        "pointer" -> CursorTypes.POINTING_HAND
        "text", "vertical-text" -> CursorTypes.IBEAM
        "crosshair", "cell" -> CursorTypes.CROSSHAIR
        "ns-resize", "n-resize", "s-resize", "row-resize" -> CursorTypes.RESIZE_NS
        "ew-resize", "e-resize", "w-resize", "col-resize" -> CursorTypes.RESIZE_EW
        "move", "grab", "grabbing", "all-scroll" -> CursorTypes.RESIZE_ALL
        "not-allowed", "no-drop" -> CursorTypes.NOT_ALLOWED
        else -> CursorTypes.ARROW
    }

}
