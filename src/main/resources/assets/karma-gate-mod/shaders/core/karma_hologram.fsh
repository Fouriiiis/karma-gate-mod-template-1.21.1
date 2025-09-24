#version 150

uniform sampler2D Sampler0;  // sprite sheet
uniform sampler2D Sampler1;  // noise

uniform float GameTime;      // engine-provided time (ticks + partials)
uniform float uTime;         // optional override from renderer
uniform float uThresh;       // static cutout threshold
uniform float uWobbleAmp;    // wobble multiplier
uniform float uStaticSpeed;  // vertical noise scroll speed
uniform vec4  uSpriteRect;   // sprite frame rect in sheet UVs: (u0, v0, u1, v1)

in vec2 vUv;
in vec4 vColor;
in vec2 vScreenUV;

out vec4 fragColor;

void main() {
    // Prefer engine GameTime if available (non-zero), else fall back to uTime
    float t = (GameTime != 0.0) ? GameTime : uTime;

    // Vertex alpha encodes “cleanliness” (1 clean → less static)
    float aRW = clamp(vColor.a, 0.0, 1.0);
    float staticAmt = 1.0 - aRW;

    // Normalize UVs into 0..1 space for the current frame rect
    vec2 frameUV;
    frameUV.x = (vUv.x - uSpriteRect.x) / max(uSpriteRect.z - uSpriteRect.x, 1e-6);
    frameUV.y = (vUv.y - uSpriteRect.y) / max(uSpriteRect.w - uSpriteRect.y, 1e-6);

    // --- Static cutout mask (scrolls UP over time) ---
    // Flip direction to move up and increase rate for clearer motion
    float n1 = texture(Sampler1, vec2(frameUV.x * 4.0,
                                      frameUV.y * 8.0 + t * uStaticSpeed)).r; // configurable vertical speed
    float h  = n1 * 2.0 - (aRW * aRW);

    float thr = mix(uThresh, -1.0, staticAmt);
    float vis = step(h, thr);   // 1 visible, 0 hole

    // --- Horizontal wobble (time varying) ---
    float n2 = texture(Sampler1, vec2(frameUV.x * 0.5 + t * 0.016,
                                      frameUV.y * 0.4)).r; // doubled again (0.008 -> 0.016)
    float h2 = sin(n2 * 16.0 + t * 1161.12); // doubled again (580.56 -> 1161.12)
    h2 *= pow(abs(h2), mix(0.5, 40.0, aRW));

    float amp = mix(0.008 + 0.008 * h, 0.0, pow(aRW, 0.25)) * uWobbleAmp;

    vec4 base = texture(Sampler0, vec2(vUv.x + h2 * amp, vUv.y));
    float alpha = base.a * vis;
    if (alpha <= 0.001) discard;

    fragColor = vec4(base.rgb * vColor.rgb, alpha);
}
