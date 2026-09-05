package dev.adrian.chesttracker.client.platform;

import dev.adrian.chesttracker.client.highlight.ContainerHighlight;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.phys.Vec3;

/**
 * Registers the in-world highlight against whichever world-render API this
 * target has.
 *
 * <p>This is the largest genuine divergence in the mod, and the one the handoff
 * expected to be worst. Fabric's world-render events were rewritten for the
 * deferred renderer:
 *
 * <ul>
 *   <li>1.21.11 has {@code rendering.v1.world.WorldRenderEvents}, whose context
 *       hands out a {@code MultiBufferSource} to draw into directly.
 *   <li>26.2 has {@code rendering.v1.level.LevelRenderEvents} - a different
 *       package, different names, and no buffer source at all. Geometry is
 *       submitted as a node to be drawn later, in its own phase.
 * </ul>
 *
 * <p>The note in the handoff saying {@code WorldRenderEvents} exists on both was
 * wrong; it does not exist on 26.2 in any form.
 *
 * <p>What is <em>not</em> shimmed, because it turned out identical on both:
 * {@code SubmitNodeCollector} (byte for byte), {@code VertexConsumer},
 * {@code PoseStack.Pose}, {@code RenderTypes.lines()} and {@code Camera}. So
 * both branches below do nothing but obtain a pose and a consumer, and hand
 * them to the same drawing code.
 */
public final class WorldHighlightHook {

    private WorldHighlightHook() {}

    public static void register() {
        //? if >=26.1 {
        /*net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN
                .register(context -> {
                    if (!ContainerHighlight.get().hasBoxes()) return;
                    // Nothing is drawn now. The node is queued and replayed in
                    // the line phase, which is why the lambda takes its own pose
                    // rather than closing over the one we have here - and why
                    // it reads the camera then too. Capturing the camera at
                    // submit time paired a position from one moment with a pose
                    // from another, and the gap between them is the player's
                    // own movement: strafing slid every box sideways by exactly
                    // how far the camera had travelled in between.
                    context.submitNodeCollector().submitCustomGeometry(
                            context.poseStack(), RenderTypes.lines(),
                            (pose, lines) -> ContainerHighlight.get().drawBoxes(
                                    pose, lines,
                                    net.minecraft.client.Minecraft.getInstance()
                                            .gameRenderer.mainCamera().position()));
                });
        *///?} else {
        net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents.AFTER_ENTITIES
                .register(context -> {
                    if (!ContainerHighlight.get().hasBoxes()) return;
                    Camera camera = context.gameRenderer().getMainCamera();
                    ContainerHighlight.get().drawBoxes(
                            context.matrices().last(),
                            context.consumers().getBuffer(RenderTypes.lines()),
                            camera.position());
                });
        //?}
    }
}
