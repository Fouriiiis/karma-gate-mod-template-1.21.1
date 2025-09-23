#version 150

// Unity GateHologram approximation
// - RGB from vertex color
// - Alpha from sheet texture
// - Discard when noise threshold exceeded (screen-static cutouts)
// - Horizontal UV wobble driven by secondary noise

uniform sampler2D Sampler0;  // main sheet (unit 0)
uniform sampler2D Sampler1;  // noise (unit 1)

uniform float uTime;         // world ticks
uniform float uStatic;       // 0..1 user-controlled "static"
uniform float uThresh;       // discard threshold (~0.6)
uniform float uNoiseScale;   // unused here, kept for tweakability
uniform float uWobbleAmp;    // extra wobble scaling (multiplier)
uniform vec2  uScreenSize;   // viewport size

in vec4 vColor;
in vec2 vUv;

out vec4 fragColor;

void main() {
    // Map ticks to a rough "seconds" feel so animation rates stay sane.
    float t = uTime * 0.05;

    // In the Unity shader, many effects were driven by vertex alpha (i.clr.w).
    // Here we map that behavior so higher uStatic => more glitches/wobble.
    float aRW = 1.0 - clamp(uStatic, 0.0, 1.0); // proxy for i.clr.w in RW

    // Build a coarse screen-space coordinate akin to Unity's ComputeScreenPos
    // Note: in core profile we can use gl_FragCoord.xy as pixel position.
    vec2 scr = floor(gl_FragCoord.xy);
    vec2 textCoord = scr / max(uScreenSize, vec2(1.0));

    // First noise: drives discard threshold (like "holes" in the holo)
    // Unity sampled Noise2 at (x*4, y*8 - RAIN*10); we reuse t as time scroll
    float n1 = texture(Sampler1, vec2(textCoord.x * 4.0, textCoord.y * 8.0 - t * 10.0)).r;
    float h  = n1 * 2.0 - (aRW * aRW);
    // Visibility mask: when noise exceeds threshold, alpha goes to 0.
    // Also push threshold down with uStatic so at max static it's fully invisible.
    float thr = mix(uThresh, -1.0, clamp(uStatic, 0.0, 1.0));
    float vis = step(h, thr); // 1 when h <= thr, 0 when h > thr

    // Second noise: controls horizontal wobble with a shaped sine
    float n2 = texture(Sampler1, vec2(textCoord.x * 0.5 + t * 0.002, textCoord.y * 0.4)).r;
    float h2 = sin(n2 * 16.0 + t * 145.14);
    h2 *= pow(abs(h2), mix(0.5, 40.0, aRW));

    // Wobble amplitude scales with (0.008 + 0.008*h), then reduced by pow(a,0.25)
    float amp = mix(0.008 + 0.008 * h, 0.0, pow(aRW, 0.25));
    amp *= uWobbleAmp; // optional fine-tune

    vec2 uv = vec2(vUv.x + h2 * amp, vUv.y);
    vec4 base = texture(Sampler0, uv);

    // Final color: tint sheet by vertex color; alpha masked by noise visibility
    vec3 rgb = base.rgb * vColor.rgb;
    float alpha = base.a * vis;

    if (alpha <= 0.001) discard;
    fragColor = vec4(rgb, alpha);
}
