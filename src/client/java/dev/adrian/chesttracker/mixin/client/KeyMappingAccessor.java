package dev.adrian.chesttracker.mixin.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The key a binding is currently on.
 *
 * <p>{@code KeyMapping} exposes {@code getDefaultKey()} but not the bound one,
 * and the default is no use to anything that has to honour a rebind. The field
 * is protected, so reading it needs this.
 *
 * <p>Wanted because a key pressed while a screen is open never reaches
 * {@code consumeClick()} - vanilla only queues those when no screen is up - so
 * the in-container hotkey has to ask the window directly whether the key is
 * held, and that means knowing which key to ask about.
 */
@Mixin(KeyMapping.class)
public interface KeyMappingAccessor {

    @Accessor("key")
    InputConstants.Key chesttracker$key();
}
