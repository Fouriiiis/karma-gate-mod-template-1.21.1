#version 150
// ============================================================
// Tunnel + seamless wrap + smooth scan bands + SINGLE cursor box
//
// Changes in this revision:
//  - Cursor crosshair is now TWO parallel lines in each direction
//    (i.e. 2 vertical + 2 horizontal), aligned to the grid (cellPx offset).
//  - Cursor movement slowed down (updates less frequently + eased).
//
// Still:
//  - Tunnel projection
//  - Seamless wrap (no seam)
//  - Grid lines only hinted near cursor/crosshair
//  - ONE cursor total
//  - Cursor size 1..3 cells
//  - Cursor block filled; glyphs inside appear inverted (dark cutouts)
// ============================================================

uniform sampler2D Sampler0;


uniform vec2  uInvScreenSize;
uniform vec2  uScrollUV;
uniform float uGridCells;
uniform float uLineWidthPx;
uniform float uFovScale;
uniform float uCamY;
uniform float uOpacity;
uniform float uEffectAmount;
uniform float uElectricPower;

uniform float uTime;
uniform float uAnimSpeed;
uniform float GameTime;

uniform float uFocusCount;
uniform float uFocusBorderPx;
uniform float uFocusLinePx;

uniform vec4 uFocusScreen0;
uniform vec4 uFocusScreen1;
uniform vec4 uFocusScreen2;
uniform vec4 uFocusScreen3;
uniform vec4 uFocusScreen4;
uniform vec4 uFocusScreen5;
uniform vec4 uFocusScreen6;
uniform vec4 uFocusScreen7;
uniform vec4 uFocusScreen8;
uniform vec4 uFocusScreen9;
uniform vec4 uFocusScreen10;
uniform vec4 uFocusScreen11;
uniform vec4 uFocusScreen12;
uniform vec4 uFocusScreen13;
uniform vec4 uFocusScreen14;
uniform vec4 uFocusScreen15;

uniform vec4 uFocusPoint0;
uniform vec4 uFocusPoint1;
uniform vec4 uFocusPoint2;
uniform vec4 uFocusPoint3;
uniform vec4 uFocusPoint4;
uniform vec4 uFocusPoint5;
uniform vec4 uFocusPoint6;
uniform vec4 uFocusPoint7;
uniform vec4 uFocusPoint8;
uniform vec4 uFocusPoint9;
uniform vec4 uFocusPoint10;
uniform vec4 uFocusPoint11;
uniform vec4 uFocusPoint12;
uniform vec4 uFocusPoint13;
uniform vec4 uFocusPoint14;
uniform vec4 uFocusPoint15;

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
vec3 safeNormalize(vec3 v) { return v / max(length(v), 1e-6); }

vec4 getFocusPoint(int i) {
    if (i == 0) return uFocusPoint0;
    if (i == 1) return uFocusPoint1;
    if (i == 2) return uFocusPoint2;
    if (i == 3) return uFocusPoint3;
    if (i == 4) return uFocusPoint4;
    if (i == 5) return uFocusPoint5;
    if (i == 6) return uFocusPoint6;
    if (i == 7) return uFocusPoint7;
    if (i == 8) return uFocusPoint8;
    if (i == 9) return uFocusPoint9;
    if (i == 10) return uFocusPoint10;
    if (i == 11) return uFocusPoint11;
    if (i == 12) return uFocusPoint12;
    if (i == 13) return uFocusPoint13;
    if (i == 14) return uFocusPoint14;
    return uFocusPoint15;
}

vec4 getFocusScreen(int i) {
    if (i == 0) return uFocusScreen0;
    if (i == 1) return uFocusScreen1;
    if (i == 2) return uFocusScreen2;
    if (i == 3) return uFocusScreen3;
    if (i == 4) return uFocusScreen4;
    if (i == 5) return uFocusScreen5;
    if (i == 6) return uFocusScreen6;
    if (i == 7) return uFocusScreen7;
    if (i == 8) return uFocusScreen8;
    if (i == 9) return uFocusScreen9;
    if (i == 10) return uFocusScreen10;
    if (i == 11) return uFocusScreen11;
    if (i == 12) return uFocusScreen12;
    if (i == 13) return uFocusScreen13;
    if (i == 14) return uFocusScreen14;
    return uFocusScreen15;
}

float sdSegment(vec2 p, vec2 a, vec2 b) {
    vec2 ba = b - a;
    float denom = dot(ba, ba);
    if (denom < 1e-6) return length(p - a);
    float h = clamp(dot(p - a, ba) / denom, 0.0, 1.0);
    return length((p - a) - ba * h);
}

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
    // Tunnel mapping (cylinder around Y)
    // ============================================================
    float aspect = uInvScreenSize.y / uInvScreenSize.x;
    vec2 ndc = screenUV * 2.0 - 1.0;
    ndc.x *= aspect;

    vec3 rd = safeNormalize(vec3(ndc * uFovScale, -1.0));

    float pitch = -uScrollUV.y + 3.14159;
    float cp = cos(pitch);
    float sp = sin(pitch);
    vec3 rd_p = vec3(rd.x, rd.y * cp - rd.z * sp, rd.y * sp + rd.z * cp);

    float yaw = uScrollUV.x;
    float cy = cos(yaw);
    float sy = sin(yaw);
    vec3 rd_w = vec3(rd_p.x * cy + rd_p.z * sy, rd_p.y, -rd_p.x * sy + rd_p.z * cy);

    float dist = 1.0 / (length(rd_w.xz) + 1e-6);
    vec3 pos = rd_w * dist;

    float uAng = atan(pos.z, pos.x) / 6.283185;
    float vLin = (pos.y - uCamY) / 6.283185;

    float uWrap = fract(uAng + 1.0);
    float vWrap = fract(vLin + 1000.0);

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
    // Smooth scan bands (visual only)
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
    // Slower updates: was /12, now /45 (about ~3.75x slower).
    // Smooth easing so it glides rather than snapping aggressively.
    float cursorRate = 45.0;
    float stepT = floor(tTick / cursorRate);
    float fracT = fract(tTick / cursorRate);
    // ease in/out
    fracT = fracT * fracT * (3.0 - 2.0 * fracT);

    vec2 p0 = vec2(hash12(vec2(stepT - 1.0, 1.23)), hash12(vec2(stepT - 1.0, 4.56))) * cells;
    vec2 p1 = vec2(hash12(vec2(stepT + 0.0, 1.23)), hash12(vec2(stepT + 0.0, 4.56))) * cells;

    vec2 curCell = floor(mix(p0, p1, fracT));
    curCell = pmod2(curCell, cells);

    // Size 1..3 (changes slower too)
    float sizeStep = floor(tTick / 60.0);
    float selSize  = 1.0 + floor(hash11(sizeStep * 1.37) * 3.0);

    float oxCell = curCell.x;
    float oyCell = curCell.y;

    // Wrap-safe rect membership
    float inX = inRangeWrap(cellId.x, oxCell, selSize, cells);
    float inY = inRangeWrap(cellId.y, oyCell, selSize, cells);
    float inRect = inX * inY;

    // Pixel-space local coords inside cursor (wrap-safe)
    float oxPx = oxCell * cellPx;
    float oyPx = oyCell * cellPx;
    float rectWpx = selSize * cellPx;
    vec2 lp = vec2(pmod(x - oxPx, widthPx), pmod(y - oyPx, heightPx));

    // Outline + fill
    float dEdgePx = min(min(lp.x, rectWpx - lp.x), min(lp.y, rectWpx - lp.y));
    float thPx = 2.0;
    float aaPx = max(max(fwidth(lp.x), fwidth(lp.y)), 1.0);
    float boxOutline = (1.0 - smoothstep(thPx, thPx + aaPx, dEdgePx)) * inRect;
    float boxFill = inRect;

    // ---- Crosshair: TWO lines on each axis aligned to grid ----
    // We want lines aligned to the selected block's grid edges:
    // vertical lines at oxCell and oxCell+selSize
    // horizontal lines at oyCell and oyCell+selSize
    float vx0 = oxCell * cellPx;
    float vx1 = pmod((oxCell + selSize) * cellPx, widthPx);
    float hy0 = oyCell * cellPx;
    float hy1 = pmod((oyCell + selSize) * cellPx, heightPx);

    // wrap-safe distance to a vertical/horizontal line
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

    // Slight emphasis near the block so it matches the ref feel
    float nearBlock = 1.0 - smoothstep(0.0, 220.0,
                        min(min(dx0, dx1), min(dy0, dy1)));
    crossA *= (0.50 + 0.50 * nearBlock);

    // Grid hint only near crosshair/cursor
    float gridHintA = gridLine * max(crossA, boxFill) * baseOpacity * 0.22;

    // ============================================================
    // Invert glyph inside cursor: filled cyan + dark glyph cutouts
    // ============================================================
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

    float boxFillA    = boxFill    * baseOpacity * (0.20 + 0.55 * effect);
    float boxOutlineA = boxOutline * baseOpacity * (0.30 + 0.70 * effect);
    float crossFinalA = crossA     * baseOpacity * (0.16 + 0.35 * effect);

    // ============================================================
    // Focus points (world-anchored rings projected to screen)
    // ============================================================
    float focusCut = 0.0;
    float focusRingA = 0.0;
    float focusLineA = 0.0;
    int fpN = int(clamp(uFocusCount, 0.0, 16.0));
    if (fpN > 0 && uFocusBorderPx > 0.0) {
        vec2 fragPx = gl_FragCoord.xy;

        // Lines between points (under circles)
        if (fpN > 1 && uFocusLinePx > 0.0) {
            for (int i = 0; i < 15; i++) {
                if (i + 1 >= fpN) break;
                vec4 a = getFocusScreen(i);
                vec4 b = getFocusScreen(i + 1);
                if (a.w < 0.5 || b.w < 0.5) continue;

                float d = sdSegment(fragPx, a.xy, b.xy);
                float aa = max(fwidth(d), 0.75);
                float w = 0.5 * uFocusLinePx;
                float line = 1.0 - smoothstep(w - aa, w + aa, d);
                focusLineA = max(focusLineA, line);
            }
        }

        for (int i = 0; i < 16; i++) {
            if (i >= fpN) break;
            vec4 fp = getFocusScreen(i);
            if (fp.w < 0.5) continue;

            vec2 fpPx = fp.xy;
            float radiusPx = fp.z;
            if (radiusPx <= 0.5) continue;

            float distPx = length(fragPx - fpPx);
            float aa = max(fwidth(distPx), 0.75);

            float inside = 1.0 - smoothstep(radiusPx - aa, radiusPx + aa, distPx);
            focusCut = max(focusCut, inside);

            float halfW = 0.5 * uFocusBorderPx;
            float outer = smoothstep(radiusPx + halfW + aa, radiusPx + halfW - aa, distPx);
            float inner = smoothstep(radiusPx - halfW + aa, radiusPx - halfW - aa, distPx);
            float ring = clamp(outer - inner, 0.0, 1.0);
            focusRingA = max(focusRingA, ring);
        }
    }

    // Cut out any pattern contributions inside a focus point.
    float keep = 1.0 - focusCut;
    scanBandsFinal *= keep;
    boxFillA *= keep;
    boxOutlineA *= keep;
    crossFinalA *= keep;
    gridHintA *= keep;
    glyphA_outside *= keep;
    holeStrength *= keep;
    focusLineA *= keep;

    // Vignette
    vec2 dv = screenUV * 2.0 - 1.0;
    float vign = 1.0 - 0.45 * smoothstep(0.65, 1.25, dot(dv, dv));
    vign = clamp(vign, 0.0, 1.0);

    float outA = 0.0;
    vec3 outRGB = vec3(0.0);

    outRGB += baseCyan * scanBandsFinal; outA = max(outA, scanBandsFinal);

    outRGB += baseCyan * crossFinalA;    outA = max(outA, crossFinalA);
    outRGB += baseCyan * gridHintA;      outA = max(outA, gridHintA);

    float focusLineFinal = focusLineA * baseOpacity * 0.75;
    outRGB += baseCyan * focusLineFinal; outA = max(outA, focusLineFinal);

    outRGB += glyphCol  * glyphA_outside; outA = max(outA, glyphA_outside);

    outRGB += baseCyan * boxFillA;       outA = max(outA, boxFillA);
    outRGB += baseCyan * boxOutlineA;    outA = max(outA, boxOutlineA);

    float focusRingFinal = focusRingA * baseOpacity;
    outRGB += baseCyan * focusRingFinal; outA = max(outA, focusRingFinal);

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
