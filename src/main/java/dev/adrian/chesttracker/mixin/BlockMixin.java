package dev.adrian.chesttracker.mixin;

import dev.adrian.chesttracker.server.Trackers;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Attributes a container to the player who placed it.
 *
 * <p>Observed placement is the strongest origin signal there is: it beats a
 * structure bounding box, so a chest a player puts down inside a village is
 * correctly theirs rather than "natural".
 */
@Mixin(Block.class)
public abstract class BlockMixin {

    @Inject(method = "setPlacedBy", at = @At("RETURN"))
    private void chesttracker$afterPlaced(Level level, BlockPos pos, BlockState state,
                                          LivingEntity placer, ItemStack stack, CallbackInfo ci) {
        Trackers.onBlockPlaced(level, pos, placer);
    }
}
