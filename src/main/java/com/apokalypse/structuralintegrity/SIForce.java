package com.apokalypse.structuralintegrity;

import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.util.List;

/**
 * The one way this mod pushes a sub-level: a direction and a magnitude.
 *
 * Everything that wants to shove something calls {@link #apply}. There are two
 * callers and they differ only in the numbers they pass - TNT pushes hard and
 * outward from the blast, a collapse pushes gently and away from whatever gave
 * way underneath it.
 *
 * A one-shot impulse, not a queued force. Sable resets every queued force group
 * at the start of each physics tick, so a group is the wrong tool for a blast:
 * it would have to be re-applied every tick to mean anything. An impulse goes
 * straight into the body's momentum once and is done.
 *
 * Callers think in world directions - outward from a blast, away from whatever
 * gave way underneath - but sable does not take one. Rapier rotates every impulse
 * by the body's own orientation before applying it, so what reaches the physics
 * engine is body-local, and a world vector handed over raw is only correct while
 * the body happens to be unrotated. Freshly assembled sub-levels are unrotated,
 * which is exactly why this never showed up in testing; a tumbling one would have
 * been shoved sideways. {@link #apply} converts through the pose the same way
 * sable's own {@code /sable physics impulse ... global} command does.
 */
public final class SIForce {
    private SIForce() {}

    /** Directions with no length to normalise are ignored rather than guessed at. */
    private static final double MIN_DIRECTION_LENGTH = 1.0e-6;

    /**
     * Push a sub-level.
     *
     * @param subLevel  the body to push
     * @param direction which way, in WORLD space, any length - normalised here and
     *                  converted into the body's own frame before it is applied
     * @param magnitude how hard. With forceScalesWithMass on (the default) this
     *                  reads as a velocity change in m/s and means the same thing
     *                  for a four-block chunk and a four-hundred-block wall;
     *                  with it off it is a raw impulse in N s and a heavy body
     *                  will barely notice a number that launches a light one.
     * @return true if the impulse reached the physics pipeline
     */
    public static boolean apply(ServerSubLevel subLevel, Vec3 direction, double magnitude) {
        if (magnitude <= 0.0) {
            return false;
        }
        double len = direction.length();
        if (!(len > MIN_DIRECTION_LENGTH) || !Double.isFinite(len)) {
            StructuralIntegrity.LOGGER.warn("[SI] force skipped: direction {} has no usable length", direction);
            return false;
        }

        RigidBodyHandle handle = RigidBodyHandle.of(subLevel);
        if (handle == null || !handle.isValid()) {
            StructuralIntegrity.LOGGER.warn("[SI] force skipped: no valid rigid body handle for sub-level {}",
                    System.identityHashCode(subLevel));
            return false;
        }

        double scale = magnitude;
        if (SIConfig.forceScalesWithMass()) {
            double mass = subLevel.getMassTracker().getMass();
            if (!(mass > 0.0) || !Double.isFinite(mass)) {
                StructuralIntegrity.LOGGER.warn("[SI] force skipped: sub-level {} reports mass {}",
                        System.identityHashCode(subLevel), mass);
                return false;
            }
            scale *= mass;
        }

        // Normalised in world space first, so the magnitude means what the caller
        // meant by it. Only then into the body's frame - the pose carries a scale as
        // well as a rotation, so the converted vector is re-normalised rather than
        // trusted to have kept its length.
        Vec3 worldUnit = direction.scale(1.0 / len);
        Vec3 local = subLevel.logicalPose().transformNormalInverse(worldUnit);
        double localLen = local.length();
        if (!(localLen > MIN_DIRECTION_LENGTH) || !Double.isFinite(localLen)) {
            StructuralIntegrity.LOGGER.warn("[SI] force skipped: direction {} vanished converting to "
                    + "the frame of sub-level {}", direction, System.identityHashCode(subLevel));
            return false;
        }
        double localScale = scale / localLen;

        Vector3d impulse = new Vector3d(local.x * localScale, local.y * localScale, local.z * localScale);
        handle.applyLinearImpulse(impulse);

        StructuralIntegrity.LOGGER.info("[SI] FORCE sub-level={} worldDir=({},{},{}) magnitude={} "
                        + "-> localImpulse=({},{},{})",
                System.identityHashCode(subLevel),
                fmt(worldUnit.x), fmt(worldUnit.y), fmt(worldUnit.z),
                fmt(magnitude), fmt(impulse.x), fmt(impulse.y), fmt(impulse.z));
        return true;
    }

    /**
     * Where a sub-level actually is, in world coordinates.
     *
     * The pose's translation is by definition the world position of the point the
     * body rotates about, which sable sets to the centre of mass. Reading it costs
     * nothing and, unlike transforming a local point through the pose, involves no
     * assumption about which frame the local point was expressed in.
     */
    public static Vec3 worldPositionOf(ServerSubLevel subLevel) {
        var p = subLevel.logicalPose().position();
        return new Vec3(p.x(), p.y(), p.z());
    }

    /**
     * The direction a collapsing piece should be pushed: away from the block whose
     * support gave way, toward where the mass actually sits.
     *
     * Flattened to horizontal, because gravity already supplies the downward part
     * and adding to it just drives the piece into the floor. A piece whose mass is
     * centred directly over the failure has no horizontal answer, so it gets a
     * straight-down nudge instead of an invented sideways one.
     */
    public static Vec3 toppleDirection(BlockPos failed, List<BlockPos> blocks) {
        double cx = 0.0;
        double cz = 0.0;
        for (BlockPos p : blocks) {
            cx += p.getX() + 0.5;
            cz += p.getZ() + 0.5;
        }
        cx = cx / blocks.size() - (failed.getX() + 0.5);
        cz = cz / blocks.size() - (failed.getZ() + 0.5);
        if (cx * cx + cz * cz < 1.0e-4) {
            return new Vec3(0.0, -1.0, 0.0);
        }
        return new Vec3(cx, 0.0, cz);
    }

    /**
     * How fast a sub-level is actually travelling, in m/s, asked of the physics
     * engine itself.
     *
     * Not {@code ServerSubLevel#latestLinearVelocity}: that field is not stored by
     * the engine but recomputed each tick from the difference between two poses, and
     * it reads zero for these bodies even while rapier reports them moving at forty
     * metres a second. Anything checking whether a push landed has to ask the engine.
     */
    public static Vec3 linearVelocityOf(ServerSubLevel subLevel) {
        RigidBodyHandle handle = RigidBodyHandle.of(subLevel);
        if (handle == null || !handle.isValid()) {
            return Vec3.ZERO;
        }
        var v = handle.getLinearVelocity();
        return new Vec3(v.x(), v.y(), v.z());
    }

    /**
     * How far a point is from the sub-level's world bounding box - zero if the point
     * is inside it.
     *
     * {@code SubLevel#boundingBox} is the plot-space bounds transformed through the
     * pose, so unlike a raw local point it is already in world coordinates and needs
     * no assumption about which frame anything was expressed in.
     */
    public static double distanceToBody(ServerSubLevel subLevel, Vec3 point) {
        var bb = subLevel.boundingBox();
        double dx = Math.max(Math.max(bb.minX() - point.x, point.x - bb.maxX()), 0.0);
        double dy = Math.max(Math.max(bb.minY() - point.y, point.y - bb.maxY()), 0.0);
        double dz = Math.max(Math.max(bb.minZ() - point.z, point.z - bb.maxZ()), 0.0);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static String fmt(double d) {
        return String.format(java.util.Locale.ROOT, "%.3f", d);
    }
}
