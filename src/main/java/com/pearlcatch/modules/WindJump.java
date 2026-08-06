package com.pearlcatch.modules;

import com.pearlcatch.PearlCatchAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;

/**
 * Watches for you jumping, then throws a wind charge straight down so the burst
 * catches you on the way up and launches you higher.
 *
 * A wind charge has no gravity and travels 1.5 blocks per tick, so from eye height
 * it reaches the ground in roughly one tick. The right release tick depends on your
 * ping and how high you want to end up, so throw-delay is a setting rather than a
 * number baked into the code.
 */
public class WindJump extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgAdvanced = settings.createGroup("Advanced");

    // ---------------- General ----------------

    private final Setting<Boolean> predictJump = sgGeneral.add(new BoolSetting.Builder()
        .name("predict-jump")
        .description("Fire on the tick you press jump, rather than the tick after you have already left the ground. Removes a full tick of delay.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> throwDelay = sgGeneral.add(new IntSetting.Builder()
        .name("throw-delay")
        .description("Ticks to wait after the jump before throwing. 0 throws on the jump tick itself.")
        .defaultValue(0)
        .range(0, 10)
        .sliderRange(0, 10)
        .build()
    );

    private final Setting<Double> pitch = sgGeneral.add(new DoubleSetting.Builder()
        .name("pitch")
        .description("Angle to throw at. 90 is straight down. Lower it to angle the boost forwards.")
        .defaultValue(90.0)
        .range(0.0, 90.0)
        .sliderRange(0.0, 90.0)
        .build()
    );

    private final Setting<Integer> cooldown = sgGeneral.add(new IntSetting.Builder()
        .name("cooldown")
        .description("Ticks before it can fire again. Stops it burning charges on every hop.")
        .defaultValue(10)
        .range(0, 100)
        .sliderRange(0, 100)
        .build()
    );

    private final Setting<Boolean> notify = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Say in chat when it fires.")
        .defaultValue(false)
        .build()
    );

    // ---------------- Advanced ----------------

    private final Setting<Boolean> snapCamera = sgAdvanced.add(new BoolSetting.Builder()
        .name("snap-camera")
        .description("Also turn your own view down. The rotation is sent to the server either way, so other players see you look down regardless.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> swapBack = sgAdvanced.add(new BoolSetting.Builder()
        .name("swap-back")
        .description("Return to the slot you were holding after the throw.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> onlyGroundJump = sgAdvanced.add(new BoolSetting.Builder()
        .name("only-ground-jump")
        .description("Only fire on a jump that started from solid ground.")
        .defaultValue(true)
        .build()
    );

    // ---------------- State ----------------

    private boolean wasOnGround = false;
    private int pending = -1;
    private int cooldownLeft = 0;

    public WindJump() {
        super(PearlCatchAddon.CATEGORY, "wind-jump",
            "Throws a wind charge at your feet when you jump, to launch you higher.");
    }

    @Override
    public void onActivate() {
        wasOnGround = mc.player != null && mc.player.isOnGround();
        pending = -1;
        cooldownLeft = 0;
    }

    @Override
    public void onDeactivate() {
        pending = -1;
        cooldownLeft = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        boolean onGround = mc.player.isOnGround();

        if (cooldownLeft > 0) cooldownLeft--;

        // Countdown to a throw that is already armed
        if (pending >= 0) {
            if (pending == 0) {
                pending = -1;
                fire();
            }
            else pending--;

            wasOnGround = onGround;
            return;
        }

        boolean jumped;

        if (predictJump.get()) {
            // The jump key is down and we are still on the ground, so the jump will
            // happen during this very tick. Throwing now lands us in the same tick
            // rather than one behind.
            jumped = onGround && mc.options.jumpKey.isPressed();
        }
        else {
            // Left the ground this tick, moving upwards - that is a jump, one tick late
            jumped = wasOnGround && !onGround && mc.player.getVelocity().y > 0;
            if (onlyGroundJump.get() && !wasOnGround) jumped = false;
        }

        if (jumped && cooldownLeft <= 0) {
            if (InvUtils.findInHotbar(Items.WIND_CHARGE).found()) {
                pending = throwDelay.get();
                if (pending == 0) {
                    pending = -1;
                    fire();
                }
            }
            else if (notify.get()) {
                ChatUtils.warning("Wind Jump: no wind charge in your hotbar.");
            }
        }

        wasOnGround = onGround;
    }

    private void fire() {
        double yaw = mc.player.getYaw();
        Rotations.rotate(yaw, pitch.get(), 60, snapCamera.get(), this::throwCharge);
    }

    private void throwCharge() {
        FindItemResult charge = InvUtils.findInHotbar(Items.WIND_CHARGE);
        if (!charge.found()) return;
        if (mc.player == null || mc.interactionManager == null) return;

        InvUtils.swap(charge.slot(), swapBack.get());
        mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
        mc.player.swingHand(Hand.MAIN_HAND);
        if (swapBack.get()) InvUtils.swapBack();

        cooldownLeft = cooldown.get();

        if (notify.get()) ChatUtils.info("Wind Jump: boost.");
    }
}
