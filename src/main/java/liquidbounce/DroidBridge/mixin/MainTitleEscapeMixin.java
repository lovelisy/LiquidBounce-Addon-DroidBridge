package liquidbounce.DroidBridge.mixin;

import net.ccbluex.liquidbounce.integration.screen.CustomScreenType;
import net.ccbluex.liquidbounce.integration.screen.impl.CustomSharedMinecraftScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keep the replaced LiquidBounce title screen stable when Escape is pressed.
 *
 * The default Minecraft Screen handler closes CustomSharedMinecraftScreen on Escape. That
 * makes ScreenManager restore/re-open the title route and can cause the WebView buttons to
 * appear to reload repeatedly. Consume Escape before the default handler only for the title
 * route; other screens retain their normal Escape behavior.
 */
@Mixin(Screen.class)
public abstract class MainTitleEscapeMixin {

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void droidbridge$ignoreEscapeOnTitle(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (event == null || !event.isEscape()) {
            return;
        }

        Object screen = this;
        if (screen instanceof CustomSharedMinecraftScreen
                && ((CustomSharedMinecraftScreen) screen).getScreenType() == CustomScreenType.TITLE) {
            // Mark Escape as handled so neither onClose() nor parent navigation can run.
            cir.setReturnValue(true);
        }
    }
}
