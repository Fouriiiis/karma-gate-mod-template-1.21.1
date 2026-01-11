package dev.fouriis.karmagate.client.gridproject;

import dev.fouriis.karmagate.client.swarmer.NeuronSwarmer;
import net.minecraft.util.math.random.Random;

/**
 * Represents a single projected circle attached to a neuron swarmer.
 * Handles animation state for radius wobble, blink, rotation, and spokes.
 * Based on Rain World's ProjectedCircle implementation.
 */
public class ProjectedCircleInstance {
    // Owning swarmer reference
    private final NeuronSwarmer owner;
    
    // Radius animation
    private float baseRad;
    private float rad;
    private float lastRad;
    private float targetRad;
    
    // Blink/pulse animation
    private float blink;
    private float lastBlink;
    private float blinkTarget;
    private int blinkCounter;
    
    // Rotation animation
    private float rotation;
    private float lastRotation;
    private float rotationSpeed;
    
    // Visual properties
    private int spokes;
    private float alphaScale;
    
    // Current projected position (in perimeter-U coordinate)
    private float centerU;
    private float centerY;
    
    // Age tracking
    private int age = 0;
    public boolean markedForRemoval = false;
    
    private static final Random RANDOM = Random.create();
    
    // Base radius range (in blocks, scaled for Minecraft)
    private static final float MIN_BASE_RAD = 3.0f;
    private static final float MAX_BASE_RAD = 8.0f;
    
    /**
     * Creates a new projected circle for a swarmer.
     * 
     * @param owner The owning neuron swarmer
     * @param sizeHint Size factor 0-1 (random if -1)
     */
    public ProjectedCircleInstance(NeuronSwarmer owner, float sizeHint) {
        this.owner = owner;
        
        // Initialize size
        float size = (sizeHint < 0) ? RANDOM.nextFloat() : sizeHint;
        this.baseRad = lerp(MIN_BASE_RAD, MAX_BASE_RAD, size);
        this.rad = baseRad;
        this.lastRad = rad;
        this.targetRad = rad;
        
        // Calculate spokes based on radius (similar to Rain World)
        // Circumference = 2*PI*rad, divide by ~3 blocks per spoke
        this.spokes = Math.max(4, (int)(Math.PI * 2.0 * baseRad / 3.0));
        
        // Rotation speed: random direction and speed
        this.rotationSpeed = lerp(0.2f, 1.0f, RANDOM.nextFloat()) 
                           * (RANDOM.nextFloat() < 0.5f ? -1f : 1f);
        
        // Initial blink state
        this.blink = 0f;
        this.lastBlink = 0f;
        this.blinkTarget = 0f;
        this.blinkCounter = 0;
        
        // Initial alpha
        this.alphaScale = 1.0f;
        
        // Rotation
        this.rotation = RANDOM.nextFloat() * 360f;
        this.lastRotation = rotation;
    }
    
    /**
     * Updates the circle each tick.
     * Projects the circle onto the wall behind the neuron relative to camera.
     * 
     * @param zone The projection zone this circle belongs to
     * @param cameraX Camera X position
     * @param cameraY Camera Y position  
     * @param cameraZ Camera Z position
     */
    public void tick(ProjectionZone zone, double cameraX, double cameraY, double cameraZ) {
        age++;
        
        // Store last values for interpolation
        lastRad = rad;
        lastBlink = blink;
        lastRotation = rotation;
        
        // Update projected position - project from camera through neuron onto zone walls
        if (owner != null && zone != null) {
            // Ray from camera through neuron
            double neuronX = owner.position.x;
            double neuronY = owner.position.y;
            double neuronZ = owner.position.z;
            
            // Direction from camera to neuron
            double dirX = neuronX - cameraX;
            double dirY = neuronY - cameraY;
            double dirZ = neuronZ - cameraZ;
            
            // Normalize XZ direction (we project onto vertical walls)
            double lenXZ = Math.sqrt(dirX * dirX + dirZ * dirZ);
            if (lenXZ < 0.001) {
                // Camera directly above/below neuron, use neuron position directly
                centerU = ProjectionMath.computeSquarePerimeterU(
                    (float) neuronX,
                    (float) neuronZ,
                    zone.getCenterXf(),
                    zone.getCenterZf(),
                    zone.getRadiusf()
                );
                centerY = (float) neuronY;
            } else {
                // Find intersection with zone boundary walls
                // Zone walls are at minX, maxX, minZ, maxZ
                float minX = zone.getMinX();
                float maxX = zone.getMaxX() + 1;
                float minZ = zone.getMinZ();
                float maxZ = zone.getMaxZ() + 1;
                
                // Compute t for each wall intersection
                double tMinX = (dirX != 0) ? (minX - cameraX) / dirX : Double.MAX_VALUE;
                double tMaxX = (dirX != 0) ? (maxX - cameraX) / dirX : Double.MAX_VALUE;
                double tMinZ = (dirZ != 0) ? (minZ - cameraZ) / dirZ : Double.MAX_VALUE;
                double tMaxZ = (dirZ != 0) ? (maxZ - cameraZ) / dirZ : Double.MAX_VALUE;
                
                // We want intersections beyond the neuron (t > 1 means past the neuron)
                // Find the closest valid intersection that is beyond the neuron
                double tNeuron = 1.0; // t=1 is at the neuron position
                double bestT = Double.MAX_VALUE;
                
                if (tMinX > tNeuron && tMinX < bestT) bestT = tMinX;
                if (tMaxX > tNeuron && tMaxX < bestT) bestT = tMaxX;
                if (tMinZ > tNeuron && tMinZ < bestT) bestT = tMinZ;
                if (tMaxZ > tNeuron && tMaxZ < bestT) bestT = tMaxZ;
                
                if (bestT < Double.MAX_VALUE - 1) {
                    // Compute wall intersection point
                    double wallX = cameraX + dirX * bestT;
                    double wallY = cameraY + dirY * bestT;
                    double wallZ = cameraZ + dirZ * bestT;
                    
                    centerU = ProjectionMath.computeSquarePerimeterU(
                        (float) wallX,
                        (float) wallZ,
                        zone.getCenterXf(),
                        zone.getCenterZf(),
                        zone.getRadiusf()
                    );
                    centerY = (float) wallY;
                } else {
                    // Fallback to neuron position
                    centerU = ProjectionMath.computeSquarePerimeterU(
                        (float) neuronX,
                        (float) neuronZ,
                        zone.getCenterXf(),
                        zone.getCenterZf(),
                        zone.getRadiusf()
                    );
                    centerY = (float) neuronY;
                }
            }
        }
        
        // Radius wobble animation
        if (rad < targetRad) {
            rad = Math.min(rad + 0.08f, targetRad);
        } else if (rad > targetRad) {
            rad = Math.max(rad - 0.08f, targetRad);
        }
        rad = lerp(rad, targetRad, 0.02f);
        
        // Random radius variation
        if (RANDOM.nextFloat() < 1f / 120f) {
            targetRad = lerp(baseRad * 0.8f, baseRad * 1.2f, RANDOM.nextFloat());
        }
        
        // Blink animation
        // Random chance to start a blink
        if (RANDOM.nextFloat() < 0.001f) { // ~0.1% chance per tick = roughly every 1000 ticks
            blinkCounter = RANDOM.nextBetween(7, 230);
            blinkTarget = 1.0f;
        }
        
        if (blinkCounter > 0) {
            blinkCounter--;
        } else {
            blinkTarget = 0f;
        }
        
        // Smooth blink transition
        if (blink < blinkTarget) {
            blink = Math.min(blink + 1f / 30f, blinkTarget);
        } else if (blink > blinkTarget) {
            blink = Math.max(blink - 1f / 30f, blinkTarget);
        }
        blink = lerp(blink, blinkTarget, 0.02f);
        
        // Rotation animation (similar to Rain World's formula)
        // rotation += rotationSpeed * 140 * rotationSpeed / rad
        rotation += rotationSpeed * 140f * rotationSpeed / Math.max(rad, 1f);
        
        // Keep rotation in reasonable range
        if (rotation > 360f) rotation -= 360f;
        if (rotation < -360f) rotation += 360f;
        
        // Check if owner is still valid
        if (owner != null && owner.markedForRemoval) {
            markedForRemoval = true;
        }
    }
    
    /**
     * Gets the effective radius considering blink state.
     * Blink causes the circle to shrink slightly.
     */
    public float getEffectiveRadius(float tickDelta) {
        float r = lerp(lastRad, rad, tickDelta);
        float b = lerp(lastBlink, blink, tickDelta);
        return r * lerp(1.0f, 0.6f, b);
    }
    
    /**
     * Gets interpolated rotation in degrees.
     */
    public float getRotation(float tickDelta) {
        // Handle wraparound for smooth interpolation
        float from = lastRotation;
        float to = rotation;
        float diff = to - from;
        if (diff > 180f) from += 360f;
        else if (diff < -180f) from -= 360f;
        return lerp(from, to, tickDelta);
    }
    
    /**
     * Gets interpolated blink value.
     */
    public float getBlink(float tickDelta) {
        return lerp(lastBlink, blink, tickDelta);
    }
    
    // Getters
    public float getCenterU() { return centerU; }
    public float getCenterY() { return centerY; }
    public float getBaseRad() { return baseRad; }
    public int getSpokes() { return spokes; }
    public float getAlphaScale() { return alphaScale; }
    public NeuronSwarmer getOwner() { return owner; }
    public int getAge() { return age; }
    
    // Setters
    public void setBaseRad(float newBaseRad) {
        float oldBaseRad = this.baseRad;
        this.baseRad = newBaseRad;
        // Adjust target based on change
        if (newBaseRad < oldBaseRad) {
            targetRad = newBaseRad * 0.8f;
        } else if (newBaseRad > oldBaseRad) {
            targetRad = newBaseRad * 1.2f;
        }
        // Recalculate spokes
        this.spokes = Math.max(4, (int)(Math.PI * 2.0 * baseRad / 3.0));
    }
    
    public void setAlphaScale(float scale) {
        this.alphaScale = scale;
    }
    
    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
