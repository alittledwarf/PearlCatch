package com.pearlcatch.modules;

import com.pearlcatch.PearlCatchAddon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.entity.simulator.ProjectileEntitySimulator;
import meteordevelopment.meteorclient.utils.entity.simulator.SimulationStep;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.Hand;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.item.Items;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

/**
 * Throws an ender pearl, then tracks the real pearl entity and fires a wind charge
 * timed so it arrives where the pearl will be.
 *
 * Pearl flight is predicted with Meteor's own ProjectileEntitySimulator, so the
 * gravity and drag values match the game exactly and block collisions are handled.
 *
 * A wind charge has no gravity and no air drag, and leaves the hand at 1.5 blocks
 * per tick, so its flight time to any point is simply distance / 1.5.
 */
public class PearlCatchHorizontal extends Module {
    /** Wind charge muzzle speed in blocks per tick. Matches Meteor's WIND_CHARGE motion data. */
    private static final double WIND_CHARGE_SPEED = 1.5;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgTiming = settings.createGroup("Timing");
    private final SettingGroup sgRender = settings.createGroup("Render");

    // ---------------- General ----------------

    private final Setting<Keybind> trigger = sgGeneral.add(new KeybindSetting.Builder()
        .name("trigger-key")
        .description("Key that starts the pearl then wind charge sequence.")
        .defaultValue(Keybind.fromKey(GLFW.GLFW_KEY_G))
        .build()
    );

    private final Setting<Double> aimOffsetY = sgGeneral.add(new DoubleSetting.Builder()
        .name("aim-offset-y")
        .description("Aim this far below the pearl. The wind burst has a radius, a direct hit is not needed.")
        .defaultValue(0.5)
        .range(-2.0, 2.0)
        .build()
    );

    private final Setting<Boolean> notify = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Print what the module is doing to chat.")
        .defaultValue(true)
        .build()
    );

    // ---------------- Timing ----------------

    private final Setting<Integer> maxLookahead = sgTiming.add(new IntSetting.Builder()
        .name("max-lookahead")
        .description("How many ticks ahead to search for an intercept point.")
        .defaultValue(40)
        .range(5, 120)
        .sliderRange(5, 120)
        .build()
    );

    private final Setting<Double> tolerance = sgTiming.add(new DoubleSetting.Builder()
        .name("timing-tolerance")
        .description("How closely the wind charge flight time must match, in ticks. Raise this if it never fires.")
        .defaultValue(0.75)
        .range(0.1, 5.0)
        .sliderRange(0.1, 5.0)
        .build()
    );

    private final Setting<Integer> minLead = sgTiming.add(new IntSetting.Builder()
        .name("min-lead-ticks")
        .description("Ignore intercept points closer than this. Stops it firing at point blank range.")
        .defaultValue(3)
        .range(0, 30)
        .sliderRange(0, 30)
        .build()
    );

    private final Setting<Integer> giveUpTicks = sgTiming.add(new IntSetting.Builder()
        .name("give-up-ticks")
        .description("Abort if no intercept is found within this many ticks of throwing.")
        .defaultValue(30)
        .range(5, 120)
        .sliderRange(5, 120)
        .build()
    );

    // ---------------- Render ----------------

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render-target")
        .description("Draw a box at the computed intercept point.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> boxSize = sgRender.add(new DoubleSetting.Builder()
        .name("box-size")
        .description("Edge length of the target box.")
        .defaultValue(0.6)
        .range(0.1, 3.0)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .description("Fill colour of the target box.")
        .defaultValue(new SettingColor(25, 225, 25, 40))
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("Outline colour of the target box.")
        .defaultValue(new SettingColor(25, 225, 25, 255))
        .build()
    );

    // ---------------- State ----------------

    private final ProjectileEntitySimulator simulator = new ProjectileEntitySimulator();

    private boolean tracking = false;
    private boolean keyWasDown = false;
    private int ticksSinceThrow = 0;
    private Vec3d target = null;
    private int targetAge = 0;

    public PearlCatchHorizontal() {
        super(PearlCatchAddon.CATEGORY, "pearl-catch-horizontal",
            "Throws a pearl, then times a wind charge to hit it in mid flight.");
    }

    @Override
    public void onDeactivate() {
        reset();
    }

    private void reset() {
        tracking = false;
        ticksSinceThrow = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        // Fade the render target out so it does not hang around forever
        if (target != null && ++targetAge > 40) target = null;

        boolean keyDown = trigger.get().isPressed();
        boolean justPressed = keyDown && !keyWasDown;
        keyWasDown = keyDown;

        if (!tracking) {
            if (justPressed) start();
            return;
        }

        if (++ticksSinceThrow > giveUpTicks.get()) {
            if (notify.get()) ChatUtils.warning("Pearl Catch: no intercept found, aborting.");
            reset();
            return;
        }

        EnderPearlEntity pearl = findOwnPearl();
        if (pearl == null) return; // entity has not reached the client yet

        Vec3d intercept = solveIntercept(pearl);
        if (intercept == null) return;

        target = intercept;
        targetAge = 0;
        tracking = false;

        Vec3d aim = intercept.subtract(0, aimOffsetY.get(), 0);
        Rotations.rotate(Rotations.getYaw(aim), Rotations.getPitch(aim), 50, this::throwWindCharge);
    }

    private void start() {
        FindItemResult pearl = InvUtils.findInHotbar(Items.ENDER_PEARL);
        if (!pearl.found()) {
            if (notify.get()) ChatUtils.error("Pearl Catch: no ender pearl in your hotbar.");
            return;
        }

        if (!InvUtils.findInHotbar(Items.WIND_CHARGE).found()) {
            if (notify.get()) ChatUtils.error("Pearl Catch: no wind charge in your hotbar.");
            return;
        }

        useItem(pearl);

        tracking = true;
        ticksSinceThrow = 0;
    }

    private void throwWindCharge() {
        FindItemResult wind = InvUtils.findInHotbar(Items.WIND_CHARGE);
        if (!wind.found()) {
            if (notify.get()) ChatUtils.error("Pearl Catch: wind charge left the hotbar.");
            return;
        }

        useItem(wind);
        if (notify.get()) ChatUtils.info("Pearl Catch: wind charge away.");
    }

    private void useItem(FindItemResult item) {
        if (mc.player == null || mc.interactionManager == null) return;

        InvUtils.swap(item.slot(), true);
        mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
        mc.player.swingHand(Hand.MAIN_HAND);
        InvUtils.swapBack();
    }

    /**
     * Steps the pearl forward with Meteor's simulator. At each future tick t, checks
     * whether a wind charge fired right now would take about t ticks to reach that point.
     * Returns the first point where the two line up.
     */
    private Vec3d solveIntercept(EnderPearlEntity pearl) {
        if (!simulator.set(pearl)) return null;

        Vec3d eye = mc.player.getEyePos();
        int lookahead = maxLookahead.get();
        double tol = tolerance.get();
        int lead = minLead.get();

        for (int t = 1; t <= lookahead; t++) {
            SimulationStep step = simulator.tick();

            Vec3d pos = new Vec3d(simulator.pos.x, simulator.pos.y, simulator.pos.z);

            if (t >= lead) {
                double flightTicks = eye.distanceTo(pos) / WIND_CHARGE_SPEED;
                if (Math.abs(flightTicks - t) <= tol) return pos;
            }

            // Pearl hit something, no point simulating further
            if (step != null && step.shouldStop) break;
        }

        return null;
    }

    /**
     * getOwner() is often null on the client, so fall back to the nearest pearl.
     */
    private EnderPearlEntity findOwnPearl() {
        EnderPearlEntity best = null;
        double bestDist = Double.MAX_VALUE;

        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof EnderPearlEntity pearl)) continue;

            if (pearl.getOwner() == mc.player) return pearl;

            double d = mc.player.squaredDistanceTo(pearl);
            if (d < bestDist) {
                bestDist = d;
                best = pearl;
            }
        }

        return best;
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (!render.get() || target == null) return;

        double h = boxSize.get() / 2.0;
        Box box = new Box(
            target.x - h, target.y - h, target.z - h,
            target.x + h, target.y + h, target.z + h
        );

        event.renderer.box(box, sideColor.get(), lineColor.get(), ShapeMode.Both, 0);
    }
}
