#version 150
// ============================================================
// FLAT (screen-space) + seamless wrap + smooth scan bands + SINGLE cursor box
//
// Changes from your provided shader:
//  - Removed tunnel/cylinder projection (pattern is flat in screen space)
//  - Removed ALL focus point / focus screen logic + uniforms
//  - Kept: seamless wrapping, drift+snap, glyph field, scan bands,
//          single cursor (1..3), filled invert cutout, 2-parallel crosshair lines
//  - Grid lines are only hinted near cursor/crosshair
// ============================================================

uniform sampler2D Sampler0;

uniform vec2  uInvScreenSize;
uniform vec2  uScrollUV;        // (unused here, kept for compatibility)
uniform float uGridCells;
uniform float uLineWidthPx;
uniform float uFovScale;        // (unused here, kept for compatibility)
uniform float uCamY;            // (unused here, kept for compatibility)
uniform float uOpacity;
uniform float uEffectAmount;
uniform float uElectricPower;

uniform float uTime;
uniform float uAnimSpeed;
uniform float GameTime;

in vec4 vColor;
out vec4 fragColor;

// ---------- Hash helpers ----------
float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}
float hash11(float p) {
    float x = fract(p * 0.1031);
    x *= x + 33.33;
    x *= x + x;
    return fract(x);
}
float saturate(float x) { return clamp(x, 0.0, 1.0); }

// Positive modulo for floats
float pmod(float x, float m) { return mod(mod(x, m) + m, m); }
vec2  pmod2(vec2 x, float m) { return vec2(pmod(x.x, m), pmod(x.y, m)); }

// wrap-safe: is i within [start, start+size) on a ring of length period?
float inRangeWrap(float i, float start, float size, float period) {
    float di = pmod(i - start, period);
    return 1.0 - step(size, di); // 1 if di < size
}

// Sample a glyph from the 50-column strip atlas
float sampleGlyph(vec2 cellUV, float glyphIndex, float pad, float edgeLo, float edgeHi) {
    vec2 glyphLocal = (cellUV - vec2(pad)) / max(vec2(1.0 - 2.0 * pad), vec2(1e-6));
    float inBox = step(0.0, glyphLocal.x) * step(0.0, glyphLocal.y) *
                  step(glyphLocal.x, 1.0) * step(glyphLocal.y, 1.0);

    float u0 = glyphIndex / 50.0;
    float u1 = (glyphIndex + 1.0) / 50.0;

    vec2 atlasUV = vec2(mix(u0, u1, clamp(glyphLocal.x, 0.0, 1.0)),
                        1.0 - clamp(glyphLocal.y, 0.0, 1.0));

    float src = texture(Sampler0, atlasUV).r;
    return smoothstep(edgeHi, edgeLo, src) * inBox;
}

void main() {
    vec2 screenUV = gl_FragCoord.xy * uInvScreenSize;
    if (screenUV.x < 0.0 || screenUV.x > 1.0 || screenUV.y < 0.0 || screenUV.y > 1.0) discard;

    // ---- ticks ----
    float tTick = (GameTime != 0.0) ? (GameTime * 24000.0) : uTime;

    float effect = saturate(uEffectAmount);
    float anim   = (uAnimSpeed <= 0.0) ? 1.0 : uAnimSpeed;

    // ---- power flicker (alpha modulation, no hard cutoff) ----
    float power = saturate(uElectricPower);
    float flickerStep = floor(tTick * 0.10);
    float gate = step(hash12(vec2(flickerStep, 91.7)), power);
    float flickerA = mix(0.18, 1.00, gate);
    flickerA *= mix(0.85, 1.15, hash12(vec2(flickerStep, 13.37)));
    float baseOpacity = uOpacity * vColor.a * flickerA;

    // ============================================================
    // FLAT base coordinates (screen tiling)
    // ============================================================
    // Make the pattern wrap seamlessly on screen: treat UV as a torus.
    // We keep vWrap stable (no camera Y now).
    float uWrap = fract(screenUV.x);
    float vWrap = fract(screenUV.y);

    // 50% denser grid (smaller)
    float cellsF = floor(uGridCells * 1.5 + 0.5);
    float cells  = max(cellsF, 1.0);

    vec2 gBase = vec2(uWrap, vWrap) * cells;

    // ============================================================
    // Drift + snap steps (periodic)
    // ============================================================
    const float cellPx = 15.0;
    const float snapPx = 30.0;

    vec2 baseVel = vec2(2.9, 2.1) * (0.85 + 0.15 * anim) * anim;

    vec2 wobble =
        vec2(10.0 * sin(tTick * 0.090) + 5.0 * sin(tTick * 0.173 + 1.7),
             8.0  * cos(tTick * 0.081) + 4.0 * sin(tTick * 0.161 + 3.2));

    vec2 scrollPx = tTick * baseVel + wobble;

    vec2 k = floor((scrollPx + vec2(cellPx)) / snapPx);
    vec2 gridPosPx = scrollPx - k * snapPx;
    vec2 cellShift = 2.0 * k;
    vec2 gridOffsetCells = gridPosPx / cellPx;
    gridOffsetCells += vec2(0.0, -1.0 / cellPx);

    vec2 gRender = gBase + gridOffsetCells;
    gRender = fract(gRender / cells) * cells;

    vec2 cellId = floor(gRender);
    vec2 cellUV = fract(gRender);

    vec2 logicalCellId = pmod2(cellId + cellShift, cells);

    // ============================================================
    // Grid lines (only shown near cursor/crosshair)
    // ============================================================
    vec2 fw = fwidth(gRender);
    float linePx = max(uLineWidthPx, 0.25);
    float lw = 0.5 * (linePx / cellPx);

    float gx = min(cellUV.x, 1.0 - cellUV.x);
    float gy = min(cellUV.y, 1.0 - cellUV.y);

    float gridLineX = 1.0 - smoothstep(lw, lw + fw.x, gx);
    float gridLineY = 1.0 - smoothstep(lw, lw + fw.y, gy);
    float gridLine  = max(gridLineX, gridLineY);

    // ============================================================
    // Glyph field
    // ============================================================
    vec2 clusterCell = floor(logicalCellId / 8.0);
    float cluster = smoothstep(0.25, 0.90, hash12(clusterCell + vec2(3.3, 7.7)));

    float baseDensity = mix(0.08, 0.22, effect);
    float density = clamp(baseDensity + cluster * 0.20 * effect, 0.0, 0.55);

    float present = step(hash12(logicalCellId), density);

    float lifeTicks = mix(60.0, 520.0, hash12(logicalCellId + vec2(17.3, 91.1)));
    float phase = fract((tTick + hash12(logicalCellId + vec2(7.7, 3.3)) * 1000.0) / max(lifeTicks, 1.0));
    float alive = step(phase, 0.92);

    float shimmer = 0.55 + 0.45 * sin(tTick * (0.18 + 0.12 * hash12(logicalCellId + 5.0))
                                      + hash12(logicalCellId) * 6.283185);
    shimmer = clamp(shimmer, 0.0, 1.0);

    float glyphIndex = floor(hash12(logicalCellId + vec2(3.1, 8.2)) * 50.0);
    float glyphCore  = sampleGlyph(cellUV, glyphIndex, 0.10, 0.65, 0.92);

    float selected = step(hash12(logicalCellId + vec2(99.4, 12.7)), 0.015);

    float glyphA = glyphCore * present * alive * baseOpacity * (0.55 + 0.75 * shimmer);

    // ============================================================
    // Smooth scan bands (visual only) in GRID-PIXEL space
    // ============================================================
    float widthPx  = cells * cellPx;
    float heightPx = cells * cellPx;

    vec2 pPx = gRender * cellPx;
    float x = pmod(pPx.x, widthPx);
    float y = pmod(pPx.y, heightPx);

    float scanBandsA = 0.0;
    const int BANDS = 10;

    for (int i = 0; i < BANDS; i++) {
        float fi = float(i);

        float align = step(0.35, hash11(fi + 9.13));
        float span  = (align > 0.5) ? widthPx : heightPx;
        float base  = hash11(fi + 2.19) * (span + 800.0) - 400.0;

        float dir   = (hash11(fi + 4.71) < 0.5) ? -1.0 : 1.0;
        float speed = mix(2.0, 10.0, hash11(fi + 0.37)) * dir;

        float jitter = (hash11(fi + 6.6) - 0.5) * 6.0 * sin(tTick * 0.13 + fi);
        float pos = pmod(base + tTick * speed * (1.0 + 0.25 * anim) + jitter, span);

        float coord = (align > 0.5) ? x : y;

        float wB = 1.8 + 0.8 * hash11(fi + 12.3);

        float d0 = abs(coord - pos);
        float d1 = abs(coord - (pos + cellPx));
        float d2 = abs(coord - (pos + 2.0 * cellPx));

        float a0 = 1.0 - smoothstep(wB - 1.0, wB + 1.5, d0);
        float a1 = 1.0 - smoothstep(wB - 1.0, wB + 1.5, d1);
        float a2 = 1.0 - smoothstep(wB - 1.0, wB + 1.5, d2);

        float a = max(a0, max(a1, a2));
        a *= mix(0.25, 1.00, hash11(fi + 8.8));

        scanBandsA = max(scanBandsA, a);
    }

    // ============================================================
    // SINGLE Cursor: slowed movement + 2-parallel crosshair lines
    // ============================================================
    float cursorRate = 45.0;
    float stepT = floor(tTick / cursorRate);
    float fracT = fract(tTick / cursorRate);
    fracT = fracT * fracT * (3.0 - 2.0 * fracT);

    vec2 p0 = vec2(hash12(vec2(stepT - 1.0, 1.23)), hash12(vec2(stepT - 1.0, 4.56))) * cells;
    vec2 p1 = vec2(hash12(vec2(stepT + 0.0, 1.23)), hash12(vec2(stepT + 0.0, 4.56))) * cells;

    vec2 curCell = floor(mix(p0, p1, fracT));
    curCell = pmod2(curCell, cells);

    float sizeStep = floor(tTick / 60.0);
    float selSize  = 1.0 + floor(hash11(sizeStep * 1.37) * 3.0);

    float oxCell = curCell.x;
    float oyCell = curCell.y;

    float inX = inRangeWrap(cellId.x, oxCell, selSize, cells);
    float inY = inRangeWrap(cellId.y, oyCell, selSize, cells);
    float inRect = inX * inY;

    float oxPx = oxCell * cellPx;
    float oyPx = oyCell * cellPx;
    float rectWpx = selSize * cellPx;
    vec2 lp = vec2(pmod(x - oxPx, widthPx), pmod(y - oyPx, heightPx));

    float dEdgePx = min(min(lp.x, rectWpx - lp.x), min(lp.y, rectWpx - lp.y));
    float thPx = 2.0;
    float aaPx = max(max(fwidth(lp.x), fwidth(lp.y)), 1.0);
    float boxOutline = (1.0 - smoothstep(thPx, thPx + aaPx, dEdgePx)) * inRect;
    float boxFill = inRect;

    // ---- Crosshair: TWO lines on each axis aligned to grid edges ----
    float vx0 = oxCell * cellPx;
    float vx1 = pmod((oxCell + selSize) * cellPx, widthPx);
    float hy0 = oyCell * cellPx;
    float hy1 = pmod((oyCell + selSize) * cellPx, heightPx);

    float dx0 = min(abs(x - vx0), abs(x - (vx0 + widthPx)));
    float dx1 = min(abs(x - vx1), abs(x - (vx1 + widthPx)));
    float dy0 = min(abs(y - hy0), abs(y - (hy0 + heightPx)));
    float dy1 = min(abs(y - hy1), abs(y - (hy1 + heightPx)));

    float crossW = 1.6;
    float vA = max(1.0 - smoothstep(crossW - 0.6, crossW + 1.4, dx0),
                   1.0 - smoothstep(crossW - 0.6, crossW + 1.4, dx1));
    float hA = max(1.0 - smoothstep(crossW - 0.6, crossW + 1.4, dy0),
                   1.0 - smoothstep(crossW - 0.6, crossW + 1.4, dy1));

    float crossA = max(vA, hA);

    float nearBlock = 1.0 - smoothstep(0.0, 220.0,
                        min(min(dx0, dx1), min(dy0, dy1)));
    crossA *= (0.50 + 0.50 * nearBlock);

    float gridHintA = gridLine * max(crossA, boxFill) * baseOpacity * 0.22;

    // Invert glyph inside cursor: fill + dark cutouts
    float glyphA_outside = glyphA * (1.0 - inRect);
    float hole = glyphCore * present * alive * inRect;
    float holeStrength = (0.70 + 0.25 * effect);

    // ============================================================
    // Compose
    // ============================================================
    vec3 baseCyan = vec3(0.55, 1.00, 0.90);
    vec3 redTint  = vec3(1.00, 0.25, 0.35);
    vec3 glyphCol = mix(baseCyan, redTint, selected);

    float scanBandsFinal = scanBandsA * baseOpacity * (0.18 + 0.55 * effect);
    float boxFillA       = boxFill    * baseOpacity * (0.20 + 0.55 * effect);
    float boxOutlineA    = boxOutline * baseOpacity * (0.30 + 0.70 * effect);
    float crossFinalA    = crossA     * baseOpacity * (0.16 + 0.35 * effect);

    // Mild vignette
    vec2 dv = screenUV * 2.0 - 1.0;
    float vign = 1.0 - 0.45 * smoothstep(0.65, 1.25, dot(dv, dv));
    vign = clamp(vign, 0.0, 1.0);

    float outA = 0.0;
    vec3 outRGB = vec3(0.0);

    outRGB += baseCyan * scanBandsFinal;   outA = max(outA, scanBandsFinal);
    outRGB += baseCyan * crossFinalA;      outA = max(outA, crossFinalA);
    outRGB += baseCyan * gridHintA;        outA = max(outA, gridHintA);

    outRGB += glyphCol  * glyphA_outside;  outA = max(outA, glyphA_outside);

    outRGB += baseCyan * boxFillA;         outA = max(outA, boxFillA);
    outRGB += baseCyan * boxOutlineA;      outA = max(outA, boxOutlineA);

    if (outA <= 0.001) discard;

    outRGB = outRGB / max(outA, 1e-6);

    // Punch glyph holes inside the filled cursor
    float holeA = hole * holeStrength;
    outRGB *= (1.0 - holeA);

    // Mild glow
    float glow = smoothstep(0.25, 0.95, outA);
    outRGB = mix(outRGB, vec3(1.0), 0.10 * glow);

    outRGB *= vign;
    outA   *= vign;

    fragColor = vec4(outRGB * vColor.rgb, outA);
}
