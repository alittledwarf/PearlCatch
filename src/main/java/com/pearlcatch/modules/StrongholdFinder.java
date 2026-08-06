package com.pearlcatch.modules;

import com.pearlcatch.PearlCatchAddon;
import meteordevelopment.meteorclient.events.entity.EntityRemovedEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EyeOfEnderEntity;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

/**
 * Triangulates the stronghold from two eye of ender throws.
 *
 * Each eye travels in a straight line towards the stronghold, so the segment from
 * where it spawned to where it vanished is a ray pointing at it. Two such rays,
 * thrown from different places, cross at the stronghold. Everything happens in the
 * XZ plane - the eye's vertical motion is decoration and tells us nothing.
 *
 * The accuracy depends almost entirely on the angle between the two rays. Throws
 * made from nearly the same spot give nearly parallel lines, and parallel lines
 * cross a very long way from where you want them to.
 */
public class StrongholdFinder extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    // ---------------- General ----------------

    private final Setting<Keybind> resetKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("reset-key")
        .description("Clears saved throws so you can start a fresh triangulation.")
        .defaultValue(Keybind.fromKey(GLFW.GLFW_KEY_DELETE))
        .build()
    );

    private final Setting<Double> minAngle = sgGeneral.add(new DoubleSetting.Builder()
        .name("min-angle")
        .description("Warn if the two throws differ by less than this many degrees. Small angles give wildly wrong results.")
        .defaultValue(10.0)
        .range(0.0, 45.0)
        .sliderRange(0.0, 45.0)
        .build()
    );

    private final Setting<Boolean> keepUpdating = sgGeneral.add(new BoolSetting.Builder()
        .name("keep-updating")
        .description("Each further throw replaces the older of the two saved rays, refining the estimate as you close in.")
        .defaultValue(true)
        .build()
    );

    // ---------------- Render ----------------

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Draw a marker at the estimated stronghold position.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .description("Fill colour of the marker.")
        .defaultValue(new SettingColor(140, 60, 220, 40))
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("Outline colour of the marker.")
        .defaultValue(new SettingColor(140, 60, 220, 255))
        .build()
    );

    // ---------------- State ----------------

    /** One eye throw: where it appeared and where it vanished, in XZ. */
    private record Ray(double startX, double startZ, double endX, double endZ) {
        double angleDegrees() {
            return Math.toDegrees(Math.atan2(endZ - startZ, endX - startX));
        }
    }

    private Vec3d pendingSpawn = null;
    private Ray first = null;
    private Ray second = null;
    private Vec3d estimate = null;
    private boolean resetWasDown = false;

    public StrongholdFinder() {
        super(PearlCatchAddon.CATEGORY, "stronghold-finder",
            "Works out where the stronghold is from two eye of ender throws.");
    }

    @Override
    public void onActivate() {
        clear();
        ChatUtils.info("Stronghold Finder: throw an eye, move sideways a good distance, then throw another.");
    }

    @Override
    public void onDeactivate() {
        clear();
    }

    private void clear() {
        pendingSpawn = null;
        first = null;
        second = null;
        estimate = null;
    }

    @EventHandler
    private void onPacket(PacketEvent.Receive event) {
        if (!(event.packet instanceof EntitySpawnS2CPacket packet)) return;
        if (packet.getEntityType() != EntityType.EYE_OF_ENDER) return;

        pendingSpawn = new Vec3d(packet.getX(), packet.getY(), packet.getZ());
    }

    @EventHandler
    private void onEntityRemoved(EntityRemovedEvent event) {
        if (!(event.entity instanceof EyeOfEnderEntity eye)) return;
        if (pendingSpawn == null) return;

        Ray ray = new Ray(pendingSpawn.x, pendingSpawn.z, eye.getX(), eye.getZ());
        pendingSpawn = null;

        // An eye that barely moved tells us nothing about direction
        double travelled = Math.hypot(ray.endX() - ray.startX(), ray.endZ() - ray.startZ());
        if (travelled < 1.0) {
            ChatUtils.warning("Stronghold Finder: that eye barely moved, ignoring it.");
            return;
        }

        record(ray);
    }

    private void record(Ray ray) {
        if (first == null) {
            first = ray;
            ChatUtils.info("Stronghold Finder: first throw saved. Now walk sideways a few hundred blocks and throw again.");
            return;
        }

        if (second == null || keepUpdating.get()) {
            if (second != null) first = second;
            second = ray;
        }
        else {
            ChatUtils.info("Stronghold Finder: already have two throws. Press your reset key to start over.");
            return;
        }

        double angleDiff = Math.abs(wrap(second.angleDegrees() - first.angleDegrees()));
        if (angleDiff < minAngle.get()) {
            ChatUtils.warning("Stronghold Finder: the two throws are only %.1f degrees apart, so this will be rough. Walk further sideways and throw again.", angleDiff);
        }

        Vec3d result = intersect(first, second);
        if (result == null) {
            ChatUtils.error("Stronghold Finder: those throws are parallel, no crossing point.");
            return;
        }

        estimate = result;

        if (mc.player != null) {
            double dist = Math.hypot(result.x - mc.player.getX(), result.z - mc.player.getZ());
            ChatUtils.info("Stronghold Finder: roughly (highlight)%.0f, %.0f(default) - about (highlight)%.0f(default) blocks away.",
                result.x, result.z, dist);
        }
    }

    /** Crossing point of two XZ lines, or null if they are effectively parallel. */
    private Vec3d intersect(Ray a, Ray b) {
        double a1 = a.endZ() - a.startZ();
        double b1 = a.startX() - a.endX();
        double c1 = a1 * a.startX() + b1 * a.startZ();

        double a2 = b.endZ() - b.startZ();
        double b2 = b.startX() - b.endX();
        double c2 = a2 * b.startX() + b2 * b.startZ();

        double det = a1 * b2 - a2 * b1;
        if (Math.abs(det) < 1e-6) return null;

        double x = (b2 * c1 - b1 * c2) / det;
        double z = (a1 * c2 - a2 * c1) / det;

        if (!Double.isFinite(x) || !Double.isFinite(z)) return null;

        return new Vec3d(x, 0, z);
    }

    private static double wrap(double degrees) {
        while (degrees > 180) degrees -= 360;
        while (degrees < -180) degrees += 360;
        return degrees;
    }

    @EventHandler
    private void onTick(meteordevelopment.meteorclient.events.world.TickEvent.Pre event) {
        boolean down = resetKey.get().isPressed();
        if (down && !resetWasDown) {
            clear();
            ChatUtils.info("Stronghold Finder: cleared.");
        }
        resetWasDown = down;
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (!render.get() || estimate == null || mc.player == null) return;

        // Draw a tall column at the estimate so it is visible from a distance
        double y = mc.player.getY();
        Box box = new Box(
            estimate.x - 1, y - 40, estimate.z - 1,
            estimate.x + 1, y + 40, estimate.z + 1
        );

        event.renderer.box(box, sideColor.get(), lineColor.get(), ShapeMode.Both, 0);
    }
}
