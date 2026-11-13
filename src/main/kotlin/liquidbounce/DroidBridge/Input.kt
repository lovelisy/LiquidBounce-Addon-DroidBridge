package liquidbounce.DroidBridge

import com.mojang.blaze3d.platform.InputConstants

internal object Input {

    fun mouseButton(button: Int) = when (button) {
        InputConstants.MOUSE_BUTTON_LEFT -> 0
        InputConstants.MOUSE_BUTTON_MIDDLE -> 1
        InputConstants.MOUSE_BUTTON_RIGHT -> 2
        else -> null
    }

}
