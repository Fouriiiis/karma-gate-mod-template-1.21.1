package dev.fouriis.karmagate.client.swarmer;

import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;

import java.util.List;

/**
 * Represents a single neuron swarmer entity with flocking behavior.
 * Based on Rain World's SSOracleSwarmer implementation.
 */
public class NeuronSwarmer {
    // Position and physics
    public Vec3d position;
    public Vec3d lastPosition;
    public Vec3d velocity;
    public Vec3d direction;
    public Vec3d lastDirection;
    public Vec3d lazyDirection;
    public Vec3d lastLazyDirection;
    
    // Rotation for visual effect
    public float rotation;
    public float lastRotation;
    public float revolveSpeed;
    
    // Behavior parameters
    private float torque;
    public float idealDistance;
    public float aimInFront;
    public float randomVibrations;
    public float behaviorLife;
    public float behaviorDeathSpeed;
    public boolean suckle;
    
    // Color (hue and saturation encoded)
    public float colorX;
    public float colorY;
    public float targetColorX;
    public float targetColorY;
    
    // Dominance for behavior propagation
    private float dominance;
    
    // Zone reference
    private final String zoneName;
    
    // Age tracking
    public int age = 0;
    public boolean markedForRemoval = false;
    
    private static final Random RANDOM = Random.create();
    
    public NeuronSwarmer(String zoneName, Vec3d spawnPosition) {
        this.zoneName = zoneName;
        this.position = spawnPosition;
        this.lastPosition = spawnPosition;
        
        // Random initial direction
        this.direction = randomNormalizedVector();
        this.lastDirection = direction;
        this.lazyDirection = direction;
        this.lastLazyDirection = direction;
        
        // Random initial velocity
        this.velocity = direction.multiply(0.1);
        
        // Initialize behavior
        initNewBehavior();
        
        // Initial rotation
        this.rotation = 0.25f;
        this.lastRotation = rotation;
    }
    
    /**
     * Initialize a new random behavior pattern.
     */
    public void initNewBehavior() {
        this.dominance = RANDOM.nextFloat();
        this.idealDistance = lerp(10f, 300f, RANDOM.nextFloat() * RANDOM.nextFloat());
        this.behaviorLife = 1f;
        this.behaviorDeathSpeed = 1f / lerp(40f, 220f, RANDOM.nextFloat());
        
        // Color: x = hue variant (0, 0.5, 1), y = saturation
        this.targetColorX = (float) RANDOM.nextInt(3) / 2f;
        this.targetColorY = RANDOM.nextFloat() < 0.75f ? 0f : 1f;
        if (colorX == 0 && colorY == 0) {
            colorX = targetColorX;
            colorY = targetColorY;
        }
        
        this.aimInFront = lerp(40f, 300f, RANDOM.nextFloat());
        this.torque = RANDOM.nextFloat() < 0.5f ? 0f : lerp(-1f, 1f, RANDOM.nextFloat());
        this.randomVibrations = RANDOM.nextFloat() * RANDOM.nextFloat() * RANDOM.nextFloat();
        this.revolveSpeed = (RANDOM.nextFloat() < 0.5f ? -1f : 1f) / lerp(15f, 65f, RANDOM.nextFloat());
        this.suckle = RANDOM.nextFloat() < 1f / 6f;
    }
    
    /**
     * Returns true if this behavior has expired.
     */
    public boolean isBehaviorDead() {
        return behaviorLife <= 0f;
    }
    
    /**
     * Returns the effective dominance of this swarmer's behavior.
     */
    public float getDominance() {
        if (isBehaviorDead()) return -1f;
        return dominance * (float) Math.pow(behaviorLife, 0.25);
    }
    
    /**
     * Main update tick for this swarmer.
     */
    public void tick(List<NeuronSwarmer> otherSwarmers, Vec3d zoneMin, Vec3d zoneMax, ClientWorld world) {
        age++;
        
        // Store last values
        lastPosition = position;
        lastDirection = direction;
        lastLazyDirection = lazyDirection;
        lastRotation = rotation;
        
        // Update rotation
        rotation += revolveSpeed;
        
        // Update lazy direction (smooth interpolation)
        lazyDirection = slerp(lazyDirection, direction, 0.06);
        
        // Run swarm behavior
        swarmBehavior(otherSwarmers);
        
        // Check block collisions and adjust velocity before applying
        if (world != null) {
            handleBlockCollisions(world);
        }
        
        // Apply velocity with stronger effect
        position = position.add(velocity);
        
        // Dampen velocity slightly to prevent runaway speeds
        double speed = velocity.length();
        double maxSpeed = 0.5;
        if (speed > maxSpeed) {
            velocity = velocity.normalize().multiply(maxSpeed);
        }
        velocity = velocity.multiply(0.95); // Gentle drag
        
        // Update direction based on velocity
        if (velocity.lengthSquared() > 0.0001) {
            direction = velocity.normalize();
        }
        
        // Boundary avoidance - stay within zone
        Vec3d center = zoneMin.add(zoneMax).multiply(0.5);
        double margin = 10.0;
        
        // Push back from boundaries
        Vec3d boundaryPush = Vec3d.ZERO;
        if (position.x < zoneMin.x + margin) {
            boundaryPush = boundaryPush.add(1, 0, 0);
        } else if (position.x > zoneMax.x - margin) {
            boundaryPush = boundaryPush.add(-1, 0, 0);
        }
        if (position.y < zoneMin.y + margin) {
            boundaryPush = boundaryPush.add(0, 1, 0);
        } else if (position.y > zoneMax.y - margin) {
            boundaryPush = boundaryPush.add(0, -1, 0);
        }
        if (position.z < zoneMin.z + margin) {
            boundaryPush = boundaryPush.add(0, 0, 1);
        } else if (position.z > zoneMax.z - margin) {
            boundaryPush = boundaryPush.add(0, 0, -1);
        }
        
        if (boundaryPush.lengthSquared() > 0) {
            velocity = velocity.add(boundaryPush.normalize().multiply(0.15));
        }
        
        // Clamp to zone bounds
        position = new Vec3d(
            clamp(position.x, zoneMin.x + 1, zoneMax.x - 1),
            clamp(position.y, zoneMin.y + 1, zoneMax.y - 1),
            clamp(position.z, zoneMin.z + 1, zoneMax.z - 1)
        );
        
        // Final collision check - push out of any solid blocks
        if (world != null) {
            BlockPos currentBlock = BlockPos.ofFloored(position);
            if (isBlockSolid(world, currentBlock)) {
                // We're inside a solid block, revert to last position
                position = lastPosition;
                velocity = velocity.multiply(-0.5); // Bounce back
            }
        }
        
        // Decay behavior life
        if (!isBehaviorDead()) {
            behaviorLife -= behaviorDeathSpeed;
        }
        
        // If behavior died, start a new one
        if (isBehaviorDead()) {
            float oldColorX = targetColorX;
            initNewBehavior();
            // 75% chance to keep previous color
            if (RANDOM.nextFloat() < 0.75f) {
                targetColorX = oldColorX;
            }
        }
        
        // Smoothly interpolate color
        colorX = lerp(colorX, targetColorX, 0.05f);
        colorY = lerp(colorY, targetColorY, 0.05f);
    }
    
    /**
     * Flocking/swarm behavior implementation.
     * Uses classic boids algorithm: cohesion, alignment, separation + orbital torque.
     */
    private void swarmBehavior(List<NeuronSwarmer> otherSwarmers) {
        // Flocking parameters
        final double INTERACTION_RANGE = 8.0;  // How far swarmers can see each other
        final double SEPARATION_RANGE = 2.0;   // Personal space distance
        final double COHESION_STRENGTH = 0.02;  // Pull toward center of flock
        final double ALIGNMENT_STRENGTH = 0.08; // Match velocity with neighbors
        final double SEPARATION_STRENGTH = 0.15; // Push away from too-close neighbors
        final double TORQUE_STRENGTH = 0.05;    // Orbital movement strength
        
        Vec3d cohesionCenter = Vec3d.ZERO;
        Vec3d alignmentVelocity = Vec3d.ZERO;
        Vec3d separationForce = Vec3d.ZERO;
        int neighborCount = 0;
        
        float avgTorque = torque;
        float avgRevolveSpeed = revolveSpeed;
        float avgColorX = 0f;
        float avgColorY = 0f;
        float colorWeight = 0f;
        
        // Iterate through other swarmers
        for (NeuronSwarmer other : otherSwarmers) {
            if (other == this || other.markedForRemoval) continue;
            
            double dist = position.distanceTo(other.position);
            if (dist < INTERACTION_RANGE && dist > 0.001) {
                float weight = (float) (1.0 - dist / INTERACTION_RANGE);
                neighborCount++;
                
                // Cohesion: accumulate positions for center of mass
                cohesionCenter = cohesionCenter.add(other.position);
                
                // Alignment: accumulate velocities
                alignmentVelocity = alignmentVelocity.add(other.velocity);
                
                // Separation: push away from close neighbors
                if (dist < SEPARATION_RANGE) {
                    Vec3d away = position.subtract(other.position).normalize();
                    double separationWeight = 1.0 - (dist / SEPARATION_RANGE);
                    separationForce = separationForce.add(away.multiply(separationWeight));
                }
                
                // Behavioral influence
                avgTorque += other.torque * weight;
                avgRevolveSpeed += other.revolveSpeed * weight;
                
                // Color influence
                avgColorX += other.colorX * weight;
                avgColorY += other.colorY * weight;
                colorWeight += weight;
                
                // Behavior propagation - adopt more dominant behavior
                float otherDom = other.getDominance();
                float myDom = getDominance();
                if (myDom < otherDom * Math.pow(weight, 2)) {
                    this.targetColorX = other.targetColorX;
                    this.targetColorY = other.targetColorY;
                    this.torque = other.torque;
                    this.revolveSpeed = other.revolveSpeed;
                    this.idealDistance = other.idealDistance;
                    this.randomVibrations = other.randomVibrations;
                    this.behaviorLife = other.behaviorLife * 0.95f;
                    this.behaviorDeathSpeed = other.behaviorDeathSpeed;
                }
            }
        }
        
        // Apply flocking forces
        if (neighborCount > 0) {
            // Cohesion: steer toward center of mass
            cohesionCenter = cohesionCenter.multiply(1.0 / neighborCount);
            Vec3d toCenter = cohesionCenter.subtract(position);
            if (toCenter.lengthSquared() > 0.0001) {
                velocity = velocity.add(toCenter.normalize().multiply(COHESION_STRENGTH));
            }
            
            // Alignment: match average velocity of neighbors
            alignmentVelocity = alignmentVelocity.multiply(1.0 / neighborCount);
            Vec3d alignDiff = alignmentVelocity.subtract(velocity);
            velocity = velocity.add(alignDiff.multiply(ALIGNMENT_STRENGTH));
            
            // Separation: avoid crowding
            if (separationForce.lengthSquared() > 0.0001) {
                velocity = velocity.add(separationForce.normalize().multiply(SEPARATION_STRENGTH));
            }
            
            // Orbital torque around center of mass (creates swirling patterns)
            if (toCenter.lengthSquared() > 0.0001) {
                Vec3d perpendicular = toCenter.crossProduct(new Vec3d(0, 1, 0));
                if (perpendicular.lengthSquared() < 0.0001) {
                    perpendicular = toCenter.crossProduct(new Vec3d(1, 0, 0));
                }
                perpendicular = perpendicular.normalize();
                velocity = velocity.add(perpendicular.multiply(torque * TORQUE_STRENGTH));
            }
            
            // Smooth torque and revolve speed
            torque = lerp(torque, avgTorque / (1 + neighborCount), 0.1f);
            revolveSpeed = lerp(revolveSpeed, avgRevolveSpeed / (1 + neighborCount), 0.2f);
            
            // Color averaging
            if (colorWeight > 0) {
                colorX = lerp(colorX, avgColorX / colorWeight, 0.2f);
                colorY = lerp(colorY, avgColorY / colorWeight, 0.2f);
            }
        }
        
        // Add random vibrations for organic movement
        Vec3d randomDir = randomNormalizedVector();
        velocity = velocity.add(randomDir.multiply(0.05 * randomVibrations));
        
        // Blend toward behavior's target color
        colorX = lerp(colorX, targetColorX, 0.05f);
        colorY = lerp(colorY, targetColorY, 0.05f);
    }
    
    /**
     * Get the zone this swarmer belongs to.
     */
    public String getZoneName() {
        return zoneName;
    }
    
    // ========== Utility methods ==========
    
    private static Vec3d randomNormalizedVector() {
        double theta = RANDOM.nextDouble() * Math.PI * 2;
        double phi = Math.acos(2 * RANDOM.nextDouble() - 1);
        return new Vec3d(
            Math.sin(phi) * Math.cos(theta),
            Math.sin(phi) * Math.sin(theta),
            Math.cos(phi)
        );
    }
    
    private static Vec3d slerp(Vec3d a, Vec3d b, double t) {
        // Simplified slerp - just lerp and normalize for our purposes
        Vec3d result = new Vec3d(
            lerp(a.x, b.x, t),
            lerp(a.y, b.y, t),
            lerp(a.z, b.z, t)
        );
        double len = result.length();
        if (len > 0.0001) {
            return result.multiply(1.0 / len);
        }
        return a;
    }
    
    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
    
    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }
    
    private static double clamp(double val, double min, double max) {
        return Math.max(min, Math.min(max, val));
    }
    
    /**
     * Handles block collisions by checking surrounding blocks and adjusting velocity.
     */
    private void handleBlockCollisions(ClientWorld world) {
        BlockPos currentBlock = BlockPos.ofFloored(position);
        double collisionMargin = 0.3; // How close to get before being repelled
        
        // Check all 6 directions for nearby solid blocks
        Vec3d pushForce = Vec3d.ZERO;
        
        // Check each axis
        for (int axis = 0; axis < 3; axis++) {
            for (int dir = -1; dir <= 1; dir += 2) {
                BlockPos checkPos = switch (axis) {
                    case 0 -> currentBlock.add(dir, 0, 0);
                    case 1 -> currentBlock.add(0, dir, 0);
                    case 2 -> currentBlock.add(0, 0, dir);
                    default -> currentBlock;
                };
                
                if (isBlockSolid(world, checkPos)) {
                    // Calculate distance to block face
                    double blockEdge = switch (axis) {
                        case 0 -> dir > 0 ? checkPos.getX() : checkPos.getX() + 1;
                        case 1 -> dir > 0 ? checkPos.getY() : checkPos.getY() + 1;
                        case 2 -> dir > 0 ? checkPos.getZ() : checkPos.getZ() + 1;
                        default -> 0;
                    };
                    
                    double posComponent = switch (axis) {
                        case 0 -> position.x;
                        case 1 -> position.y;
                        case 2 -> position.z;
                        default -> 0;
                    };
                    
                    double distToBlock = Math.abs(posComponent - blockEdge);
                    
                    if (distToBlock < collisionMargin) {
                        // Push away from the block
                        double pushStrength = (collisionMargin - distToBlock) / collisionMargin * 0.2;
                        Vec3d push = switch (axis) {
                            case 0 -> new Vec3d(-dir * pushStrength, 0, 0);
                            case 1 -> new Vec3d(0, -dir * pushStrength, 0);
                            case 2 -> new Vec3d(0, 0, -dir * pushStrength);
                            default -> Vec3d.ZERO;
                        };
                        pushForce = pushForce.add(push);
                        
                        // Also dampen velocity in this direction
                        double velComponent = switch (axis) {
                            case 0 -> velocity.x;
                            case 1 -> velocity.y;
                            case 2 -> velocity.z;
                            default -> 0;
                        };
                        
                        // If moving toward the block, reduce/reverse that velocity
                        if ((dir > 0 && velComponent > 0) || (dir < 0 && velComponent < 0)) {
                            velocity = switch (axis) {
                                case 0 -> new Vec3d(velComponent * -0.3, velocity.y, velocity.z);
                                case 1 -> new Vec3d(velocity.x, velComponent * -0.3, velocity.z);
                                case 2 -> new Vec3d(velocity.x, velocity.y, velComponent * -0.3);
                                default -> velocity;
                            };
                        }
                    }
                }
            }
        }
        
        velocity = velocity.add(pushForce);
        
        // Also check the current block (in case we're inside one)
        if (isBlockSolid(world, currentBlock)) {
            // Find the nearest non-solid block and push toward it
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        BlockPos neighbor = currentBlock.add(dx, dy, dz);
                        if (!isBlockSolid(world, neighbor)) {
                            // Push toward this open space
                            Vec3d toOpen = new Vec3d(
                                neighbor.getX() + 0.5 - position.x,
                                neighbor.getY() + 0.5 - position.y,
                                neighbor.getZ() + 0.5 - position.z
                            ).normalize();
                            velocity = velocity.add(toOpen.multiply(0.3));
                            return;
                        }
                    }
                }
            }
        }
    }
    
    /**
     * Checks if a block is solid (should be collided with).
     */
    private boolean isBlockSolid(ClientWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        // Check if the block has any collision shape
        return !state.getCollisionShape(world, pos).isEmpty();
    }
}
