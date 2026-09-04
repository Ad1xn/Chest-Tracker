package dev.adrian.chesttracker.platform;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;

/**
 * The one place Fabric's networking API differs between the two targets.
 *
 * <p>Everything else used here - {@code ServerPlayNetworking.registerGlobal-
 * Receiver}/{@code send}/{@code canSend}, the client equivalents, both
 * {@code Context} types and the connection events - is identical on both, so
 * only the registry accessors are shimmed. Fabric API 6 renamed them from the
 * direction-suffixed form to the vanilla packet-direction wording:
 * {@code playC2S} became {@code serverboundPlay} and {@code playS2C} became
 * {@code clientboundPlay}.
 */
public final class NetworkCompat {

    private NetworkCompat() {}

    /** Payloads the client sends to the server. */
    public static PayloadTypeRegistry<RegistryFriendlyByteBuf> playC2S() {
        //? if >=26.1 {
        /*return PayloadTypeRegistry.serverboundPlay();
        *///?} else {
        return PayloadTypeRegistry.playC2S();
        //?}
    }

    /** Payloads the server sends to the client. */
    public static PayloadTypeRegistry<RegistryFriendlyByteBuf> playS2C() {
        //? if >=26.1 {
        /*return PayloadTypeRegistry.clientboundPlay();
        *///?} else {
        return PayloadTypeRegistry.playS2C();
        //?}
    }
}
