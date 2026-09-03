package dev.adrian.chesttracker.mixin;

import dev.adrian.chesttracker.server.Trackers;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Removes containers from the index the moment they stop existing.
 *
 * <p>Hooked at {@code setBlock} rather than on a break event because every
 * block change funnels through here: creepers, TNT, pistons, fire and
 * worldedit-style bulk changes included. Injecting at RETURN means the world
 * already reflects the change, so the hook can simply ask what is there now.
 */
@Mixin(Level.class)
public abstract class LevelMixin {

    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("RETURN"))
    private void chesttracker$afterSetBlock(BlockPos pos, BlockState state, int flags, int recursionLeft,
                                            CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()) return; // The change did not take effect.
        Trackers.onBlockChanged((Level) (Object) this, pos);
    }
}
