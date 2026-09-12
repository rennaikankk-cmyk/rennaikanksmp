package me.matl114.hacks.utils.move;

import java.util.List;
import me.matl114.accessors.hacks.PlayerInternalAccess;
import me.matl114.hacks.utils.entity.PredictorImpl;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

/**
 * Shared pursuit guidance for the elytra chase behaviours (ElytraBot v1/v2).
 *
 * - estimateTargetVelocity: per-tick velocity from the tracked server-synced
 *   known positions, falling back to the client-interpolated velocity
 * - interceptPoint: solves the rendezvous equation so a fleeing target is cut
 *   off instead of tail-chased
 * - steerAroundTerrain: fan of raycasts, pick the cheapest clear heading so we
 *   keep speed around ridges instead of stalling into a vertical climb
 */
public final class PursuitUtils {
    private PursuitUtils() {}

    private static final MinecraftClient mc = MinecraftClient.getInstance();

    /** per-tick velocity of the target, from tracked server positions when available */
    public static Vec3d estimateTargetVelocity(Entity target) {
        if (target instanceof PlayerEntity) {
            List<PredictorImpl.KnownPosition> positions =
                    ((PlayerInternalAccess) target).getPredictorImpl().getLastKnownPositions(3);
            if (positions.size() >= 2) {
                PredictorImpl.KnownPosition oldest = positions.get(0);
                PredictorImpl.KnownPosition newest = positions.get(positions.size() - 1);
                int dt = newest.tick() - oldest.tick();
                if (dt > 0) {
                    return newest.vec3d().subtract(oldest.vec3d()).multiply(1.0D / dt);
                }
            }
        }
        return target.getVelocity();
    }

    /**
     * rendezvous point: where to fly to meet a moving target. solves
     * |T + Vt*t - P| = Vp*t for the smallest positive t; when the course has no
     * solution (we cannot close on this heading) falls back to a lead point at
     * t = |T-P|/Vp. t is capped so stale velocity cannot project the target
     * across the world. leadScale in [0,1] tones the prediction down for
     * jittery targets.
     */
    public static Vec3d interceptPoint(
            Vec3d playerPos,
            double playerSpeed,
            Vec3d targetPos,
            Vec3d targetVel,
            double leadScale,
            double maxLeadTicks) {
        double scale = Math.max(0.0D, Math.min(1.0D, leadScale));
        Vec3d vel = targetVel.multiply(scale);
        Vec3d rel = targetPos.subtract(playerPos);
        double a = vel.dotProduct(vel) - playerSpeed * playerSpeed;
        double b = 2.0D * rel.dotProduct(vel);
        double c = rel.dotProduct(rel);
        double t = -1.0D;
        if (Math.abs(a) < 1E-9D) {
            if (b < -1E-9D) {
                t = -c / b;
            }
        } else {
            double disc = b * b - 4.0D * a * c;
            if (disc >= 0.0D) {
                double sq = Math.sqrt(disc);
                double t1 = (-b - sq) / (2.0D * a);
                double t2 = (-b + sq) / (2.0D * a);
                if (t1 > 0.0D && t2 > 0.0D) {
                    t = Math.min(t1, t2);
                } else {
                    t = Math.max(t1, t2);
                }
            }
        }
        if (t < 0.0D) {
            t = playerSpeed > 1E-6D ? rel.length() / playerSpeed : 0.0D;
        }
        t = Math.max(0.0D, Math.min(maxLeadTicks, t));
        return targetPos.add(vel.multiply(t));
    }

    /** candidate headings {yaw, pitch} offsets in degrees, ordered by turn cost */
    private static final double[][] FAN_CANDIDATES = {
        {14, 0}, {-14, 0},
        {30, 0}, {-30, 0},
        {48, 0}, {-48, 0},
        {0, -15}, {0, 15},
        {30, -15}, {-30, -15},
    };

    private static final double BLOCKED_COST = 4.0D;

    /**
     * terrain avoidance by fan sampling: raycast each candidate heading, keep
     * the cheapest clear one (smallest turn), so we side-step ridges at speed
     * instead of ballooning straight up. the fan spans yaw offsets plus pitch
     * variants so near-vertical pursuits (chasing a climber, nether ceiling)
     * still have lateral options. when everything sampled is blocked, slides
     * along the wall face instead of climbing harder. returns a vector with
     * the same length as the input direction.
     */
    public static Vec3d steerAroundTerrain(Vec3d eye, Vec3d desired, double lookahead) {
        double len = desired.length();
        if (len < 1E-6D || mc.world == null || mc.player == null) {
            return desired;
        }
        Vec3d dir = desired.normalize();
        BlockHitResult centerHit = raycast(eye, dir, lookahead);
        if (centerHit.getType() == HitResult.Type.MISS) {
            return desired;
        }
        double baseYaw = Math.atan2(-dir.x, dir.z);
        double basePitch = -Math.asin(Math.max(-1.0D, Math.min(1.0D, dir.y)));
        Vec3d best = null;
        double bestCost = Double.MAX_VALUE;
        for (double[] off : FAN_CANDIDATES) {
            Vec3d cand = fromYawPitch(baseYaw + Math.toRadians(off[0]), basePitch + Math.toRadians(off[1]));
            BlockHitResult hit = raycast(eye, cand, lookahead);
            double cost;
            if (hit.getType() == HitResult.Type.MISS) {
                cost = Math.abs(off[0]) / 48.0D + Math.abs(off[1]) / 30.0D;
            } else {
                double dist = eye.distanceTo(hit.getPos());
                cost = BLOCKED_COST + (1.0D - dist / lookahead) * 2.0D;
            }
            if (cost < bestCost) {
                bestCost = cost;
                best = cand;
            }
        }
        if (bestCost < BLOCKED_COST) {
            return best.multiply(len);
        }
        // everything sampled is blocked: slide along the wall face; climbing
        // harder here is how you get stuck under a nether ceiling
        return slideAlongFace(centerHit, dir, len);
    }

    /** keep speed along the hit face instead of stalling into it */
    private static Vec3d slideAlongFace(BlockHitResult hit, Vec3d dir, double len) {
        Direction face = hit.getSide();
        Vec3d horizontal = dir.withAxis(Direction.Axis.Y, 0);
        if (horizontal.lengthSquared() < 1E-4) {
            horizontal = new Vec3d(1, 0, 0);
        }
        Vec3d slide;
        if (face == Direction.DOWN) {
            // ceiling: press down and away
            slide = horizontal.normalize().add(0, -0.2D, 0);
        } else if (face == Direction.UP) {
            // floor: lift off gently
            slide = horizontal.normalize().add(0, 0.4D, 0);
        } else {
            Vec3d n = new Vec3d(face.getOffsetX(), 0, face.getOffsetZ());
            Vec3d tangent = new Vec3d(0, 1, 0).crossProduct(n).normalize();
            if (tangent.dotProduct(horizontal) < 0) {
                tangent = tangent.negate();
            }
            // hug the wall sideways with a slight lift to peel off
            slide = tangent.add(0, 0.25D, 0);
        }
        return slide.normalize().multiply(len);
    }

    private static BlockHitResult raycast(Vec3d eye, Vec3d dir, double lookahead) {
        return mc.world.raycast(new RaycastContext(
                eye,
                eye.add(dir.multiply(lookahead)),
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,
                mc.player));
    }

    /** MC convention: yaw around Y, pitch positive down */
    private static Vec3d fromYawPitch(double yaw, double pitch) {
        double cp = Math.cos(pitch);
        return new Vec3d(-Math.sin(yaw) * cp, -Math.sin(pitch), Math.cos(yaw) * cp);
    }
}
