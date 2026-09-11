package me.matl114.hacks.modules.render;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.events.RenderListener;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.utils.render.RenderCollectors;
import me.matl114.hacks.utils.render.RenderElements;
import me.matl114.managers.Configs;
import me.matl114.managers.Tasks;
import me.matl114.managers.config.FlagRef;
import me.matl114.managers.config.IntRef;
import me.matl114.managers.config.KeyBindRef;
import me.matl114.managers.input.MultiKeyBind;
import me.matl114.utils.MathUtils;
import me.matl114.utils.RenderUtils;
import me.matl114.utils.render.RenderCollector;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

/**
 * Floating damage numbers above entities whose health dropped — derived from
 * client-observed health deltas, so it shows every hit (ours, others',
 * environmental) without any server-side data.
 */
public class DamageDisplay extends BaseModule {
    public DamageDisplay() {
        super("DamageDisplay");
        bindFlag(enable);
    }

    public final ModulePath root = makePath(Configs.RENDER_CONFIG, "render.damage-display");

    public final FlagRef enable = flagBuilder(root.addEnable()).build();

    public final KeyBindRef hotkey =
            toggleHotkey(root.addHotkey(), new MultiKeyBind(), root.addEnable()).build();

    public final IntRef distance = intBuilder(root.add("distance"))
            .defaultValue(32)
            .validator(Configs.INT_POSITIVE)
            .build();

    public final IntRef durationTicks = intBuilder(root.add("duration-ticks"))
            .defaultValue(30)
            .validator(Configs.INT_POSITIVE)
            .build();

    public final FlagRef onlyPlayers =
            builder(root.add("only-players"), Boolean.class).defaultValue(false).build();

    private static final int DAMAGE_COLOR = 0xFFFF5555;

    private record Damage(float amount, int expireTick) {}

    private final Map<Integer, Float> lastHealth = new HashMap<>();
    private final Map<Integer, Damage> active = new HashMap<>();
    private final RenderCollector<RenderElements.Text> textCollector = RenderCollectors.createTextCollector();

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(Listener.getPostGameTick(), this::onTick);
        registerListener(RenderListener.getRender3DEvent(), this::onRender);
    }

    public void onTick(Event<ClientPlayerEntity> event) {
        if (checkNull() || !enable.get()) {
            lastHealth.clear();
            active.clear();
            return;
        }
        int tick = Tasks.getTick();
        double maxDistanceSq = MathUtils.s2(distance.get());
        lastHealth.clear();
        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof LivingEntity living) || living == mc.player) {
                continue;
            }
            if (onlyPlayers.get() && !(living instanceof net.minecraft.entity.player.PlayerEntity)) {
                continue;
            }
            float health = living.getHealth();
            Float previous = lastHealth.put(living.getId(), health);
            if (previous != null && previous - health > 0.05F) {
                if (living.getPos().squaredDistanceTo(mc.player.getPos()) <= maxDistanceSq) {
                    Damage existing = active.get(living.getId());
                    float amount = previous
                            - health
                            + (existing != null && existing.expireTick() > tick ? existing.amount() : 0.0F);
                    active.put(living.getId(), new Damage(amount, tick + durationTicks.get()));
                }
            }
        }
        Iterator<Map.Entry<Integer, Damage>> iterator = active.entrySet().iterator();
        while (iterator.hasNext()) {
            if (tick >= iterator.next().getValue().expireTick()) {
                iterator.remove();
            }
        }
    }

    public void onRender(Event<MatrixStack> event) {
        if (checkNull() || !enable.get() || active.isEmpty()) {
            return;
        }
        MatrixStack stack = event.context();
        RenderUtils.startDrawVirtual(stack);
        try {
            textCollector.clear();
            for (Map.Entry<Integer, Damage> entry : active.entrySet()) {
                Entity entity = mc.world.getEntityById(entry.getKey());
                if (entity == null || !entity.isAlive()) {
                    continue;
                }
                Vec3d pos = new Vec3d(entity.getX(), entity.getBoundingBox().maxY + 0.35D, entity.getZ());
                textCollector.submit(
                        new RenderElements.Text(
                                Text.literal("-%.1f".formatted(entry.getValue().amount())), pos, 1.0F),
                        DAMAGE_COLOR);
            }
            textCollector.render3D(stack);
        } finally {
            RenderUtils.stopDrawVirtual(stack);
        }
    }
}
