#version 150

uniform sampler2D Sampler0; // block atlas

uniform float GameTime;

uniform float uSeed;
uniform float uScrollSpeed;   // blocks/tick
uniform vec2  uNoiseScale;    // (xScale, yScale)
uniform float uSoftness;
uniform float uTilesPerBlock;

in vec2 vLocalUV; // x: 0..1 across width, y: distance down in blocks
in float vFlow;
in vec4 vColor;

out vec4 fragColor;

float hash11(float p) {
    p = fract(p * 0.1031);
    p *= p + 33.33;
    p *= p + p;
    return fract(p);
}

float hash21(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float valueNoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);

    float a = hash21(i + vec2(0.0, 0.0));
    float b = hash21(i + vec2(1.0, 0.0));
    float c = hash21(i + vec2(0.0, 1.0));
    float d = hash21(i + vec2(1.0, 1.0));

    // smooth interpolation
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

float easedFall(float vBlocks) {
    // Hypnotic acceleration curve: starts slower, speeds up gradually with distance.
    // Uses an exponential ease so it doesn't require knowing the full waterfall height.
    return 0.40 + 0.60 * (1.0 - exp(-vBlocks * 0.35));
}

float streamMaskAt(float x, float vBlocks, float yScroll, float seed, float softness) {
    // Multiple thin ribbons across the sheet.
    // We take the max of several soft masks so streams remain distinct.
    const int STREAMS = 7;
    float m = 0.0;

    for (int i = 0; i < STREAMS; i++) {
        float fi = float(i);
        float r0 = hash11(seed * 17.0 + fi * 13.0);
        float r1 = hash11(seed * 31.0 + fi * 7.0);
        float r2 = hash11(seed * 47.0 + fi * 19.0);
        float r3 = hash11(seed * 59.0 + fi * 23.0);

        float baseCenter = (fi + 0.5) / float(STREAMS);
        float jitter = (r0 - 0.5) * (0.55 / float(STREAMS));
        float center = baseCenter + jitter;

        // Narrow ribbon width with soft feather.
        float width = mix(0.030, 0.070, r1);
        float feather = max(width * 0.70, softness * 2.0);

        // Irregular start height (in blocks) so they originate from uneven ledges.
        float start = mix(0.0, 0.35, r2);
        float startFade = smoothstep(start, start + 0.10, vBlocks);

        // Small high-frequency wobble to keep streams alive but coherent.
        float wob = (valueNoise(vec2(yScroll * 1.25 + seed * (11.0 + fi), fi * 3.1)) - 0.5) * 0.030;
        float c = center + wob;

        float d = abs(x - c);
        float ribbon = smoothstep(width, width - feather, d);

        // Micro-splitting: occasionally split into 2–3 filaments then recombine.
        float splitN = valueNoise(vec2(yScroll * 0.65 + seed * (29.0 + fi), fi * 9.7));
        float split = smoothstep(0.78, 0.92, splitN);
        float splitOffset = width * mix(0.45, 0.85, r3);
        float rA = smoothstep(width * 0.75, (width * 0.75) - feather, abs(x - (c - splitOffset)));
        float rB = smoothstep(width * 0.75, (width * 0.75) - feather, abs(x - (c + splitOffset)));
        float splitRibbon = max(rA, rB);

        float finalRibbon = mix(ribbon, splitRibbon, split);
        m = max(m, finalRibbon * startFade);
    }

    return m;
}

void main() {
    float t = GameTime;

    float flow = clamp(vFlow, 0.0, 1.0);

    // Local tiling along height: vLocalUV.y is distance down in blocks.
    float vBlocks = max(vLocalUV.y, 0.0);
    float vTile = fract(vBlocks * uTilesPerBlock);
    // seam fix: treat exact 0.0 as 1.0 when not at top
    if (vTile == 0.0 && vBlocks > 0.0) vTile = 1.0;

    // Sample the water texture directly (not from the stitched block atlas).
    // This avoids reliance on atlas rect uniforms, which can be brittle under shaderpack pipelines.
    vec4 base = texture(Sampler0, vec2(clamp(vLocalUV.x, 0.0, 1.0), vTile));

    float seed = uSeed;
    float s = max(uSoftness, 1e-4);

    float x = clamp(vLocalUV.x, 0.0, 1.0);

    // Accelerating fall: animation scroll increases with distance down.
    float yMotion = t * uScrollSpeed * easedFall(vBlocks);
    float yScroll = vBlocks + yMotion;

    // Build the stream ribbons.
    float streams = streamMaskAt(x, vBlocks, yScroll, seed, s);

    // Subtle striations / vertical threads inside the water.
    float threadN = valueNoise(vec2(x * 22.0 + seed * 101.0, yScroll * 0.35 + seed * 7.0));
    float threads = 0.5 + 0.5 * sin((x * 180.0 + threadN * 6.0 + seed * 37.0) + yScroll * 6.0);
    float glint = pow(threads, 10.0) * 0.12; // thin vertical glints

    // Vertically stretched void cuts (inside streams only).
    float stripeFreq = max(uNoiseScale.x, 1e-3);
    float yScale = max(uNoiseScale.y, 1e-3);
    float warp = valueNoise(vec2(yScroll * 0.45 + seed * 13.0, x * 0.25 + seed * 19.0));
    float phase = (x * stripeFreq * 1.85 + (warp - 0.5) * 0.75) * 6.2831853;
    float stripe = 0.5 + 0.5 * sin(phase);
    float ridge = 1.0 - abs(stripe * 2.0 - 1.0);
    ridge = pow(ridge, 10.0);

    float cutBreak = valueNoise(vec2(x * stripeFreq * 0.35 + seed * 41.0,
                                    yScroll * yScale * 0.55 + seed * 59.0));
    float cut = ridge * smoothstep(0.45 - s, 0.85 + s, cutBreak);
    float voidStrength = mix(0.25, 1.0, 1.0 - flow);
    float voidMask = smoothstep(0.35 - s, 0.75 + s, cut * voidStrength) * streams;

    // Cool/desaturated water tint (avoid pure white).
    float luma = dot(base.rgb, vec3(0.2126, 0.7152, 0.0722));
    vec3 cool = mix(base.rgb, vec3(luma), 0.35) * vec3(0.85, 0.90, 1.00);

    // Slight depth variation along the fall.
    float depthN = valueNoise(vec2(seed * 5.0, yScroll * 0.20 + seed * 3.0));
    float depthMul = mix(0.85, 1.0, depthN);

    // Final alpha: thin ribbons with soft edges, carved voids, and stable baseline.
    float alpha = base.a * depthMul * streams * (0.25 + 0.75 * flow) * (1.0 - 0.92 * voidMask);
    alpha = clamp(alpha, 0.0, 1.0);

    // Add subtle glints inside streams.
    vec3 rgb = cool + vec3(0.06, 0.07, 0.09) * glint * streams;

    fragColor = vec4(rgb, alpha);
}
