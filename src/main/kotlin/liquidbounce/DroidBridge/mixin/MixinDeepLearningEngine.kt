package liquidbounce.DroidBridge.mixin

import net.ccbluex.liquidbounce.deeplearn.DeepLearningEngine
import net.ccbluex.liquidbounce.integration.task.type.Task
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import kotlin.coroutines.Continuation

@Mixin(value = DeepLearningEngine::class, remap = false)
class MixinDeepLearningEngine {

    @Inject(method = ["init"], at = [At("HEAD")], remap = false)
    private fun init(task: Task, continuation: Continuation<*>) {
        throw IllegalStateException("Deep learning is disabled on Android")
    }

}
