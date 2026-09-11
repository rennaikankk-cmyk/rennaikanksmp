package me.matl114.hacks.modules.extra;

import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.managers.Configs;
import me.matl114.managers.Tasks;
import me.matl114.managers.config.FlagRef;
import me.matl114.managers.config.IntRef;
import me.matl114.managers.config.KeyBindRef;
import me.matl114.managers.input.MultiKeyBind;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.Hand;

/**
 * Keeps the server from flagging the player as idle: a tiny alternating yaw
 * sway and an optional hand swing, both sent at a configurable interval.
 */
public class AntiAFK extends BaseModule {
    public AntiAFK() {
        super("AntiAFK");
        bindFlag(enable);
    }

    public final ModulePath root = makePath(Configs.EXTRA_CONFIG, "other.anti-afk");

    public final FlagRef enable = flagBuilder(root.addEnable()).build();

    public final KeyBindRef hotkey =
            toggleHotkey(root.addHotkey(), new MultiKeyBind(), root.addEnable()).build();

    public final IntRef intervalTicks = intBuilder(root.add("interval-ticks"))
            .defaultValue(60)
            .validator(Configs.INT_POSITIVE)
            .build();

    public final FlagRef swayRotation =
            builder(root.add("sway-rotation"), Boolean.class).defaultValue(true).build();

    public final FlagRef swingHand =
            builder(root.add("swing-hand"), Boolean.class).defaultValue(false).build();

    private float swayDirection = 1.0F;

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(Listener.getPostGameTick(), this::onTick);
    }

    public void onTick(Event<ClientPlayerEntity> event) {
        if (checkNull() || !enable.get()) {
            return;
        }
        if (Tasks.getTick() % intervalTicks.get() != 0) {
            return;
        }
        if (swayRotation.get()) {
            // 0.4 degree sway: invisible in gameplay, but the rotation delta
            // rides the next movement packet so the server sees activity
            mc.player.setYaw(mc.player.getYaw() + 0.4F * swayDirection);
            swayDirection = -swayDirection;
        }
        if (swingHand.get()) {
            mc.player.swingHand(Hand.MAIN_HAND);
        }
    }
}
