#version 150

uniform sampler2D Sampler0;  // atlas (your sheet)
uniform sampler2D Noise;     // noise.png

uniform float uTime;         // world time
uniform float uStatic;       // 0..1 : amount of “snow” (1 = fully gone)
uniform float uThresh;       // center of the cutoff curve
uniform float uNoiseScale;   // tiling
uniform float uWobbleAmp;    // horizontal wobble in UV

in vec4 vColor;
in vec2 vUv;

out vec4 fragColor;

void main() {
    // Sheet sample (with tiny horizontal wobble like RW)
    float wobble = sin(uTime * 0.35 + vUv.y * 24.0) * uWobbleAmp * (0.5 + 0.5 * uStatic);
    vec4 base = texture(Sampler0, vec2(vUv.x + wobble, vUv.y));

    // Noise field in screen-space-ish (use vUv scale for simplicity)
    vec2 nUv = vUv * uNoiseScale + vec2(0.02 * uTime, -0.013 * uTime);
    float n  = texture(Noise, nUv).r;

    // Turn static knob (0..1) into a cutoff threshold.
    float cutoff = mix(-0.2, 1.2, uStatic);
    float hole   = step(n, cutoff);  // 1 if noise < cutoff

    float alpha = base.a * vColor.a * (1.0 - hole);

    // Optional faint scanlines modulating alpha (subtle)
    float scan = 0.95 + 0.05 * sin(vUv.y * 150.0 + uTime * 3.0);
    alpha *= scan;

    // Color tint from vertex color, preserve sheet RGB (or tint it a bit)
    vec3 rgb = mix(base.rgb, vColor.rgb, 0.15);

    if (alpha <= 0.01) discard;
    fragColor = vec4(rgb, alpha);
}
