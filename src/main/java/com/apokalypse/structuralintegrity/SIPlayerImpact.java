package com.apokalypse.structuralintegrity;

import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.entity_collision.SubLevelEntityCollision;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.joml.Vector3dc;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * A player's weight, handed to whatever loose piece they are standing on.
 *
 * Two sub-levels that run into each other are settled by rapier: both are rigid
 * bodies it knows about, so its own contact solver works out the momentum each one
 * takes away and this mod is not involved. A player is not a rigid body. Sable
 * resolves a player against a sub-level in Java instead, by pushing the player back
 * out of the blocks they walked into, and nothing at all is done to the sub-level.
 * The blocks are unmoved and the person may as well be a ghost. That asymmetry is
 * the entire feature: the piece has to be handed the impulse by hand, because there
 * is no solver holding the other end of the collision.
 *
 * None of the physics is written here. Sable already computes everything an impact
 * needs, in the frames it needs them in, in the collision pass it runs every tick:
 * {@code CollisionInfo.preDeltaMovement} is the velocity the player was travelling
 * at before being stopped, {@code verticalCollisionBelow} says they were stopped
 * from underneath rather than walking into a wall, and
 * {@code FirstCollisionInfo.localLocation} is the contact point already converted
 * into the sub-level's own frame. The one number this mod supplies is the player's
 * weight, which vanilla does not have.
 *
 * Handing it over goes through sable's own routine for an entity striking a
 * sub-level - {@link RigidBodyHandle#applyImpulseAtPoint}, called exactly as sable's
 * arrow mixin calls it, a local contact point and the entity's own motion converted
 * through the same pose. Rapier does the rest: the impulse at an offset from the
 * centre of mass becomes both a shove and a torque, so hitting a slab near its edge
 * tips it and hitting it over the middle presses it down, without a line of
 * geometry here deciding that.
 */
public final class SIPlayerImpact {
    private SIPlayerImpact() {}

    /**
     * Players who were resting on a sub-level as of last tick.
     *
     * Landing is a transition, not a state. A player standing still on a piece is
     * reported as collided-from-below every tick for as long as they stand there, and
     * gravity keeps handing them a small downward velocity to be stopped; charging for
     * that every tick would drive anything a player stood on into the ground. Only the
     * tick they arrive counts. Weak, so a player who logs out falls out on their own.
     */
    private static final Set<Player> RESTING =
            Collections.newSetFromMap(new WeakHashMap<>());

    /**
     * Landing. The player was falling and has just been stopped by a sub-level from
     * underneath, so the momentum that stopped went into the blocks.
     */
    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        Player player = event.getEntity();
        if (player.level().isClientSide || !SIConfig.playerImpactEnabled()) {
            return;
        }
        if (!(player instanceof EntityMovementExtension movement)) {
            return;
        }

        SubLevelEntityCollision.CollisionInfo info = movement.sable$getCollisionInfo();
        SubLevel landedOn = info != null && info.verticalCollisionBelow ? info.trackingSubLevel : null;
        if (!(landedOn instanceof ServerSubLevel subLevel)) {
            RESTING.remove(player);
            return;
        }
        if (!RESTING.add(player)) {
            // Already stood here last tick. Standing is not landing.
            return;
        }

        Vec3 arrival = info.preDeltaMovement;
        if (arrival == null) {
            return;
        }
        double speed = arrival.length();
        if (!(speed >= SIConfig.playerImpactMinSpeed())) {
            // Stepping down a stair rather than dropping onto the thing.
            return;
        }

        // The contact point sable itself recorded for this sub-level on this collision,
        // already in the sub-level's frame. Only the first contact of the tick is kept,
        // which is the one that stopped the player.
        SubLevelEntityCollision.FirstCollisionInfo first =
                info.firstCollisions == null ? null : info.firstCollisions.get(landedOn);
        Vec3 localPoint = first != null
                ? toVec3(first.localLocation())
                : subLevel.logicalPose().transformPositionInverse(player.position());

        strike(subLevel, localPoint, arrival, "LAND", player, speed);
    }

    /**
     * Jumping. A player leaving the ground pushes down on whatever they left, and
     * jumping off something loose ought to kick it out from under them.
     *
     * Fired from vanilla's own jump, after the upward velocity has been set, so the
     * reaction is that velocity turned around. Only the vertical part: the horizontal
     * component of a running jump was already there and is carried, not pushed off
     * against.
     */
    @SubscribeEvent
    public static void onJump(LivingEvent.LivingJumpEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (player.level().isClientSide || !SIConfig.playerImpactEnabled()) {
            return;
        }
        if (!(player instanceof EntityMovementExtension movement)) {
            return;
        }
        // The player is airborne from this tick, so the next time they are stopped
        // from underneath is a genuine landing again.
        RESTING.remove(player);

        if (!(movement.sable$getTrackingSubLevel() instanceof ServerSubLevel subLevel)) {
            return;
        }
        double up = player.getDeltaMovement().y;
        if (!(up > 0.0)) {
            return;
        }

        // No collision was recorded this tick - the player is leaving, not arriving -
        // so the contact point is their feet, which is where an entity's position sits.
        Vec3 localPoint = subLevel.logicalPose().transformPositionInverse(player.position());
        strike(subLevel, localPoint, new Vec3(0.0, -up, 0.0), "JUMP", player, up);
    }

    /**
     * Hand the sub-level the player's momentum at a point on it.
     *
     * The impulse is the player's own motion times their weight, and the motion is
     * converted into the body's frame the same way sable's arrow mixin converts an
     * arrow's - rapier rotates an impulse by the body's orientation before applying
     * it, so a world vector handed over raw is only correct on a body that happens to
     * be sitting square.
     */
    static void strike(ServerSubLevel subLevel, Vec3 localPoint, Vec3 worldMotion,
                       String what, @Nullable Player player, double speed) {
        double weight = SIConfig.playerImpactMass();
        if (!(weight > 0.0)) {
            return;
        }
        RigidBodyHandle handle = RigidBodyHandle.of(subLevel);
        if (handle == null || !handle.isValid()) {
            StructuralIntegrity.LOGGER.warn("[SI] player {} skipped: no valid rigid body handle for sub-level {}",
                    what, System.identityHashCode(subLevel));
            return;
        }

        Vec3 localImpulse = impulseOf(subLevel, worldMotion);

        Vec3 vBefore = SIForce.linearVelocityOf(subLevel);
        Vec3 wBefore = SIForce.angularVelocityOf(subLevel);
        handle.applyImpulseAtPoint(localPoint, localImpulse);
        Vec3 vAfter = SIForce.linearVelocityOf(subLevel);
        Vec3 wAfter = SIForce.angularVelocityOf(subLevel);

        StructuralIntegrity.LOGGER.info("[SI] PLAYER {} {} onto sub-level={} mass={} speed={} "
                        + "at local=({},{},{}) impulse=({},{},{}) -> v=({},{},{})->({},{},{}) "
                        + "w=({},{},{})->({},{},{})",
                what, player == null ? "<gametest>" : player.getGameProfile().getName(),
                System.identityHashCode(subLevel),
                fmt(subLevel.getMassTracker().getMass()), fmt(speed),
                fmt(localPoint.x), fmt(localPoint.y), fmt(localPoint.z),
                fmt(localImpulse.x), fmt(localImpulse.y), fmt(localImpulse.z),
                fmt(vBefore.x), fmt(vBefore.y), fmt(vBefore.z),
                fmt(vAfter.x), fmt(vAfter.y), fmt(vAfter.z),
                fmt(wBefore.x), fmt(wBefore.y), fmt(wBefore.z),
                fmt(wAfter.x), fmt(wAfter.y), fmt(wAfter.z));
    }

    /**
     * The impulse a player's motion delivers to a body, in the body's own frame.
     *
     * Pure, and separated out so the one property worth checking can be checked by
     * a test rather than by reading: the impulse is the player's ACTUAL world
     * motion - {@code CollisionInfo.preDeltaMovement} for a landing, the launch
     * velocity for a jump - times {@code playerImpactMass}, which is the player's
     * weight and is correctly the same for every impact. So a player who fell ten
     * blocks hits ten blocks' worth harder than one who stepped off a kerb, and
     * nothing about the impact is a flat constant.
     *
     * Rotated into the body's frame for the same reason sable's own arrow mixin
     * does it: rapier turns an impulse by the body's orientation before applying
     * it, so a world vector handed over raw is only correct on a body that happens
     * to be sitting square with the world.
     */
    static Vec3 impulseOf(ServerSubLevel subLevel, Vec3 worldMotion) {
        return subLevel.logicalPose()
                .transformNormalInverse(worldMotion.scale(SIConfig.playerImpactMass()));
    }

    private static Vec3 toVec3(Vector3dc v) {
        return new Vec3(v.x(), v.y(), v.z());
    }

    private static String fmt(double d) {
        return String.format(java.util.Locale.ROOT, "%.3f", d);
    }
}
