package dev.adrian.chesttracker.mixin;

import dev.adrian.chesttracker.server.Trackers;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Notices when a container's contents change.
 *
 * <p>{@code setChanged} is the canonical "my data changed" signal, so this one
 * hook covers a player moving items, a hopper, a furnace smelting, a crafter,
 * another player on a server, and datapack edits alike.
 *
 * <p>It only ever marks the position dirty. Re-reading here would be ruinous:
 * a single item sorter produces hundreds of these per tick. The dirty set
 * coalesces them and a bounded number are re-read per tick instead.
 */
@Mixin(BlockEntity.class)
public abstract class BlockEntityMixin {

    @Inject(method = "setChanged()V", at = @At("RETURN"))
    private void chesttracker$afterSetChanged(CallbackInfo ci) {
        Trackers.onContainerChanged((BlockEntity) (Object) this);
    }
}
