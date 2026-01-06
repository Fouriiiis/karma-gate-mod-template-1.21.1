#version 150
// ============================================================
// Square-cylinder projection shader (4 projectors at center)
//
// UV coordinates from vertex shader (computed in renderer):
//   vWorldUV.x = unfolded perimeter distance around a square cylinder
//                centered on the zone center. This wraps seamlessly
//                around corners (N->E->S->W).
//   vWorldUV.y = world Y height
//
// The grid animation + glyph logic remains unchanged.
// ============================================================

uniform sampler2D Sampler0;

uniform float uGridCells;
uniform float uLineWidthPx;
uniform float uOpacity;
uniform float uEffectAmount;
uniform float uElectricPower;

uniform float uTime;
uniform float uAnimSpeed;
uniform float GameTime;

in vec4 vColor;
in vec2 vWorldUV;  // x = square-perimeter U, y = world Y
in vec3 vWorldPos; // true world-space position (reconstructed in vertex shader)

// Zone projection parameters (set per-zone by the renderer)
uniform float uZoneCenterX;
uniform float uZoneCenterZ;
uniform float uZoneRadius;
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

// Periodic distance on a ring [0, period)
float ringDist(float a, float b, float period) {
    float d = abs(a - b);
    return min(d, period - d);
}

vec2  pmod2(vec2 x, float m) { return vec2(pmod(x.x, m), pmod(x.y, m)); }


// ============================================================
// Per-fragment square-cylinder ray projection (prevents curved lines on big quads)
// Matches GridProjectRenderer.computeSquarePerimeterU(...)
// ============================================================
float computeSquarePerimeterU_ws(float worldX, float worldZ, float centerX, float centerZ, float radius) {
    float R = max(radius, 1e-6);

    float rx = worldX - centerX;
    float rz = worldZ - centerZ;

    float len = sqrt(rx * rx + rz * rz);
    float dx = (len > 1e-9) ? (rx / len) : 1.0;
    float dz = (len > 1e-9) ? (rz / len) : 0.0;

    // Ray-square boundary intersection (scale so max(|x|,|z|)=R)
    float m = max(abs(dx), abs(dz));
    if (m < 1e-9) m = 1.0;

    float hx = dx * (R / m);
    float hz = dz * (R / m);

    // Convert boundary point to perimeter distance [0..8R)
    // Origin at (x=+R, z=-R), increasing CCW: East -> North -> West -> South
    float ax = abs(hx);
    float az = abs(hz);

    float u;
    if (ax >= az) {
        if (hx >= 0.0) {
            // East side: x=+R, z:-R..R
            u = hz + R;                 // [0..2R)
        } else {
            // West side: x=-R, z:R..-R
            u = 4.0 * R + (R - hz);     // [4R..6R)
        }
    } else {
        if (hz >= 0.0) {
            // North side: z=+R, x:R..-R
            u = 2.0 * R + (R - hx);     // [2R..4R)
        } else {
            // South side: z=-R, x:-R..R
            u = 6.0 * R + (hx + R);     // [6R..8R)
        }
    }

    // IMPORTANT:
    // We do NOT wrap with mod() here.
    // Keeping u continuous eliminates the visible seam caused by the 8R->0 jump at the chosen seam corner.
    //
    // To mimic the Java-side "per-quad seam fix", we shift the EAST/SOUTH corner region forward by +perim
    // so values near (x=+R, z=-R) become ~8R instead of ~0R.
    float perim = 8.0 * R;

    // If we're on the east side in the south half (hz < 0), push forward by one full wrap.
    // This moves the branch cut away from that corner and makes the pattern loop cleanly there.
    return u;
}

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
    // Square-cylinder projection coordinates
    // ============================================================
    float cells = max(floor(uGridCells + 0.5), 1.0);

    // vWorldUV.x = unfolded square perimeter U (wraps around corners)
    // vWorldUV.y = world Y
    // Compute projection per-fragment from true world position (prevents interpolation warping)
    float uPerim = computeSquarePerimeterU_ws(vWorldPos.x, vWorldPos.z, uZoneCenterX, uZoneCenterZ, uZoneRadius);

    // Choose a continuous unwrap of uPerim that matches the per-vertex reference (vWorldUV.x).
    // This avoids derivative-heuristic seams that can appear mid-wall on some meshes.
    float perim = 8.0 * max(uZoneRadius, 1e-6);
    float uRaw = pmod(uPerim, perim);

    // vWorldUV.x comes from the renderer and is already made continuous per-quad.
    float uRef = vWorldUV.x;

    float u0 = uRaw;
    float u1 = uRaw + perim;
    float u2 = uRaw - perim;

    float uCont = u0;
    if (abs(u1 - uRef) < abs(uCont - uRef)) uCont = u1;
    if (abs(u2 - uRef) < abs(uCont - uRef)) uCont = u2;

    vec2 gBase = vec2(uCont, vWorldPos.y);

    // ============================================================
    // Drift + snap steps (periodic animation)
    // ============================================================
    const float cellPx = 1.0;  // 1 block = 1 cell
    const float snapPx = 2.0;

    // Make the *pattern* itself tile seamlessly around the square-perimeter loop.
    // This ensures U≈0 and U≈perim render identical content.
    float perimCells = max(floor(perim / cellPx + 0.5), 1.0);

    vec2 baseVel = vec2(0.029, 0.021) * (0.85 + 0.15 * anim) * anim;

    vec2 wobble =
        vec2(0.1 * sin(tTick * 0.090) + 0.05 * sin(tTick * 0.173 + 1.7),
             0.08 * cos(tTick * 0.081) + 0.04 * sin(tTick * 0.161 + 3.2));

    vec2 scrollOffset = tTick * baseVel + wobble;

    vec2 k = floor((scrollOffset + vec2(cellPx)) / snapPx);
    vec2 gridPosPx = scrollOffset - k * snapPx;
    vec2 cellShift = 2.0 * k;
    vec2 gridOffsetCells = gridPosPx / cellPx;

    vec2 gRender = gBase + gridOffsetCells;

    // Wrap the X coordinate onto a ring of length perimCells.
    float gxLoop = pmod(gRender.x, perimCells);

    // Keep anti-aliasing stable across the wrap by locally unwrapping based on derivatives.
    float gxLoopCont = gxLoop;
    float dg = max(abs(dFdx(gxLoop)), abs(dFdy(gxLoop)));
    if (dg > perimCells * 0.5) {
        if (gxLoop < perimCells * 0.5) gxLoopCont += perimCells;
    }

    vec2 gRenderAA = vec2(gxLoopCont, gRender.y);

    vec2 cellId = vec2(floor(gxLoop), floor(gRender.y));
    vec2 cellUV = fract(gRenderAA);

    vec2 logicalCellId = cellId + cellShift;
    logicalCellId.x = pmod(logicalCellId.x, perimCells);

    // ============================================================
    // Grid lines
    // ============================================================
    vec2 fw = fwidth(gRenderAA);
    float linePx = max(uLineWidthPx, 0.25);
    float lw = 0.5 * linePx;

    float gx = min(cellUV.x, 1.0 - cellUV.x);
    float gy = min(cellUV.y, 1.0 - cellUV.y);

    float gridLineX = 1.0 - smoothstep(lw, lw + fw.x * 2.0, gx);
    float gridLineY = 1.0 - smoothstep(lw, lw + fw.y * 2.0, gy);
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
    float widthPx  = 20.0;
    float heightPx = 20.0;

    // Ensure scan-band math is also periodic around the perimeter loop.
    // First wrap to the perimeter length, then apply the band period.
    vec2 pPx = gRenderAA;
    float xPer = pmod(pPx.x, perimCells);
    float x = pmod(xPer, widthPx);
    float y = pmod(pPx.y, heightPx);

    float scanBandsA = 0.0;
    const int BANDS = 10;

    for (int i = 0; i < BANDS; i++) {
        float fi = float(i);

        float align = step(0.35, hash11(fi + 9.13));
        float span  = (align > 0.5) ? widthPx : heightPx;
        float base  = hash11(fi + 2.19) * (span + 40.0) - 20.0;

        float dir   = (hash11(fi + 4.71) < 0.5) ? -1.0 : 1.0;
        float speed = mix(0.02, 0.10, hash11(fi + 0.37)) * dir;

        float jitter = (hash11(fi + 6.6) - 0.5) * 0.3 * sin(tTick * 0.13 + fi);
        float pos = pmod(base + tTick * speed * (1.0 + 0.25 * anim) + jitter, span);

        float coord = (align > 0.5) ? x : y;

        float wB = 0.12 + 0.05 * hash11(fi + 12.3);

        float d0 = ringDist(coord, pos, span);
        float pos2 = pmod(pos + 1.0, span);
        float d1 = ringDist(coord, pos2, span);

        float a0 = 1.0 - smoothstep(wB - 0.05, wB + 0.1, d0);
        float a1 = 1.0 - smoothstep(wB - 0.05, wB + 0.1, d1);

        // Two-line scan (not three)
        float a = max(a0, a1);

        a *= mix(0.25, 1.00, hash11(fi + 8.8));

        scanBandsA = max(scanBandsA, a);
    }

    // ============================================================
    // Cursor box (world-space)
    // ============================================================
    float cursorRate = 45.0;
    float stepT = floor(tTick / cursorRate);
    float fracT = fract(tTick / cursorRate);
    fracT = fracT * fracT * (3.0 - 2.0 * fracT);

    vec2 p0 = vec2(hash12(vec2(stepT - 1.0, 1.23)), hash12(vec2(stepT - 1.0, 4.56))) * 20.0 - 10.0;
    vec2 p1 = vec2(hash12(vec2(stepT + 0.0, 1.23)), hash12(vec2(stepT + 0.0, 4.56))) * 20.0 - 10.0;

    vec2 curCell = floor(mix(p0, p1, fracT));
    // Wrap cursor along the perimeter so it doesn't introduce a seam.
    curCell.x = pmod(curCell.x, perimCells);

    float sizeStep = floor(tTick / 60.0);
    float selSize  = 1.0 + floor(hash11(sizeStep * 1.37) * 3.0);

    float inX = step(curCell.x, cellId.x) * step(cellId.x, curCell.x + selSize - 0.5);
    float inY = step(curCell.y, cellId.y) * step(cellId.y, curCell.y + selSize - 0.5);
    float inRect = inX * inY;

    // Use wrapped (looped) X for cursor math (avoids issues when gRenderAA is locally unwrapped).
    vec2 gCursor = vec2(gxLoop, gRender.y);

    vec2 lp = gCursor - curCell;
    float dEdge = min(min(lp.x, selSize - lp.x), min(lp.y, selSize - lp.y));
    float th = 0.12;
    float aa = max(max(fw.x, fw.y), 0.05);
    float boxOutline = (1.0 - smoothstep(th, th + aa, dEdge)) * inRect;
    float boxFill = inRect;

    // ---- Crosshair lines ----
    float vx0 = curCell.x;
    float vx1 = curCell.x + selSize;
    float hy0 = curCell.y;
    float hy1 = curCell.y + selSize;

    float dx0 = abs(gCursor.x - vx0);
    float dx1 = abs(gCursor.x - vx1);
    float dy0 = abs(gCursor.y - hy0);
    float dy1 = abs(gCursor.y - hy1);

    float crossW = 0.1;
    float vA = max(1.0 - smoothstep(crossW - 0.03, crossW + 0.08, dx0),
                   1.0 - smoothstep(crossW - 0.03, crossW + 0.08, dx1));
    float hA = max(1.0 - smoothstep(crossW - 0.03, crossW + 0.08, dy0),
                   1.0 - smoothstep(crossW - 0.03, crossW + 0.08, dy1));

    float crossA = max(vA, hA);

    float nearBlock = 1.0 - smoothstep(0.0, 15.0,
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
    float gridLineFinal  = gridLine   * baseOpacity * 0.15;

    float outA = 0.0;
    vec3 outRGB = vec3(0.0);

    outRGB += baseCyan * gridLineFinal;    outA = max(outA, gridLineFinal);
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

    fragColor = vec4(outRGB * vColor.rgb, outA);
}