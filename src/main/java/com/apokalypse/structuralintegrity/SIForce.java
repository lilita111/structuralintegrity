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
 * {@link #applyTorque} is the same function for spin: an axis and a magnitude,
 * every guard and conversion identical, ending at a torque impulse instead of a
 * linear one. A collapse uses both - the linear push decides which way the piece
 * goes, the torque decides that it rolls rather than slides.
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
 * sable's own {@code /sable physics impulse ... global} command does, and
 * {@link #applyTorque} does the same for the axis - rapier rotates the torque by
 * the body's orientation on exactly the same line as the force.
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
     * Spin a sub-level.
     *
     * The angular twin of {@link #apply}, and deliberately its mirror image: same
     * guards, same world-space normalise, same conversion into the body's frame,
     * same mass scaling. Only the last line differs - a torque impulse rather than a
     * linear one. Rapier rotates both by the body's orientation before applying
     * them, so an axis needs converting exactly as a direction does.
     *
     * @param subLevel  the body to spin
     * @param axis      which way it turns, in WORLD space, right-handed about the
     *                  axis, any length - normalised here and converted into the
     *                  body's own frame before it is applied
     * @param magnitude how hard. Unlike {@link #apply}'s magnitude this is not a
     *                  velocity in disguise even with forceScalesWithMass on: rapier
     *                  divides a torque impulse by the moment of inertia, which grows
     *                  with the square of the body's size while mass grows with its
     *                  volume. Scaling by mass keeps a number meaning roughly the
     *                  same thing across sizes; it does not make it rad/s.
     * @return true if the torque impulse reached the physics pipeline
     */
    public static boolean applyTorque(ServerSubLevel subLevel, Vec3 axis, double magnitude) {
        if (magnitude <= 0.0) {
            return false;
        }
        double len = axis.length();
        if (!(len > MIN_DIRECTION_LENGTH) || !Double.isFinite(len)) {
            StructuralIntegrity.LOGGER.warn("[SI] torque skipped: axis {} has no usable length", axis);
            return false;
        }

        RigidBodyHandle handle = RigidBodyHandle.of(subLevel);
        if (handle == null || !handle.isValid()) {
            StructuralIntegrity.LOGGER.warn("[SI] torque skipped: no valid rigid body handle for sub-level {}",
                    System.identityHashCode(subLevel));
            return false;
        }

        double scale = magnitude;
        if (SIConfig.forceScalesWithMass()) {
            double mass = subLevel.getMassTracker().getMass();
            if (!(mass > 0.0) || !Double.isFinite(mass)) {
                StructuralIntegrity.LOGGER.warn("[SI] torque skipped: sub-level {} reports mass {}",
                        System.identityHashCode(subLevel), mass);
                return false;
            }
            scale *= mass;
        }

        Vec3 worldUnit = axis.scale(1.0 / len);
        Vec3 local = subLevel.logicalPose().transformNormalInverse(worldUnit);
        double localLen = local.length();
        if (!(localLen > MIN_DIRECTION_LENGTH) || !Double.isFinite(localLen)) {
            StructuralIntegrity.LOGGER.warn("[SI] torque skipped: axis {} vanished converting to "
                    + "the frame of sub-level {}", axis, System.identityHashCode(subLevel));
            return false;
        }
        double localScale = scale / localLen;

        Vector3d torque = new Vector3d(local.x * localScale, local.y * localScale, local.z * localScale);
        handle.applyTorqueImpulse(torque);

        StructuralIntegrity.LOGGER.info("[SI] TORQUE sub-level={} worldAxis=({},{},{}) magnitude={} "
                        + "-> localTorque=({},{},{})",
                System.identityHashCode(subLevel),
                fmt(worldUnit.x), fmt(worldUnit.y), fmt(worldUnit.z),
                fmt(magnitude), fmt(torque.x), fmt(torque.y), fmt(torque.z));
        return true;
    }

    /**
     * Set a sub-level moving and turning at the moment it is born, which is the one
     * moment an impulse cannot do it.
     *
     * {@link #apply} and {@link #applyTorque} hand rapier an impulse, and rapier
     * divides an impulse by the body's mass and moment of inertia to get the velocity
     * change. That works on a body that has been alive a while. It does nothing at all
     * on a body assembled this tick: sable builds the collider at zero density and
     * feeds the real mass in afterwards, so at assembly the engine still has a cached
     * inverse mass and inverse inertia of zero, and anything divided by them comes out
     * as no motion whatsoever. The log says so plainly - the identical call reads
     * {@code before=(0,0,0) after=(0,0,0)} against a mass of 8, then moves the same
     * body by forty metres a second once it has settled. It is not a matter of the
     * number being too small; nothing is arriving.
     *
     * So a newborn body is kicked rather than pushed, which is exactly what sable
     * itself does in the same situation: when it splits one sub-level off another, the
     * piece inherits its parent's motion through
     * {@code SubLevelAssemblyHelper.kickFromContainingSubLevel}, and that adds
     * velocity directly instead of applying an impulse. Rapier's
     * {@code addLinearAndAngularVelocity} is a plain {@code set_linvel(linvel + v)} and
     * {@code set_angvel(angvel + w)} - it never consults mass or inertia, so there is
     * nothing to be zero, and it never rotates its arguments, so both are world-space
     * and need none of the pose conversion an impulse needs.
     *
     * The trade is that mass is genuinely ignored: a four-block chunk and a
     * four-hundred-block wall leave at the same speed. For a collapse that is closer to
     * right than the alternative anyway, because both are in free fall regardless.
     *
     * @param subLevel  the body to set moving
     * @param direction which way it travels, in WORLD space, any length
     * @param speed     how fast, in m/s. Not scaled by mass, and
     *                  {@code forceScalesWithMass} does not apply here - there is no
     *                  impulse to scale.
     * @param axis      which way it turns, in WORLD space, right-handed, any length
     * @param spin      how fast it turns, in rad/s. A real angular speed, unlike
     *                  {@link #applyTorque}'s magnitude: 2.5 is about two fifths of a
     *                  turn a second.
     * @return true if anything was actually handed to the physics engine
     */
    public static boolean kick(ServerSubLevel subLevel, Vec3 direction, double speed,
                               Vec3 axis, double spin) {
        RigidBodyHandle handle = RigidBodyHandle.of(subLevel);
        if (handle == null || !handle.isValid()) {
            StructuralIntegrity.LOGGER.warn("[SI] kick skipped: no valid rigid body handle for sub-level {}",
                    System.identityHashCode(subLevel));
            return false;
        }

        Vector3d linear = scaledOrZero(direction, speed);
        Vector3d angular = scaledOrZero(axis, spin);
        if (linear.lengthSquared() == 0.0 && angular.lengthSquared() == 0.0) {
            return false;
        }

        // Read back on both sides. This is the only evidence that the kick landed -
        // the whole reason this method exists is that the call it replaces reported
        // success while changing nothing.
        Vector3d vBefore = handle.getLinearVelocity(new Vector3d());
        Vector3d wBefore = handle.getAngularVelocity(new Vector3d());
        handle.addLinearAndAngularVelocity(linear, angular);
        Vector3d vAfter = handle.getLinearVelocity(new Vector3d());
        Vector3d wAfter = handle.getAngularVelocity(new Vector3d());

        StructuralIntegrity.LOGGER.info("[SI] KICK sub-level={} mass={} v=({},{},{})->({},{},{}) "
                        + "w=({},{},{})->({},{},{})",
                System.identityHashCode(subLevel), fmt(subLevel.getMassTracker().getMass()),
                fmt(vBefore.x), fmt(vBefore.y), fmt(vBefore.z),
                fmt(vAfter.x), fmt(vAfter.y), fmt(vAfter.z),
                fmt(wBefore.x), fmt(wBefore.y), fmt(wBefore.z),
                fmt(wAfter.x), fmt(wAfter.y), fmt(wAfter.z));
        return true;
    }

    /**
     * A world direction rescaled to {@code magnitude}, or zero if there is nothing to
     * scale - a zero magnitude, or a direction with no usable length to normalise.
     */
    private static Vector3d scaledOrZero(Vec3 direction, double magnitude) {
        if (!(magnitude > 0.0)) {
            return new Vector3d();
        }
        double len = direction.length();
        if (!(len > MIN_DIRECTION_LENGTH) || !Double.isFinite(len)) {
            StructuralIntegrity.LOGGER.warn("[SI] kick component skipped: {} has no usable length", direction);
            return new Vector3d();
        }
        double s = magnitude / len;
        return new Vector3d(direction.x * s, direction.y * s, direction.z * s);
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
     * The axis a collapsing piece should turn about, so that its top leads in the
     * direction it is already toppling instead of the whole thing sliding away flat.
     *
     * That axis is {@code up x toppleDirection}. Take a piece toppling toward +X: the
     * cross product gives -Z, and a body spinning about -Z carries a point above its
     * centre of mass toward +X - the top goes over the way the piece is already
     * leaning, which is what falling masonry does.
     *
     * A piece whose mass sits squarely over the failure has no toppling direction to
     * cross with - {@link #toppleDirection} answers straight down there, and up
     * crossed with down is nothing. Rather than leave it unspun, one of the four
     * horizontal directions is chosen by {@link Integrity#scatter}, which is the same
     * stable per-position hash the support tie-break uses: neighbouring collapses
     * fall different ways, but the same collapse always falls the same way.
     */
    public static Vec3 tumbleAxis(BlockPos failed, List<BlockPos> blocks) {
        Vec3 topple = toppleDirection(failed, blocks);
        double dx = topple.x;
        double dz = topple.z;
        if (dx * dx + dz * dz < 1.0e-4) {
            switch (Math.floorMod(Integrity.scatter(failed), 4)) {
                case 0 -> { dx = 1.0; dz = 0.0; }
                case 1 -> { dx = -1.0; dz = 0.0; }
                case 2 -> { dx = 0.0; dz = 1.0; }
                default -> { dx = 0.0; dz = -1.0; }
            }
        }
        // up x (dx, 0, dz), written out rather than built as two vectors and crossed.
        return new Vec3(dz, 0.0, -dx);
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
        Vector3d v = handle.getLinearVelocity(new Vector3d());
        return new Vec3(v.x(), v.y(), v.z());
    }

    /**
     * How fast a sub-level is actually turning, in rad/s about each world axis, asked
     * of the physics engine itself.
     *
     * Global, not body-local: rapier reports angular velocity in world space even
     * though it takes torque in the body's frame. So this can be compared directly
     * against a {@link #tumbleAxis} without converting anything.
     */
    public static Vec3 angularVelocityOf(ServerSubLevel subLevel) {
        RigidBodyHandle handle = RigidBodyHandle.of(subLevel);
        if (handle == null || !handle.isValid()) {
            return Vec3.ZERO;
        }
        Vector3d w = handle.getAngularVelocity(new Vector3d());
        return new Vec3(w.x(), w.y(), w.z());
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
