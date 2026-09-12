package me.matl114.hacks.modules.combat;

import java.awt.Color;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Random;
import javax.annotation.Nullable;
import lombok.Setter;
import lombok.experimental.Accessors;
import me.matl114.accessors.access.PlayerInteractEntityC2SPacketAccess;
import me.matl114.accessors.hacks.PlayerInternalAccess;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.events.RenderListener;
import me.matl114.events.impl.EventContainer;
import me.matl114.hacks.CombatTasks;
import me.matl114.hacks.MovTasks;
import me.matl114.hacks.RenderTasks;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.modules.inv.InvExtra;
import me.matl114.hacks.modules.move.PlayerStateManager;
import me.matl114.hacks.utils.config.NBTTypes;
import me.matl114.hacks.utils.config.OptionalPrimitive;
import me.matl114.hacks.utils.entity.PredictorImpl;
import me.matl114.hacks.utils.move.FlightVelocity;
import me.matl114.hacks.utils.move.PursuitUtils;
import me.matl114.managers.Configs;
import me.matl114.managers.Tasks;
import me.matl114.managers.config.*;
import me.matl114.managers.input.MultiKeyBind;
import me.matl114.utils.*;
import me.matl114.utils.algorithms.StateMachine;
import me.matl114.utils.collections.IndexEntry;
import me.matl114.utils.entity.PlayerInputUtils;
import me.matl114.versioned.api.VDataFlag;
import me.matl114.versioned.api.VItem;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.s2c.play.EntityAnimationS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityDamageS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

/**
 * ElytraBot V2: rebuild of {@link ElytraBot} with target intelligence on top of
 * the same decision/execution split (movementDirection -> FlightVelocity).
 *
 * New over v1:
 * - totem awareness: mace dives are gated on the target's totem state, orbit
 *   overhead while the totem is up, dive on the pop
 * - pearl awareness: tracks the target's thrown ender pearls, simulates the
 *   landing spot and intercepts there instead of chasing the stale position
 * - attack cadence: every attack is gated by attack-cooldown progress and a
 *   minimum tick interval
 * - anti-spear feint: alternating lateral drift while they aim, hard dodge
 *   burst the moment their spear is fully charged
 * - spear engage window: after their spear is spent, dive to combat range
 * - follower terrain awareness: raycast ahead, climb before hitting terrain
 *
 * v1 and v2 are mutually exclusive at runtime: while ElytraBot is enabled,
 * v2 pauses itself.
 */
public class ElytraBotV2 extends BaseModule {
    public final ModulePath combatBot = makePath(Configs.COMBAT_CONFIG, "combat-bot");
    public final ModulePath path = combatBot.add("elytra-bot-v2");

    public static ElytraBotV2 INSTANCE;

    public ElytraBotV2() {
        super("ElytraBotV2");
        bindFlag(enable);
        INSTANCE = this;
    }

    public final FlagRef enable = flagBuilder(path.add("enable")).build();

    public final KeyBindRef keyBind = moduleEntry(path.add("hotkey"), new MultiKeyBind(), path.add("enable"))
            .build();

    public final IntRef targetRange = intBuilder(path.add("range"))
            .defaultValue(80)
            .validator(Configs.INT_POSITIVE)
            .build();

    public final EnumRef<Mode> mode =
            builder(path.add("mode"), Mode.class).defaultValue(Mode.FOLLOW).build();

    public final FlagRef playerOnly =
            builder(path.add("player-only"), Boolean.class).defaultValue(true).build();

    public final FlagRef autoControl =
            flagBuilder(path.add("auto-control-elytra")).build();

    public final FlagRef dynamicTarget =
            flagBuilder(path.add("dynamic-target")).defaultValue(true).build();

    public final FlagRef onlyWhenNoWASD =
            flagBuilder(path.add("only-when-no-wasd")).build();

    // ---------------- mace ----------------
    public final DoubleRef maceHeight = doubleBuilder(path.add("mace-height"))
            .defaultValue(10.0D)
            .show(() -> mode.get().isIn(Mode.MACE))
            .build();

    public final DoubleRef maceHeightGround = doubleBuilder(path.add("mace-height-ground"))
            .defaultValue(10.0D)
            .show(() -> mode.get().isIn(Mode.MACE))
            .build();

    public final IntRef maceMaxPullUpTick = intBuilder(path.add("mace-max-pull-up-tick"))
            .defaultValue(40)
            .validator(Configs.INT_POSITIVE)
            .show(() -> mode.get().isIn(Mode.MACE))
            .build();

    public final DoubleRef maceFollowMinHeight = doubleBuilder(path.add("mace-max-follow-height"))
            .defaultValue(1.5D)
            .show(() -> mode.get().isIn(Mode.MACE))
            .build();

    public final DoubleRef combatMaceRange =
            doubleBuilder(path.add("combat-range")).defaultValue(10.0D).build();

    public final DoubleRef combatSpearRange =
            doubleBuilder(path.add("combat-spear-range")).defaultValue(11.0D).build();

    public final FlagRef maceUsePredictor = flagBuilder(path.add("use-predictor"))
            .show(() -> mode.get().isIn(Mode.MACE))
            .build();

    // ---------------- v2 target intelligence ----------------
    // totem is ONE extra life, not a reason to hold fire: keep hitting through
    // it, then press harder during the post-pop kill window
    public final FlagRef totemAware = flagBuilder(path.add("totem-aware"))
            .defaultValue(true)
            .show(() -> mode.get().isIn(Mode.MACE, Mode.SPEAR))
            .build();

    public final IntRef killWindowTicks = intBuilder(path.add("kill-window-ticks"))
            .defaultValue(60)
            .validator(Configs.INT_POSITIVE)
            .show(() -> totemAware.get())
            .build();

    public final FlagRef totemHover = flagBuilder(path.add("totem-hover"))
            .defaultValue(false)
            .show(() -> mode.get().isIn(Mode.MACE) && totemAware.get())
            .build();

    public final DoubleRef totemHoverHeight = doubleBuilder(path.add("totem-hover-height"))
            .defaultValue(14.0D)
            .show(() -> mode.get().isIn(Mode.MACE) && totemHover.get())
            .build();

    public final FlagRef pearlAware =
            flagBuilder(path.add("pearl-aware")).defaultValue(true).build();

    public final IntRef pearlSimTicks = intBuilder(path.add("pearl-sim-ticks"))
            .defaultValue(80)
            .validator(Configs.INT_POSITIVE)
            .show(() -> pearlAware.get())
            .build();

    // ---------------- v2 attack cadence ----------------
    public final DoubleRef attackCadence = doubleBuilder(path.add("attack-cooldown-threshold"))
            .defaultValue(0.9)
            .build();

    public final IntRef attackMinInterval = intBuilder(path.add("attack-min-interval"))
            .defaultValue(3)
            .validator(Configs.INT_POSITIVE)
            .build();

    // ---------------- v2 auto spear charge ----------------
    public final FlagRef autoSpearCharge = flagBuilder(path.add("auto-spear-charge"))
            .defaultValue(true)
            .show(() -> mode.get().isIn(Mode.MACE, Mode.SPEAR))
            .build();

    public final DoubleRef spearChargeMinFall = doubleBuilder(path.add("spear-charge-min-fall"))
            .defaultValue(1.5D)
            .show(() -> autoSpearCharge.get())
            .build();

    // ---------------- v2 max-damage stab ----------------
    // spear kinetic damage scales with impact speed: hold the charged stab
    // until the dive reaches peak velocity instead of poking at cruise speed
    public final FlagRef spearMaxDamage = flagBuilder(path.add("spear-max-damage"))
            .defaultValue(true)
            .show(() -> mode.get().isIn(Mode.MACE, Mode.SPEAR))
            .build();

    public final DoubleRef spearStabMinSpeed = doubleBuilder(path.add("spear-stab-min-speed"))
            .defaultValue(1.1D)
            .show(() -> mode.get().isIn(Mode.MACE, Mode.SPEAR) && spearMaxDamage.get())
            .build();

    // ---------------- v2 anti-spear feint ----------------
    public final DoubleRef dodgeIntensity =
            doubleBuilder(path.add("dodge-intensity")).defaultValue(1.0D).build();

    public final IntRef dodgeCycleTicks = intBuilder(path.add("dodge-cycle-ticks"))
            .defaultValue(8)
            .validator(Configs.INT_POSITIVE)
            .build();

    // ---------------- v2 adaptive aggression ----------------
    // vs high-frequency rushers (charged dueling head-on): trade some per-hit
    // damage for pressure instead of holding charged stabs waiting for peaks
    public final FlagRef adaptiveAggression =
            flagBuilder(path.add("adaptive-aggression")).defaultValue(true).build();

    public final IntRef adaptiveWindowTicks = intBuilder(path.add("adaptive-window-ticks"))
            .defaultValue(40)
            .validator(Configs.INT_POSITIVE)
            .show(() -> adaptiveAggression.get())
            .build();

    public final IntRef adaptiveAttackThreshold = intBuilder(path.add("adaptive-attack-threshold"))
            .defaultValue(3)
            .validator(Configs.INT_POSITIVE)
            .show(() -> adaptiveAggression.get())
            .build();

    // speed gate measures closing speed (their oncoming velocity counts too):
    // head-on duels reach the gate naturally instead of waiting for our peak
    public final FlagRef relativeSpeedGate = flagBuilder(path.add("relative-speed-gate"))
            .defaultValue(true)
            .show(() -> mode.get().isIn(Mode.MACE, Mode.SPEAR) && spearMaxDamage.get())
            .build();

    // ---------------- v2 pursuit upgrade ----------------

    public final FlagRef terrainAware =
            flagBuilder(path.add("terrain-aware")).defaultValue(true).build();

    public final IntRef terrainLookahead = intBuilder(path.add("terrain-lookahead"))
            .defaultValue(8)
            .validator(Configs.INT_POSITIVE)
            .show(() -> terrainAware.get())
            .build();

    public final FlagRef avoidanceFan = flagBuilder(path.add("avoidance-fan"))
            .defaultValue(true)
            .show(() -> terrainAware.get())
            .build();

    /** intercept course: cut off a moving target instead of tail-chasing it */
    public final FlagRef intercept =
            flagBuilder(path.add("intercept")).defaultValue(true).build();

    /** prediction strength for the intercept course, 0 = chase current pos */
    public final DoubleRef interceptLead = doubleBuilder(path.add("intercept-lead"))
            .defaultValue(1.0D)
            .validator(v -> v >= 0.0D && v <= 1.0D)
            .show(() -> intercept.get())
            .build();

    /** near the target stop sprinting through it: slow to a trailing follow */
    public final FlagRef converge = flagBuilder(path.add("converge"))
            .defaultValue(true)
            .show(() -> mode.get().isIn(Mode.FOLLOW))
            .build();

    /** after losing the target keep flying the last known course while re-searching */
    public final IntRef lostTargetTicks = intBuilder(path.add("lost-target-ticks"))
            .defaultValue(40)
            .validator(Configs.INT_POSITIVE)
            .build();

    public final NBTRef<OptionalPrimitive<Double>> followOnGroundHeight = builder(
                    path.add("follow-on-ground-height-extra"), OptionalPrimitive.DOUBLE_TYPE)
            .defaultValue(new OptionalPrimitive<>(true, NBTTypes.DOUBLE_TYPE, 2.0D))
            .build();

    public final FlagRef render = flagBuilder(path.add("render")).build();

    public void registerAll() {
        super.registerAll();
        registerListener(Listener.getPreTick(), this::onPreTick);
        registerListener(Listener.getCustomListener().getChannel(FlightVelocity.class), this::onElytraChase);
        registerListener(Listener.getPacketPoint().getChannel(PlayerInteractEntityC2SPacket.class), this::onAttack);
        registerListener(Listener.getPreHandleInputEvents(), this::onInputEvent);
        registerListener(Listener.getPacketPoint().getChannel(EntityStatusS2CPacket.class), this::onEntityStatus);
        registerListener(Listener.getPacketPoint().getChannel(EntityAnimationS2CPacket.class), this::onEntityAnimation);
        registerListener(RenderListener.getRender3DEvent(), this::onRender);
        registerListener(Listener.getPacketPoint().getChannel(EntityDamageS2CPacket.class), this::onEntityDamage);
    }

    Entity target;

    @Nullable
    Behaviour currentBehaviour;

    final Behaviour follower = new FollowerV2().setBase(this);
    final Behaviour mace = new MaceV2().setBase(this);
    final Behaviour spear = new SpearV2().setBase(this);

    public boolean canControlFlight() {
        return enable.get()
                && autoControl.get()
                && currentBehaviour != null
                && currentBehaviour.movementDirection != null
                && currentBehaviour.movementDirection.lengthSquared() > 1E-9;
    }

    Entity lastTarget;
    TargetActionV2 currentAction;
    boolean currentInCombatRange;
    boolean currentOnGround;
    boolean heightLimitEnvironment = false;
    int lastAttackTick = Integer.MIN_VALUE;

    private static final Random RANDOM = new Random();

    public boolean isNoPullUpEnvironment() {
        return heightLimitEnvironment || currentOnGround;
    }

    public boolean isTargetUsingSpear() {
        return target instanceof PlayerEntity player && SpearEnhance.isUsingSpear(player);
    }

    public double combatRange() {
        return isTargetUsingSpear() ? combatSpearRange.get() : combatMaceRange.get();
    }

    public Behaviour getBehaviour() {
        if (!enable.get()) return null;
        // mutual exclusion: yield to v1 while it is enabled
        if (ElytraBot.INSTANCE != null && ElytraBot.INSTANCE.enable.get()) return null;
        return switch (mode.get()) {
            case FOLLOW -> follower;
            case MACE -> mace;
            case SPEAR -> spear;
        };
    }

    public void onPreTick(Event<Void> event) {
        var lastBehaviour = currentBehaviour;
        currentBehaviour = getBehaviour();
        if (currentBehaviour != lastBehaviour) {
            if (lastBehaviour != null) {
                lastBehaviour.onDisable();
            }
            if (currentBehaviour != null) {
                currentBehaviour.onEnable();
            }
        }
        if (checkNull()) return;
        if (currentBehaviour != null) {
            heightLimitEnvironment = mc.world.getDimension().hasCeiling()
                    && mc.player.getY()
                            < mc.world.getDimension().minY()
                                    + mc.world.getDimension().logicalHeight();
            refreshTarget();
            updateTargetIntelligence();
            currentBehaviour.onUpdate();
            updateSpearCharge();
        } else {
            releaseAutoSpear();
        }
    }

    /**
     * dynamic speed shaping for the active behaviour: dive-speed braking is
     * written here each tick (steep dive = slower, more ticks inside the
     * strike window); behaviours reset it to 1.0 when not shaping
     */
    double behaviourSpeedMultiplier = 1.0D;

    public double speedMultiplier() {
        return behaviourSpeedMultiplier;
    }

    // ---------------------------------------------------------------
    // target intelligence: totem, pearl, action classification
    // ---------------------------------------------------------------

    int totemPopTick = Integer.MIN_VALUE;

    /** totem gate: true while the target visibly holds a totem (or just popped one) */
    public boolean targetHasTotem() {
        if (!totemAware.get() || !(target instanceof PlayerEntity player)) {
            return false;
        }
        boolean holds = player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)
                || player.getMainHandStack().isOf(Items.TOTEM_OF_UNDYING);
        if (!holds) {
            return false;
        }
        // the item lingers one equipment sync after the pop: trust the pop packet
        return Tasks.getTick() - totemPopTick > 3;
    }

    /** conservative play (off by default): hold at altitude while their totem is up */
    public boolean totemHoverActive() {
        return totemAware.get() && totemHover.get() && targetHasTotem();
    }

    /**
     * kill window: right after a totem pop the target sits at half health with
     * absorption gone, scrambling to re-totem — that is when presses connect
     */
    public boolean killWindowOpen() {
        if (!totemAware.get() || totemPopTick == Integer.MIN_VALUE) {
            return false;
        }
        return Tasks.getTick() - totemPopTick < killWindowTicks.get();
    }

    @Nullable
    Entity trackedPearl;

    @Nullable
    Vec3d pearlLanding;

    int pearlGraceTick = Integer.MIN_VALUE;

    /**
     * finds a live ender pearl near the target, simulates its full trajectory
     * (projectile gravity/drag + block raycast) and exposes the landing spot.
     */
    void updatePearl() {
        if (!pearlAware.get() || target == null) {
            trackedPearl = null;
            pearlLanding = null;
            return;
        }
        Entity best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity entity : mc.world.getEntities()) {
            if (entity.getType() != EntityType.ENDER_PEARL) continue;
            if (entity instanceof ProjectileEntity projectile
                    && projectile.getOwner() != null
                    && projectile.getOwner() != target) {
                continue;
            }
            double dist = entity.getPos().squaredDistanceTo(target.getPos());
            if (dist < 36.0D && dist < bestDist) {
                best = entity;
                bestDist = dist;
            }
        }
        if (best != null) {
            if (best != trackedPearl) {
                trackedPearl = best;
                pearlLanding = simulatePearlLanding(best.getPos(), best.getVelocity());
            }
            pearlGraceTick = Tasks.getTick();
        } else if (trackedPearl != null) {
            // pearl landed or despawned: keep the landing spot valid briefly so
            // the intercept vector stays stable through the teleport frame
            if (Tasks.getTick() - pearlGraceTick > 4) {
                trackedPearl = null;
                pearlLanding = null;
            }
        }
    }

    @Nullable
    Vec3d simulatePearlLanding(Vec3d pos, Vec3d velocity) {
        Vec3d current = pos;
        Vec3d motion = velocity;
        for (int i = 0; i < pearlSimTicks.get(); i++) {
            Vec3d next = current.add(motion);
            BlockHitResult hit = mc.world.raycast(new RaycastContext(
                    current, next, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player));
            if (hit.getType() != HitResult.Type.MISS) {
                return hit.getPos();
            }
            current = next;
            // thrown item physics: 0.99 drag, 0.03 gravity per tick
            motion = motion.multiply(0.99D).subtract(0, 0.03D, 0);
            if (motion.lengthSquared() < 1E-6) break;
        }
        return current;
    }

    public boolean pearlActive() {
        return pearlLanding != null;
    }

    /** unified aim point: pearl landing overrides stale position data */
    public Vec3d predictTargetPos() {
        if (pearlActive()) {
            return pearlLanding;
        }
        return maceUsePredictor.get()
                ? PositionPredict.INSTANCE.attackPredictArgument.get().predict(target)
                : target.getPos();
    }

    void refreshTarget() {
        if (!EntityUtils.isEntityValid(target)
                || target.getPos().squaredDistanceTo(mc.player.getPos()) > targetRange.get()) {
            target = null;
        }
        if (target == null || dynamicTarget.get()) {
            target = currentBehaviour != null ? currentBehaviour.searchTarget() : null;
        }
    }

    void updateTargetIntelligence() {
        if (target != lastTarget) {
            lastTarget = target;
            currentAction = null;
            totemPopTick = Integer.MIN_VALUE;
            targetAttackTicks.clear();
            trackedPearl = null;
            pearlLanding = null;
        }
        updatePearl();
        if (target == null) return;
        currentInCombatRange =
                TargetSelector.INSTANCE.isWithinAttackRange(mc.player.getPos(), target.getBoundingBox(), combatRange());
        currentOnGround = target.isOnGround() || CollisionUtil.isEntitySupported(target);
        // pearl mid-flight: this IS an escape, intercept the landing
        if (pearlActive()) {
            currentAction = TargetActionV2.PEARL_ESCAPE;
            return;
        }
        if (!(target instanceof PlayerEntity)) {
            currentAction = TargetActionV2.SLOW_SPEED;
            return;
        }
        if (currentOnGround) {
            currentAction = TargetActionV2.SLOW_SPEED;
            return;
        }
        List<PredictorImpl.KnownPosition> positions =
                ((PlayerInternalAccess) target).getPredictorImpl().getLastKnownPositions(3);
        if (positions.size() < 2) {
            currentAction = TargetActionV2.CIRCLING;
            return;
        }
        PredictorImpl.KnownPosition oldest = positions.get(0);
        if (Tasks.getTick() - oldest.tick() > 20) {
            currentAction = TargetActionV2.AFK;
            return;
        }
        Vec3d pos0 = positions.get(0).vec3d();
        Vec3d pos1 = positions.get(1).vec3d();
        Vec3d pos2 = positions.get(2).vec3d();
        double dist01 = pos0.distanceTo(pos1);
        double dist12 = pos1.distanceTo(pos2);
        if (dist01 < 1E-6 && dist12 < 1E-6) {
            currentAction = TargetActionV2.AFK;
        } else if (dist01 < 0.75 && dist12 < 0.75) {
            currentAction = TargetActionV2.SLOW_SPEED;
        } else {
            Vec3d ab = pos1.subtract(pos0);
            Vec3d bc = pos2.subtract(pos1);
            double dot = ab.dotProduct(bc);
            double magAB = ab.length();
            double magBC = bc.length();
            double angleDeg = Math.toDegrees(Math.acos(Math.min(1.0, Math.max(-1.0, dot / (magAB * magBC)))));
            if (angleDeg < 60.0) {
                Vec3d toPlayer = mc.player.getPos().subtract(pos2);
                double moveDot = bc.normalize().dotProduct(toPlayer.normalize());
                currentAction = moveDot > 0 ? TargetActionV2.TOWARDS : TargetActionV2.ESCAPING;
            } else {
                currentAction = TargetActionV2.CIRCLING;
            }
        }
    }

    // ---------------------------------------------------------------
    // attack cadence (v2: every attack respects cooldown + interval)
    // ---------------------------------------------------------------

    /** opponent pressure meter: their swings/hits inside the recent window */
    final ArrayDeque<Integer> targetAttackTicks = new ArrayDeque<>();

    void recordTargetAttack() {
        int tick = Tasks.getTick();
        targetAttackTicks.addLast(tick);
        pruneTargetAttacks(tick);
    }

    private void pruneTargetAttacks(int tick) {
        int window = adaptiveWindowTicks.get();
        while (!targetAttackTicks.isEmpty() && tick - targetAttackTicks.peekFirst() > window) {
            targetAttackTicks.pollFirst();
        }
    }

    /** high pressure = the target is swinging fast (high-frequency rusher) */
    public boolean underHighPressure() {
        if (!adaptiveAggression.get()) {
            return false;
        }
        pruneTargetAttacks(Tasks.getTick());
        return targetAttackTicks.size() >= adaptiveAttackThreshold.get();
    }

    /**
     * impact speed for the kinetic gate: our full speed plus the target's
     * oncoming component — a head-on dueler already feeds us the closing speed
     */
    double stabImpactSpeed() {
        double speed = mc.player.getVelocity().length();
        if (relativeSpeedGate.get() && target != null) {
            Vec3d toTarget = target.getPos().subtract(mc.player.getPos());
            if (toTarget.lengthSquared() > 1E-4) {
                double targetClosing = -target.getVelocity().dotProduct(toTarget.normalize());
                if (targetClosing > 0) {
                    speed += targetClosing;
                }
            }
        }
        return speed;
    }

    /** max-damage gate: stab only at dive-speed peaks (kinetic damage ~ speed) */
    public boolean spearStabReady() {
        if (!spearMaxDamage.get()) {
            return true;
        }
        double minSpeed = spearStabMinSpeed.get();
        if (killWindowOpen()) {
            // the window is worth more than the perfect hit: accept a slower stab
            minSpeed *= 0.8D;
        } else if (underHighPressure()) {
            // they out-tempo us: stop holding charged stabs for the perfect peak
            minSpeed *= 0.85D;
        }
        return stabImpactSpeed() >= minSpeed;
    }

    public boolean attackReady() {
        // press harder in the kill window: relaxed cooldown gate + interval
        boolean killWindow = killWindowOpen();
        boolean pressure = !killWindow && underHighPressure();
        double threshold = attackCadence.get();
        if (killWindow) {
            threshold = Math.min(0.7D, threshold * 0.8D);
        } else if (pressure) {
            threshold = Math.min(0.75D, threshold * 0.85D);
        }
        if (mc.player.getAttackCooldownProgress(0.5F) < threshold) {
            return false;
        }
        int interval = attackMinInterval.get();
        if (killWindow || pressure) {
            interval = Math.max(1, interval - 1);
        }
        return Tasks.getTick() - lastAttackTick >= interval;
    }

    public void markAttacked() {
        lastAttackTick = Tasks.getTick();
    }

    // ---------------------------------------------------------------
    // v2 auto spear charge: hold use on the spear during the damage
    // window so kinetic (charged) hits actually deal damage. v1 never
    // started the use action, so spear dives hit for plain melee only.
    // ---------------------------------------------------------------

    boolean spearChargeHeld = false;

    @Nullable
    Runnable spearSwapBack;

    void updateSpearCharge() {
        boolean diving = autoSpearCharge.get()
                && mode.get().isIn(Mode.MACE, Mode.SPEAR)
                && mc.player.isFallFlying()
                && target != null
                && PlayerStateManager.INSTANCE.fallDistance >= spearChargeMinFall.get();
        if (!diving) {
            releaseAutoSpear();
            return;
        }
        if (SpearEnhance.isUsingSpear(mc.player)) {
            spearChargeHeld = true;
            return;
            // overflow restart is handled by SpearEnhance.spearAutoRestart;
            // post-hit restarts happen naturally here once the use ends
        }
        if (VItem.getInstance().isSpear(mc.player.getStackInHand(Hand.MAIN_HAND))) {
            mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
            spearChargeHeld = true;
            return;
        }
        // spear somewhere in inventory: swap it into the main hand (kept there
        // while diving; the swap-back runs when the dive ends)
        if (spearSwapBack == null) {
            IndexEntry<ItemStack> spear =
                    InventoryUtils.findPlayerItem(stack -> VItem.getInstance().isSpear(stack), false, false);
            if (spear != null) {
                spearSwapBack = InvExtra.INSTANCE.swapInventoryIndexToHand(spear.index());
            }
        }
    }

    void releaseAutoSpear() {
        if (spearChargeHeld) {
            if (mc.player != null && mc.player.isUsingItem()) {
                mc.interactionManager.stopUsingItem(mc.player);
            }
            spearChargeHeld = false;
        }
        if (spearSwapBack != null) {
            spearSwapBack.run();
            spearSwapBack = null;
        }
    }

    // ---------------------------------------------------------------
    // event plumbing (mirrors v1)
    // ---------------------------------------------------------------

    public void onElytraChase(Event<EventContainer<FlightVelocity>> event) {
        if (enable.get()) {
            Behaviour behaviour = currentBehaviour;
            if (behaviour != null) {
                if (onlyWhenNoWASD.get()) {
                    PlayerInputUtils.Input input = PlayerInputUtils.of(mc.options);
                    if (input.hasMovementControl()) {
                        behaviour.onPauseControl();
                        return;
                    }
                }
                behaviour.onElytra(event);
            }
        }
    }

    public void onAttack(Event<PlayerInteractEntityC2SPacket> attack) {
        if (enable.get()
                && currentBehaviour != null
                && PlayerInteractEntityC2SPacketAccess.of(attack.context).isAttack()) {
            Entity entity = mc.world.getEntityById(
                    PlayerInteractEntityC2SPacketAccess.of(attack.context).getEntityId());
            if (entity != null) {
                currentBehaviour.onAttack(entity);
            }
        }
    }

    public void onInputEvent(Event<Void> event) {
        if (enable.get() && currentBehaviour != null) {
            currentBehaviour.onInputEvent(event);
        }
    }

    public void onEntityStatus(Event<EntityStatusS2CPacket> statusEvent) {
        var status = statusEvent.context;
        if (!enable.get() || mc.player == null || mc.world == null) return;
        // target popped a totem: open the dive window
        if (target != null
                && status.getEntity(mc.world) == target
                // vanilla totem-pop entity status (EntityStatuses has no named constant here)
                && status.getStatus() == 35) {
            totemPopTick = Tasks.getTick();
        }
        if (currentBehaviour instanceof HitListenerV2 listener
                && status.getEntity(mc.world) == mc.player
                && status.getStatus() == VDataFlag.ENTITY_STATUS_KINETIC_ATTACK) {
            listener.onHit(HitListenerV2.HIT_SPEAR);
        }
    }

    public void onEntityAnimation(Event<EntityAnimationS2CPacket> animationEvent) {
        // pressure meter: count the target's main-hand swings (attacks and
        // whiffs both count — a rusher's tempo is the signal)
        if (!adaptiveAggression.get() || !enable.get() || checkNull()) return;
        var animation = animationEvent.context;
        if (animation.getAnimationId() == EntityAnimationS2CPacket.SWING_MAIN_HAND
                && target != null
                && animation.getEntityId() == target.getId()) {
            recordTargetAttack();
        }
    }

    public void onEntityDamage(Event<EntityDamageS2CPacket> damageEvent) {
        if (checkNull()) return;
        // they landed a hit on anything (us included): that is pressure too
        if (adaptiveAggression.get() && target != null && damageEvent.context.sourceCauseId() == target.getId()) {
            recordTargetAttack();
        }
        if (currentBehaviour instanceof HitListenerV2 listener
                && enable.get()
                && damageEvent.context.sourceCauseId() == mc.player.getId()
                && mc.world.getEntityById(damageEvent.context.entityId()) == target) {
            var source = damageEvent.context.sourceType().getKey().orElse(null);
            if (DamageUtils.isType(source, "mace_smash")) {
                listener.onHit(HitListenerV2.HIT_MACE);
            } else {
                listener.onHit(HitListenerV2.HIT_ATTACK);
            }
        }
    }

    public void onRender(Event<MatrixStack> event) {
        if (enable.get() && render.get() && currentBehaviour != null) {
            RenderUtils.startDrawVirtual(event.context);
            try {
                Vec3d targetRender = currentBehaviour.movementDirection.add(mc.player.getPos());
                RenderUtils.drawOutlinedBox(
                        event.context,
                        targetRender.add(RenderTasks.FROM),
                        targetRender.add(RenderTasks.TO),
                        Color.MAGENTA);
            } finally {
                RenderUtils.stopDrawVirtual(event.context);
            }
        }
    }

    public void refreshTargetNow() {
        refreshTarget();
    }

    public static interface HitListenerV2 {
        int HIT_ATTACK = 0;
        int HIT_MACE = 1;
        int HIT_SPEAR = 2;

        void onHit(int type);
    }

    // ---------------------------------------------------------------
    // behaviour base
    // ---------------------------------------------------------------

    @Setter
    @Accessors(chain = true)
    public abstract static class Behaviour {
        ElytraBotV2 base;
        Vec3d movementDirection = Vec3d.ZERO;

        public abstract Entity searchTarget();

        public void onElytra(Event<EventContainer<FlightVelocity>> event) {
            if (movementDirection != null && movementDirection.lengthSquared() > 1E-9) {
                Vec3d targetVec = movementDirection;
                double min =
                        Math.min(targetVec.length(), event.context.getValue().maxVelocity() * base.speedMultiplier());
                targetVec = targetVec.normalize().multiply(min);
                event.context.getValue().velocity(targetVec);
            }
        }

        public void onUpdate() {}

        public void onInputEvent(Event<Void> event) {}

        public void onAttack(Entity entity) {}

        public abstract void onEnable();

        public abstract void onDisable();

        public void onPauseControl() {}

        /** lateral dodge relative to our current heading, sign alternates (feint) */
        protected Vec3d lateralDodge(Vec3d heading, double magnitude) {
            Vec3d horizontal = heading.withAxis(Direction.Axis.Y, 0);
            if (horizontal.lengthSquared() < 1E-4) {
                horizontal = new Vec3d(1, 0, 0);
            }
            Vec3d side = new Vec3d(0, 1, 0).crossProduct(horizontal).normalize();
            return side.multiply(magnitude);
        }

        // ---------------------------------------------------------------
        // shared pursuit helpers (follower / orbit / engage all use these)
        // ---------------------------------------------------------------

        // lost-target memory: last sighted position and velocity of the target
        Vec3d lastSeenPos;
        Vec3d lastSeenVel = Vec3d.ZERO;
        int lastSeenTick = Integer.MIN_VALUE;

        /** refresh the last-sighting memory; call every tick we still see a target */
        void trackTargetSighting() {
            if (base.target != null) {
                lastSeenPos = base.target.getPos();
                lastSeenVel = PursuitUtils.estimateTargetVelocity(base.target);
                lastSeenTick = Tasks.getTick();
            }
        }

        /**
         * ghost course after losing the target: extrapolate the last sighting
         * for a bounded number of ticks. null once the memory expired.
         */
        Vec3d lostCourse(double cruise) {
            if (lastSeenPos == null || Tasks.getTick() - lastSeenTick >= base.lostTargetTicks.get()) {
                return null;
            }
            int dt = Tasks.getTick() - lastSeenTick;
            Vec3d ghost = lastSeenPos.add(lastSeenVel.multiply(Math.min(dt, 10)));
            Vec3d heading = ghost.subtract(mc.player.getPos());
            if (heading.lengthSquared() < 1E-9) {
                return null;
            }
            return heading.normalize().multiply(cruise);
        }

        /**
         * approach aim: intercept course on the moving target, keeping the
         * fallback (attack predict / pearl landing) when intercept is off
         */
        Vec3d approachAim(Vec3d fallbackAim, double leadScale) {
            if (!base.intercept.get() || base.pearlActive()) {
                return fallbackAim;
            }
            double cruise = Math.max(0.8D, mc.player.getVelocity().length());
            return PursuitUtils.interceptPoint(
                    mc.player.getPos(),
                    cruise,
                    base.target.getPos(),
                    PursuitUtils.estimateTargetVelocity(base.target),
                    leadScale,
                    40);
        }

        /**
         * horizontal-only intercept for behaviours that manage altitude by
         * their own state machine (mace pull-up/hover, spear orbit/engage):
         * the predicted vertical offset of a climbing target would push the
         * approach height ever upward, so only XZ borrows the intercept
         */
        Vec3d approachAimHorizontal(Vec3d fallbackAim, double leadScale) {
            Vec3d aim = approachAim(fallbackAim, leadScale);
            if (aim == fallbackAim) {
                return fallbackAim;
            }
            return new Vec3d(aim.x, fallbackAim.y, aim.z);
        }

        /** fan terrain avoidance for non-dive headings; keeps heading length */
        Vec3d avoid(Vec3d heading) {
            if (!base.terrainAware.get() || !base.avoidanceFan.get()) {
                return heading;
            }
            return PursuitUtils.steerAroundTerrain(mc.player.getEyePos(), heading, base.terrainLookahead.get());
        }
    }

    // ---------------------------------------------------------------
    // FOLLOW: terrain-aware chaser
    // ---------------------------------------------------------------

    public static class FollowerV2 extends Behaviour {

        @Override
        public Entity searchTarget() {
            return CombatTasks.getTargetSelector().searchAttack(base.targetRange.get(), true, 0, this::canBeAttacked);
        }

        public boolean canBeAttacked(Entity entity) {
            return TargetSelector.INSTANCE.canAttack(entity)
                    && (!base.playerOnly.get() || entity instanceof PlayerEntity);
        }

        @Override
        public synchronized void onUpdate() {
            double cruise = Math.max(0.8D, mc.player.getVelocity().length());
            trackTargetSighting();
            if (base.target != null) {
                // aim point: pearl landing already is an intercept; otherwise cut
                // the moving target off instead of tail-chasing its current pos
                Vec3d aimPos = approachAim(base.predictTargetPos(), base.interceptLead.get());
                Vec3d direction = aimPos.subtract(mc.player.getPos());
                if (base.currentOnGround) {
                    var op = base.followOnGroundHeight.get();
                    if (op.isPresent()) {
                        direction = direction.add(0, op.getValue(), 0);
                    }
                }
                if (base.terrainAware.get()) {
                    direction = base.avoidanceFan.get() ? avoid(direction) : climbIfBlocked(direction);
                }
                // convergence: inside the brake distance, stop sprinting
                // through the target and settle into a trailing follow
                double dist = direction.length();
                double convergeRadius = Math.max(4.0D, cruise * 3.0D);
                if (base.converge.get() && dist < convergeRadius) {
                    double scale = 0.35D + 0.65D * (dist / convergeRadius);
                    direction = direction.normalize().multiply(cruise * scale);
                }
                movementDirection = direction;
            } else {
                // don't stall the moment the target dips out: fly the last
                // known course while searchTarget keeps looking
                Vec3d ghost = lostCourse(cruise);
                movementDirection = ghost != null ? ghost : Vec3d.ZERO;
            }
        }

        /** raycast along the heading; steer up before we eat terrain */
        private Vec3d climbIfBlocked(Vec3d direction) {
            Vec3d look = direction.normalize();
            if (look.lengthSquared() < 1E-6) return direction;
            double lookahead = base.terrainLookahead.get();
            Vec3d eye = mc.player.getEyePos();
            BlockHitResult hit = mc.world.raycast(new RaycastContext(
                    eye,
                    eye.add(look.multiply(lookahead)),
                    RaycastContext.ShapeType.COLLIDER,
                    RaycastContext.FluidHandling.NONE,
                    mc.player));
            if (hit.getType() == HitResult.Type.MISS) {
                return direction;
            }
            double proximity = 1.0D - (eye.distanceTo(hit.getPos()) / lookahead);
            // steeper climb the closer the wall, plus a nudge along the wall face
            Vec3d climb = direction.normalize().add(0, 1.5D + 2.5D * proximity, 0);
            Direction face = hit.getSide();
            if (face != null) {
                climb = climb.add(face.getOffsetX() * 0.3D, 0, face.getOffsetZ() * 0.3D);
            }
            return climb.multiply(direction.length());
        }

        @Override
        public void onEnable() {}

        @Override
        public void onDisable() {}
    }

    // ---------------------------------------------------------------
    // MACE: totem-gated dive state machine
    // ---------------------------------------------------------------

    public static class MaceV2 extends Behaviour implements HitListenerV2 {
        static final int STATE_NONE = 0;
        static final int STATE_PULL_UP = 1;
        static final int STATE_FOLLOW = 2;
        static final int STATE_WAIT_ATTACK = 3;
        static final int STATE_HOVER = 4;

        StateMachine stateMachine;
        int startWaitAttack = -1;
        int startPullUpTick = 0;
        double hoverAngle = 0.0D;
        int lastMaceAttackTick = Integer.MIN_VALUE;
        int lastMaceAttackSuccessTick = Integer.MIN_VALUE;
        boolean attackFlag = false;
        double lastFallDistance = 0.0D;
        boolean wasTargetUsingSpear = false;

        public MaceV2() {
            stateMachine = new StateMachine(
                    STATE_NONE,
                    this::onCondition,
                    this::onStateNone,
                    this::onStatePullUp,
                    this::onStateFollow,
                    this::onStateWaitAttack,
                    this::onStateHover);
            stateMachine.registerListener(STATE_WAIT_ATTACK, this::onStartWaitAttack);
            stateMachine.registerListener(STATE_PULL_UP, this::onStartPullUp);
        }

        public int onCondition(StateMachine machine, int state) {
            if (base.target == null) {
                // ghost course on the last sighting instead of stalling mid-air
                Vec3d ghost = lostCourse(Math.max(0.8D, mc.player.getVelocity().length()));
                if (ghost != null) {
                    movementDirection = ghost;
                    machine.markForEndState();
                    return state;
                }
                machine.markForEndState();
                movementDirection = Vec3d.ZERO;
                return STATE_NONE;
            }
            return state;
        }

        public void onStartPullUp(boolean on) {
            if (on) {
                startPullUpTick = Tasks.getTick();
            } else {
                startPullUpTick = 0;
            }
        }

        public void onStartWaitAttack(boolean on) {
            startWaitAttack = 0;
        }

        public int onStateNone(StateMachine machine) {
            if (base.target != null) {
                if (PlayerStateManager.INSTANCE.fallDistance > 4
                        && mc.player.getPos().getY() > base.target.getPos().getY() + 4.0D) {
                    return STATE_FOLLOW;
                }
                return STATE_PULL_UP;
            }
            movementDirection = Vec3d.ZERO;
            machine.markForEndState();
            return STATE_NONE;
        }

        public int onStatePullUp(StateMachine machine) {
            // climb-out is also the long approach: intercept a runner's course
            // (XZ only — altitude stays owned by the pull-up state machine)
            Vec3d predictor = approachAimHorizontal(base.predictTargetPos(), base.interceptLead.get());
            if (shouldPullUpEating()) {
                // keep distance while we must eat
                setTargetToPlayerUpper(predictor);
                machine.markForEndState();
                return STATE_PULL_UP;
            }
            Vec3d testMovement = new Vec3d(0, 0.1, 0);
            Vec3d simulation = MovTasks.simulateMovement(mc.player, mc.player.getPos(), testMovement, true);
            boolean headBlocked = simulation.squaredDistanceTo(testMovement) > 1E-4;
            if (headBlocked) {
                // can not pull up here: try to follow out
                return STATE_FOLLOW;
            }
            double targetY =
                    base.target.getY() + (base.currentOnGround ? base.maceHeightGround.get() : base.maceHeight.get());
            boolean mayFollow = (mc.player.getY() >= targetY)
                    || (startPullUpTick != 0 && Tasks.getTick() > startPullUpTick + base.maceMaxPullUpTick.get());
            if (mayFollow) {
                // conservative play only: hover while their totem is up (off by
                // default — a totem is one extra life, breaking it IS progress)
                if (base.totemHoverActive()) {
                    return STATE_HOVER;
                }
                return STATE_FOLLOW;
            }
            setTargetToPlayerUpper(predictor);
            machine.markForEndState();
            return STATE_PULL_UP;
        }

        public int onStateHover(StateMachine machine) {
            if (!base.totemHoverActive()) {
                // totem gone (popped or never there): dive window
                machine.markForEndState();
                return STATE_FOLLOW;
            }
            if (base.currentAction == TargetActionV2.ESCAPING || base.currentAction == TargetActionV2.PEARL_ESCAPE) {
                // target running: cut the orbit and reposition on its course
                setTargetToPlayerUpper(approachAimHorizontal(base.predictTargetPos(), base.interceptLead.get()));
                machine.markForEndState();
                return STATE_HOVER;
            }
            hoverAngle += (2 * Math.PI) / 40.0D;
            double radius = base.combatMaceRange.get() + 2.0D;
            Vec3d orbitPoint = base.target
                    .getPos()
                    .add(Math.cos(hoverAngle) * radius, base.totemHoverHeight.get(), Math.sin(hoverAngle) * radius);
            movementDirection = avoid(orbitPoint.subtract(mc.player.getPos()));
            if (movementDirection.length() < 5) {
                movementDirection = movementDirection.normalize().multiply(5);
            }
            machine.markForEndState();
            return STATE_HOVER;
        }

        public int onStateFollow(StateMachine machine) {
            if (PlayerStateManager.INSTANCE.fallDistance < 1E-6 && lastFallDistance > 1E-6) {
                // fall distance reset mid-chase: regain height first
                return STATE_PULL_UP;
            }
            if (shouldPullUpEating()) {
                return STATE_PULL_UP;
            }
            // conservative play only: abort the dive when they re-totemed
            if (base.totemHoverActive() && !base.currentInCombatRange && mc.player.getY() > base.target.getY() + 6.0D) {
                return STATE_HOVER;
            }
            Vec3d targetPos = base.predictTargetPos();
            double attackRange = CombatTasks.getCombatExtra().getAttackAtTargetRange(base.target);
            // terminal guidance: inside the strike window the aim snaps from
            // the attack-predict point to the live hitbox center — a
            // sidestepping target cannot slip the final ticks of the dive
            double strikeDist = mc.player.getPos().distanceTo(base.target.getPos());
            if (strikeDist < attackRange * 1.6D) {
                targetPos = base.target.getPos().add(0, base.target.getHeight() * 0.5D, 0);
            }
            boolean targetInRange = TargetSelector.INSTANCE.isWithinAttackRange(
                    mc.player.getPos(), base.target.getBoundingBox(), attackRange);
            boolean mayAttack = shouldAttackSimple() || shouldAttackMace();
            if (mayAttack && targetInRange) {
                setTargetToPlayer(targetPos);
                scheduleAttack();
                machine.markForEndState();
                return STATE_WAIT_ATTACK;
            }
            Vec3d testMovement = new Vec3d(0, -0.1, 0);
            Vec3d simulation = MovTasks.simulateMovement(mc.player, mc.player.getPos(), testMovement, true);
            boolean feetBlocked = simulation.squaredDistanceTo(testMovement) > 1E-4;
            // kill window: stay glued to the target, tolerate a bigger height
            // deficit before giving up the dive
            double followTolerance = base.maceFollowMinHeight.get() + (base.killWindowOpen() ? 3.0D : 0.0D);
            boolean needPullUp = feetBlocked || base.target.getY() > mc.player.getY() + followTolerance;
            if (needPullUp) {
                if (targetInRange) {
                    setTargetToPlayer(targetPos);
                    scheduleAttack();
                    machine.markForEndState();
                    return STATE_WAIT_ATTACK;
                }
                return STATE_PULL_UP;
            }
            setTargetToPlayer(targetPos);
            // dive-speed shaping (from the v1 mace): the steeper the dive the
            // harder we brake — more ticks spent inside the strike window
            // instead of blowing straight through it
            double verticalShare = 0.0D;
            if (movementDirection.lengthSquared() > 1E-9) {
                verticalShare = Math.abs(movementDirection.normalize().y);
            }
            base.behaviourSpeedMultiplier = verticalShare > 0.75D ? Math.min(1.0D, 0.75D / verticalShare) : 1.0D;
            machine.markForEndState();
            return STATE_FOLLOW;
        }

        public int onStateWaitAttack(StateMachine machine) {
            if (base.currentAction != TargetActionV2.AFK && base.currentAction != TargetActionV2.SLOW_SPEED) {
                machine.markForEndState();
                return STATE_PULL_UP;
            }
            if (++startWaitAttack > 1) {
                if (base.currentOnGround) {
                    return STATE_NONE;
                } else {
                    return STATE_FOLLOW;
                }
            }
            machine.markForEndState();
            Vec3d targetPos = base.predictTargetPos().withAxis(Direction.Axis.Y, base.target.getY());
            setTargetToPlayer(targetPos);
            return STATE_WAIT_ATTACK;
        }

        protected void setTargetToPlayerUpper(Vec3d predictor) {
            Vec3d movement = predictor
                    .withAxis(Direction.Axis.Y, predictor.getY() + base.maceHeight.get())
                    .subtract(mc.player.getPos());
            if (movement.length() < 5) {
                movement = movement.normalize().multiply(5);
            }
            movementDirection = avoid(movement);
        }

        protected void setTargetToPlayer(Vec3d targetPos) {
            Vec3d movement;
            if (base.isTargetUsingSpear() && SpearEnhance.isUsingSpear(mc.player)) {
                movement = targetPos
                        .add(0, base.target.getEyeHeight(base.target.getPose()), 0)
                        .subtract(mc.player.getEyePos())
                        .normalize();
            } else {
                movement = targetPos.subtract(mc.player.getPos()).normalize();
            }
            if (base.currentOnGround) {
                var op = base.followOnGroundHeight.get();
                if (op.isPresent()) {
                    movement = movement.add(0, op.getValue(), 0);
                }
            } else if (targetPos.y > mc.player.getY()) {
                // never chase upward in a mace dive: keep the height advantage
                movement = movement.withAxis(Direction.Axis.Y, 0);
            }
            // v2 feint: alternate lateral drift while they aim a spear at us
            if (base.isTargetUsingSpear()) {
                double magnitude = base.dodgeIntensity.get() * 0.8D;
                movement = movement.add(lateralDodge(movement, magnitude)).normalize();
            }
            if (movement.length() < 5) {
                movement = movement.multiply(5);
            }
            movementDirection = movement;
        }

        public boolean shouldPullUpEating() {
            return CombatTasks.getAttackAura().checkEating();
        }

        // v2: cadence gates live here, every attack path goes through them
        public boolean shouldAttackSimple() {
            // charged spear stab: this IS the kinetic damage path
            if (SpearEnhance.isUsingSpear(mc.player)) {
                return SpearEnhance.canSpearKineticAttack(mc.player) && base.attackReady() && base.spearStabReady();
            }
            if (Attack.shouldUseAntiShield(base.target)) return true;
            if (!base.attackReady()) return false;
            if (CombatTasks.getAttackAura().checkUsing()) return false;
            if (Attack.INSTANCE.willUseMaceAttack(false)) return false;
            return true;
        }

        public boolean shouldAttackMace() {
            boolean cooldown = lastMaceAttackSuccessTick < Tasks.getTick() || Attack.INSTANCE.willUseMaceAttack(false);
            return cooldown && PlayerStateManager.INSTANCE.fallDistance > 1.5D;
        }

        public boolean canAttackMace() {
            boolean cooldown = lastMaceAttackSuccessTick < Tasks.getTick();
            return Attack.INSTANCE.willUseMaceAttack(true)
                    && (cooldown || PlayerStateManager.INSTANCE.fallDistance > 1.5D);
        }

        public void scheduleAttack() {
            attackFlag = true;
        }

        @Override
        public Entity searchTarget() {
            return CombatTasks.getTargetSelector()
                    .searchAttackEntity(
                            base.targetRange.get(),
                            true,
                            base.playerOnly.get() ? (e) -> e instanceof PlayerEntity : null);
        }

        @Override
        public synchronized void onUpdate() {
            trackTargetSighting();
            // reset every tick: only the dive state shapes speed, and a stale
            // multiplier must not bleed into pull-up/hover/attack
            base.behaviourSpeedMultiplier = 1.0D;
            if (mc.player.isFallFlying() || mc.player.getAbilities().flying) {
                stateMachine.step();
                if (attackFlag) {
                    if (base.target != null) {
                        boolean spearCharged = SpearEnhance.isUsingSpear(mc.player);
                        if (shouldAttackSimple() && base.attackReady()) {
                            Attack.AttackSettings settings = CombatTasks.getAttack()
                                    .createAttackSettings()
                                    .withMaceSwap(false);
                            if (spearCharged) {
                                // keep the charging spear in hand: no weapon/inv
                                // swaps, no use-release, no anti-shield swap
                                settings = settings.withSelectWeapon(false)
                                        .withInvSwap(false)
                                        .withUseAttack(false)
                                        .withAntiShieldSwap(false);
                            } else if (Attack.shouldUseAntiShield(base.target)) {
                                settings = settings.withAntiShieldSwap(true);
                            }
                            CombatTasks.getAttack().attackEntity(base.target, settings);
                            base.markAttacked();
                        }
                        if (!spearCharged && canAttackMace() && base.attackReady()) {
                            Attack.AttackSettings settings = CombatTasks.getAttack()
                                    .createAttackSettings()
                                    .withMaceSwap(true)
                                    .withInvSwap(false)
                                    .withAntiShieldSwap(false);
                            CombatTasks.getAttack().attackEntity(base.target, settings);
                            lastMaceAttackTick = Tasks.getTick();
                            base.markAttacked();
                            Debug.debug("[ElytraBotV2] mace attack", Tasks.getTick());
                        }
                    }
                    attackFlag = false;
                }
            } else {
                stateMachine.setState(STATE_NONE);
                movementDirection = Vec3d.ZERO;
            }
            lastFallDistance = PlayerStateManager.INSTANCE.fallDistance;
            wasTargetUsingSpear = base.isTargetUsingSpear();
        }

        @Override
        public synchronized void onPauseControl() {
            stateMachine.setState(STATE_NONE);
        }

        @Override
        public void onEnable() {
            stateMachine.setState(STATE_NONE);
        }

        @Override
        public void onDisable() {}

        @Override
        public synchronized void onHit(int type) {
            if (lastMaceAttackTick > Tasks.getTick() - 5 && stateMachine.getState() != STATE_PULL_UP) {
                lastMaceAttackSuccessTick = Tasks.getTick();
                stateMachine.setState(STATE_PULL_UP);
            }
        }

        @Override
        public synchronized void onAttack(Entity entity) {
            if ((mc.player.isFallFlying() || mc.player.getAbilities().flying)
                    && stateMachine.getState() != STATE_PULL_UP) {
                if (lastMaceAttackTick >= Tasks.getTick() - 1) {
                    stateMachine.setState(STATE_PULL_UP);
                    stateMachine.step();
                } else {
                    stateMachine.setState(STATE_FOLLOW);
                }
            }
        }
    }

    // ---------------------------------------------------------------
    // SPEAR: feint orbit + charged-spear hard dodge + spend window engage
    // ---------------------------------------------------------------

    public static class SpearV2 extends Behaviour implements HitListenerV2 {
        static final int STATE_ORBIT = 0;
        static final int STATE_ENGAGE = 1;
        static final int STATE_DODGE = 2;

        double orbitAngle = 0.0D;
        int dodgeTicksLeft = 0;
        int dodgeCooldownUntil = Integer.MIN_VALUE;
        int feintSign = 1;
        int nextFeintFlip = 0;
        int engageUntil = Integer.MIN_VALUE;
        int reengageCooldownUntil = Integer.MIN_VALUE;
        boolean wasTargetUsingSpear = false;

        StateMachine stateMachine;

        public SpearV2() {
            stateMachine = new StateMachine(
                    STATE_ORBIT, this::onCondition, this::onStateOrbit, this::onStateEngage, this::onStateDodge);
        }

        public int onCondition(StateMachine machine, int state) {
            if (base.target == null) {
                // ghost course on the last sighting instead of stalling mid-air
                Vec3d ghost = lostCourse(Math.max(0.8D, mc.player.getVelocity().length()));
                if (ghost != null) {
                    movementDirection = ghost;
                    machine.markForEndState();
                    return state;
                }
                machine.markForEndState();
                movementDirection = Vec3d.ZERO;
                return STATE_ORBIT;
            }
            return state;
        }

        @Override
        public Entity searchTarget() {
            return CombatTasks.getTargetSelector()
                    .searchAttackEntity(
                            base.targetRange.get(),
                            true,
                            base.playerOnly.get() ? (e) -> e instanceof PlayerEntity : null);
        }

        public int onStateOrbit(StateMachine machine) {
            // their spear was spent (or swapped away): strike window
            boolean spearDropped = wasTargetUsingSpear && !base.isTargetUsingSpear();
            if (spearDropped || Tasks.getTick() < engageUntil) {
                if (spearDropped) {
                    engageUntil = Tasks.getTick() + 40;
                }
                // their spear is spent — engage regardless of totem: breaking a
                // totem is progress, not a reason to hold fire
                return STATE_ENGAGE;
            }
            // kill window: their totem just popped, dive in immediately
            if (base.killWindowOpen()) {
                return STATE_ENGAGE;
            }
            if (Tasks.getTick() >= dodgeCooldownUntil && base.isTargetUsingSpear() && spearThreatens()) {
                dodgeTicksLeft = 5;
                dodgeCooldownUntil = Tasks.getTick() + 20;
                flipFeint();
                machine.markForEndState();
                return STATE_DODGE;
            }
            // proactive strike: our spear is charged and the impact gate is
            // open — press instead of circling while theirs stays in hand
            if (Tasks.getTick() >= reengageCooldownUntil
                    && SpearEnhance.isUsingSpear(mc.player)
                    && SpearEnhance.canSpearKineticAttack(mc.player)
                    && base.spearStabReady()
                    && base.attackReady()) {
                engageUntil = Tasks.getTick() + 40;
                reengageCooldownUntil = engageUntil + 20;
                return STATE_ENGAGE;
            }
            Vec3d heading = orbitHeading();
            movementDirection = avoid(heading);
            machine.markForEndState();
            return STATE_ORBIT;
        }

        public int onStateDodge(StateMachine machine) {
            if (--dodgeTicksLeft <= 0) {
                machine.markForEndState();
                return STATE_ORBIT;
            }
            Vec3d away = mc.player.getPos().subtract(base.target.getPos());
            Vec3d horizontalAway = away.withAxis(Direction.Axis.Y, 0).normalize();
            if (horizontalAway.lengthSquared() < 1E-4) {
                horizontalAway = new Vec3d(1, 0, 0);
            }
            Vec3d burst = horizontalAway
                    .multiply(4.0D * base.dodgeIntensity.get())
                    .add(lateralDodge(horizontalAway, 8.0D * base.dodgeIntensity.get() * feintSign))
                    .add(0, 1.0D, 0);
            movementDirection = burst;
            machine.markForEndState();
            return STATE_DODGE;
        }

        public int onStateEngage(StateMachine machine) {
            if (Tasks.getTick() >= dodgeCooldownUntil && base.isTargetUsingSpear() && spearThreatens()) {
                dodgeTicksLeft = 5;
                dodgeCooldownUntil = Tasks.getTick() + 20;
                flipFeint();
                machine.markForEndState();
                return STATE_DODGE;
            }
            if (Tasks.getTick() >= engageUntil) {
                machine.markForEndState();
                return STATE_ORBIT;
            }
            Vec3d targetPos = base.predictTargetPos();
            double attackRange = CombatTasks.getCombatExtra().getAttackAtTargetRange(base.target);
            boolean farApproach = mc.player.getPos().distanceTo(base.target.getPos()) > attackRange + 8.0D;
            if (farApproach) {
                // still closing in: cut the course, steer around terrain; the
                // final dive keeps the raw attack predict for accuracy
                targetPos = approachAimHorizontal(targetPos, base.interceptLead.get() * 0.7D);
            }
            setTargetToPlayer(targetPos);
            if (farApproach) {
                movementDirection = avoid(movementDirection);
            }
            boolean inRange = TargetSelector.INSTANCE.isWithinAttackRange(
                    mc.player.getPos(),
                    base.target.getBoundingBox(),
                    CombatTasks.getCombatExtra().getAttackAtTargetRange(base.target));
            if (inRange && base.attackReady()) {
                boolean spearCharged = SpearEnhance.isUsingSpear(mc.player);
                if (!spearCharged || (SpearEnhance.canSpearKineticAttack(mc.player) && base.spearStabReady())) {
                    Attack.AttackSettings settings =
                            CombatTasks.getAttack().createAttackSettings().withMaceSwap(false);
                    if (spearCharged) {
                        settings = settings.withSelectWeapon(false)
                                .withInvSwap(false)
                                .withUseAttack(false)
                                .withAntiShieldSwap(false);
                    } else if (Attack.shouldUseAntiShield(base.target)) {
                        settings = settings.withAntiShieldSwap(true);
                    }
                    CombatTasks.getAttack().attackEntity(base.target, settings);
                    base.markAttacked();
                }
            }
            machine.markForEndState();
            return STATE_ENGAGE;
        }

        /** charged spear inside its range, aimed at our hemisphere */
        boolean spearThreatens() {
            if (!(base.target instanceof PlayerEntity player)) return false;
            double distance = mc.player.getPos().distanceTo(player.getPos());
            if (distance > base.combatSpearRange.get() + 3.0D) return false;
            if (!SpearEnhance.canSpearKineticAttack(player)) return false;
            // actually aimed at us: a charged spear staring away threatens
            // nobody. without this check the orbit (radius spearRange+2) sits
            // inside the threat bubble (spearRange+3) forever and would dodge
            // every single tick while they merely hold the spear
            Vec3d toUs = mc.player.getPos().subtract(player.getPos()).normalize();
            return player.getRotationVector().dotProduct(toUs) > 0.25D;
        }

        Vec3d orbitHeading() {
            if (Tasks.getTick() >= nextFeintFlip) {
                flipFeint();
                nextFeintFlip = Tasks.getTick() + base.dodgeCycleTicks.get();
            }
            // chasing: a runner (or a target still far away) is not pressed by
            // an orbit — cut across its course until we are in spear range
            boolean chasing = base.currentAction == TargetActionV2.ESCAPING
                    || mc.player.getPos().subtract(base.target.getPos()).horizontalLength()
                            > base.combatSpearRange.get() * 2.5D;
            Vec3d heading;
            if (chasing) {
                Vec3d aim = approachAimHorizontal(base.predictTargetPos(), base.interceptLead.get());
                heading = aim.subtract(mc.player.getPos());
            } else {
                orbitAngle += (2 * Math.PI) / 36.0D;
                double radius = base.combatSpearRange.get() + 2.0D;
                // wave the orbit up/down: the periodic dip banks spear-charge
                // fall distance, so a full charge is available to press with
                double bob = 3.0D * Math.sin(2.0D * orbitAngle);
                Vec3d orbitPoint = base.target
                        .getPos()
                        .add(Math.cos(orbitAngle) * radius, 6.0D + bob, Math.sin(orbitAngle) * radius);
                heading = orbitPoint.subtract(mc.player.getPos());
            }
            if (base.isTargetUsingSpear()) {
                // feint: persistent small lateral drift that flips on a cycle
                heading = heading.add(lateralDodge(heading, 0.5D * base.dodgeIntensity.get() * feintSign));
            }
            if (heading.length() < 5) {
                heading = heading.normalize().multiply(5);
            }
            return heading;
        }

        void flipFeint() {
            feintSign = RANDOM.nextBoolean() ? 1 : -1;
        }

        void setTargetToPlayer(Vec3d targetPos) {
            Vec3d movement = targetPos.subtract(mc.player.getPos()).normalize();
            if (movement.length() < 5) {
                movement = movement.multiply(5);
            }
            movementDirection = movement;
        }

        @Override
        public synchronized void onUpdate() {
            trackTargetSighting();
            stateMachine.step();
            wasTargetUsingSpear = base.isTargetUsingSpear();
        }

        @Override
        public synchronized void onHit(int type) {
            if (type == HitListenerV2.HIT_SPEAR) {
                // their spear connected on us: it is spent, counterattack now
                engageUntil = Tasks.getTick() + 40;
                stateMachine.setState(STATE_ENGAGE);
            }
        }

        @Override
        public void onEnable() {
            engageUntil = Integer.MIN_VALUE;
            reengageCooldownUntil = Integer.MIN_VALUE;
            dodgeCooldownUntil = Integer.MIN_VALUE;
            stateMachine.setState(STATE_ORBIT);
        }

        @Override
        public void onDisable() {
            stateMachine.setState(STATE_ORBIT);
        }
    }

    public enum TargetActionV2 {
        ESCAPING,
        TOWARDS,
        CIRCLING,
        SLOW_SPEED,
        AFK,
        /** target has a pearl in the air: intercept the landing point */
        PEARL_ESCAPE
    }

    public enum Mode implements ConfigEnum {
        FOLLOW,
        MACE,
        SPEAR;

        @Override
        public String getConfigEnumType() {
            return "elytra_bot_v2_mode";
        }
    }
}
