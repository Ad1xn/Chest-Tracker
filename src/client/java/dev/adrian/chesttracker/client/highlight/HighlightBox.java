package dev.adrian.chesttracker.client.highlight;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

/**
 * The wireframe drawn around a tracked container.
 *
 * <p>Written out edge by edge rather than through a vanilla helper, because
 * there is no longer one: the {@code renderLineBox} family is gone from both
 * targets. Twelve edges is little enough code that owning it is cheaper than
 * finding a moving equivalent on each version.
 *
 * <p>This is the half of the highlight that does <em>not</em> differ between
 * versions. {@code VertexConsumer}, {@code PoseStack.Pose} and
 * {@code RenderTypes.lines()} are identical on both, so only the hook that
 * hands them over is shimmed.
 */
public final class HighlightBox {

    /** Drawn slightly outside the block, so the lines are not inside its faces. */
    private static final double SWELL = 0.002;

    /**
     * Width carried by every vertex, because the line format demands one.
     *
     * <p>{@code RenderPipelines.LINES} is built on
     * {@code POSITION_COLOR_NORMAL_LINE_WIDTH} on <em>both</em> targets, and its
     * render type sets no default - the width is per vertex and nothing fills
     * it in. Omitting it does not draw a thin line, it throws
     * {@code IllegalStateException: Missing elements in vertex} on the second
     * vertex of the first edge, taking the render thread down with it.
     */
    private static final float LINE_WIDTH = 2.0f;

    private HighlightBox() {}

    /**
     * Emits one box in camera-relative coordinates.
     *
     * @param pose  the current transform; vertices are placed through it
     * @param lines a consumer opened on a line render type
     */
    public static void emit(PoseStack.Pose pose, VertexConsumer lines,
                            double x, double y, double z,
                            float red, float green, float blue, float alpha) {

        float x0 = (float) (x - SWELL);
        float y0 = (float) (y - SWELL);
        float z0 = (float) (z - SWELL);
        float x1 = (float) (x + 1 + SWELL);
        float y1 = (float) (y + 1 + SWELL);
        float z1 = (float) (z + 1 + SWELL);

        // Four uprights.
        edge(pose, lines, x0, y0, z0, x0, y1, z0, 0, 1, 0, red, green, blue, alpha);
        edge(pose, lines, x1, y0, z0, x1, y1, z0, 0, 1, 0, red, green, blue, alpha);
        edge(pose, lines, x1, y0, z1, x1, y1, z1, 0, 1, 0, red, green, blue, alpha);
        edge(pose, lines, x0, y0, z1, x0, y1, z1, 0, 1, 0, red, green, blue, alpha);

        // Bottom and top rings.
        ring(pose, lines, x0, x1, y0, z0, z1, red, green, blue, alpha);
        ring(pose, lines, x0, x1, y1, z0, z1, red, green, blue, alpha);
    }

    private static void ring(PoseStack.Pose pose, VertexConsumer lines,
                             float x0, float x1, float y, float z0, float z1,
                             float red, float green, float blue, float alpha) {
        edge(pose, lines, x0, y, z0, x1, y, z0, 1, 0, 0, red, green, blue, alpha);
        edge(pose, lines, x1, y, z0, x1, y, z1, 0, 0, 1, red, green, blue, alpha);
        edge(pose, lines, x1, y, z1, x0, y, z1, -1, 0, 0, red, green, blue, alpha);
        edge(pose, lines, x0, y, z1, x0, y, z0, 0, 0, -1, red, green, blue, alpha);
    }

    /**
     * One line segment.
     *
     * <p>The line format wants a normal per vertex, and it is the segment's own
     * direction - a line has no surface to face away from, so anything else
     * shades it inconsistently as the camera moves.
     */
    private static void edge(PoseStack.Pose pose, VertexConsumer lines,
                             float ax, float ay, float az, float bx, float by, float bz,
                             float nx, float ny, float nz,
                             float red, float green, float blue, float alpha) {
        lines.addVertex(pose, ax, ay, az).setColor(red, green, blue, alpha)
                .setNormal(pose, nx, ny, nz).setLineWidth(LINE_WIDTH);
        lines.addVertex(pose, bx, by, bz).setColor(red, green, blue, alpha)
                .setNormal(pose, nx, ny, nz).setLineWidth(LINE_WIDTH);
    }
}
