#version 150

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
in vec2 vWorldUV;
in vec3 vWorldPos;

uniform float uZoneCenterX;
uniform float uZoneCenterZ;
uniform float uZoneRadius;

uniform float uZoneMinX;
uniform float uZoneMaxX;
uniform float uZoneMinZ;
uniform float uZoneMaxZ;

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

// AA line helper (1 at center, 0 outside)
float lineA(float dist, float halfW, float aa) {
    return 1.0 - smoothstep(halfW, halfW + aa, dist);
}

void main() {
    float tTick = (GameTime != 0.0) ? (GameTime * 24000.0) : uTime;

    float effect = saturate(uEffectAmount);
    float anim   = (uAnimSpeed <= 0.0) ? 1.0 : uAnimSpeed;

    // ============================================================
    // Zone-edge inward fade
    // ============================================================
    float dLeft  = vWorldPos.x - uZoneMinX;
    float dRight = uZoneMaxX - vWorldPos.x;
    float dNear  = vWorldPos.z - uZoneMinZ;
    float dFar   = uZoneMaxZ - vWorldPos.z;
    float inward = min(min(dLeft, dRight), min(dNear, dFar));
    inward = max(inward, 0.0);

    const float borderBlocks = 10.0;
    const float fadeBlocks   = 10.0;
    float zoneFade = 1.0 - smoothstep(borderBlocks, borderBlocks + fadeBlocks, inward);
    if (zoneFade <= 0.001) discard;

    // ---- power flicker ----
    float power = saturate(uElectricPower);
    float flickerStep = floor(tTick * 0.10);
    float gate = step(hash11(flickerStep + 91.7), power);
    float flickerA = mix(0.18, 1.00, gate) * mix(0.85, 1.15, hash11(flickerStep + 169.7));
    float baseOpacity = uOpacity * vColor.a * flickerA * zoneFade;
    if (baseOpacity < 0.01) discard;

    // ============================================================
    // Projection coordinates
    // ============================================================
    const float glyphScale = 1.0;

    float uCont = vWorldUV.x;
    vec2 gBase = vec2(uCont, vWorldPos.y);
    vec2 gScaled = gBase / glyphScale;

    const float cellPx = 1.0;
    const float snapPx = 2.0;

    float perim = 8.0 * max(uZoneRadius, 1e-6);
    float perimScaled = perim / glyphScale;
    float perimCells = max(floor(perimScaled / cellPx + 0.5), 1.0);

    vec2 baseVel = vec2(0.029, 0.021) * (0.85 + 0.15 * anim) * anim;

    vec2 wobble =
        vec2(0.1 * sin(tTick * 0.090) + 0.05 * sin(tTick * 0.173 + 1.7),
             0.08 * cos(tTick * 0.081) + 0.04 * sin(tTick * 0.161 + 3.2));

    // ============================================================
    // Requested: glyph grid drift should be MORE aggressive
    // Previously: 3.0, now 6.0 (2× faster than before, 6× original)
    // ============================================================
    const float DRIFT_MULT = 6.0;
    vec2 scrollOffset = DRIFT_MULT * (tTick * baseVel + wobble);

    vec2 k = floor((scrollOffset + vec2(cellPx)) / snapPx);
    vec2 gridPosPx = scrollOffset - k * snapPx;
    vec2 cellShift = 2.0 * k;
    vec2 gridOffsetCells = gridPosPx / cellPx;

    vec2 gRender = gScaled + gridOffsetCells;

    float gxLoop = pmod(gRender.x, perimCells);

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

    vec2 fw = fwidth(gRenderAA);
    float aa = max(max(fw.x, fw.y), 0.05);

    // ============================================================
    // Glyph field
    // ============================================================
    float baseHash  = hash12(logicalCellId);
    float baseHash2 = hash12(logicalCellId + 17.3);

    // IMPORTANT: always-available glyph index for this cell
    // (used by cursor highlight even if glyph isn't "present/alive")
    float glyphIndexCell = floor(fract(baseHash * 7.31) * 50.0);

    vec2 clusterCell = floor(logicalCellId / 8.0);
    float cluster = smoothstep(0.25, 0.90, hash12(clusterCell));

    float baseDensity = mix(0.08, 0.22, effect);
    float density = clamp(baseDensity + cluster * 0.20 * effect, 0.0, 0.55);
    float present = step(baseHash, density);

    float glyphA = 0.0;
    float glyphCoreVisible = 0.0;

    if (present > 0.5) {
        float lifeTicks = mix(60.0, 520.0, baseHash2);
        float phase = fract((tTick + baseHash * 1000.0) / max(lifeTicks, 1.0));
        float alive = step(phase, 0.92);

        if (alive > 0.5) {
            float shimmer = 0.55 + 0.45 * sin(tTick * (0.18 + 0.12 * baseHash2) + baseHash * 6.283185);
            glyphCoreVisible = sampleGlyph(cellUV, glyphIndexCell, 0.10, 0.65, 0.92);
            glyphA = glyphCoreVisible * baseOpacity * (0.55 + 0.75 * shimmer);
        }
    }

    float selected = step(fract(baseHash * 99.4), 0.015);

    // ============================================================
    // Scan lines (brighter) + slowed motion preserved
    // ============================================================
    const float SCAN_SLOW = 2.8;
    float tScan = tTick / SCAN_SLOW;

    const int MAX_SCAN = 6;
    float scanLinesA = 0.0;

    vec2 gScan = vec2(gxLoop, gRender.y);

    float lineHalfW = 0.045;
    float driftBoost = mix(0.6, 1.0, effect);

    for (int i = 0; i < MAX_SCAN; i++) {
        float fi = float(i);
        float isH = step(0.5, fract(fi * 0.5)); // 0,0,1,1...

        float seed = 73.1 + fi * 19.7;

        float dir = mix(-1.0, 1.0, step(0.5, hash11(seed + 1.3)));
        float spd = mix(0.012, 0.050, hash11(seed + 2.7)) * dir * driftBoost;

        float basePosX = hash11(seed + 10.1) * perimCells;
        float basePosY = hash11(seed + 11.9) * 80.0;

        float posX = pmod(basePosX + tScan * spd * 80.0, perimCells);
        float posY = pmod(basePosY + tScan * spd * 80.0, 80.0);

        float d0, d1;
        if (isH > 0.5) {
            float yL = pmod(gScan.y, 80.0);
            d0 = abs(yL - posY);
            d1 = abs(yL - pmod(posY + 1.0, 80.0));
        } else {
            d0 = ringDist(gScan.x, posX, perimCells);
            d1 = ringDist(gScan.x, pmod(posX + 1.0, perimCells), perimCells);
        }

        float a0 = lineA(d0, lineHalfW, aa);
        float a1 = lineA(d1, lineHalfW, aa);

        float jitter = 0.70 + 0.30 * sin(tScan * (0.06 + 0.03 * hash11(seed + 5.1)) + seed);

        // BRIGHTER: was ~0.10..0.32-ish, now ~0.22..0.77-ish
        float strength = (0.22 + 0.55 * effect) * jitter;

        scanLinesA = max(scanLinesA, max(a0, a1) * strength);
    }

    // Broad sweep band (also a bit stronger)
    float heightPx = 20.0;
    float y = pmod(gRenderAA.y, heightPx);

    float scanSpeed = 0.05;
    float scanPos = pmod(tScan * scanSpeed, heightPx);
    float scanDist = ringDist(y, scanPos, heightPx);
    float scanBandsA = (1.0 - smoothstep(0.1, 0.25, scanDist)) * 0.70;

    // ============================================================
    // Multiple cursors
    // ============================================================
    const float CURSOR_SLOW = 2.4;

    const int MAX_CURSORS = 4;
    float cursorCountF = clamp(floor(1.0 + effect * 3.25), 1.0, float(MAX_CURSORS));

    float boxFillMax    = 0.0;
    float boxOutlineMax = 0.0;
    float crossMax      = 0.0;
    float holeMaskMax   = 0.0;

    vec2 gCursor = vec2(gxLoop, gRender.y);

    for (int ci = 0; ci < MAX_CURSORS; ci++) {
        float fci = float(ci);
        float enabled = step(fci, cursorCountF - 1.0);
        if (enabled <= 0.0) continue;

        float seed = 200.0 + fci * 31.7;

        float cursorRate = mix(38.0, 62.0, hash11(seed + 0.1)) * CURSOR_SLOW;
        float tt = (tTick / CURSOR_SLOW) + hash11(seed + 9.7) * 1000.0;

        float stepT = floor(tt / cursorRate);
        float fracT = fract(tt / cursorRate);
        fracT = fracT * fracT * (3.0 - 2.0 * fracT);

        vec2 p0 = vec2(hash12(vec2(stepT - 1.0, seed + 1.23)),
                       hash12(vec2(stepT - 1.0, seed + 4.56)));
        vec2 p1 = vec2(hash12(vec2(stepT + 0.0, seed + 1.23)),
                       hash12(vec2(stepT + 0.0, seed + 4.56)));

        float ySpan = 70.0;
        vec2 target  = vec2(p0.x * perimCells, p0.y * ySpan);
        vec2 target2 = vec2(p1.x * perimCells, p1.y * ySpan);

        vec2 curCell = floor(mix(target, target2, fracT));
        curCell.x = pmod(curCell.x, perimCells);

        float sizeStep = floor((tt + seed * 3.1) / 60.0);
        float selSize  = 1.0 + floor(hash11(sizeStep * 1.37 + seed) * 3.0);

        float inX = step(curCell.x, cellId.x) * step(cellId.x, curCell.x + selSize - 0.5);
        float inY = step(curCell.y, cellId.y) * step(cellId.y, curCell.y + selSize - 0.5);
        float inRect = inX * inY;

        vec2 lp = gCursor - curCell;
        float dEdge = min(min(lp.x, selSize - lp.x), min(lp.y, selSize - lp.y));
        float th = 0.12;
        float outline = (1.0 - smoothstep(th, th + aa, dEdge)) * inRect;

        float vx0 = curCell.x;
        float vx1 = curCell.x + selSize;
        float hy0 = curCell.y;
        float hy1 = curCell.y + selSize;

        float dx0 = ringDist(gCursor.x, pmod(vx0, perimCells), perimCells);
        float dx1 = ringDist(gCursor.x, pmod(vx1, perimCells), perimCells);
        float dy0 = abs(gCursor.y - hy0);
        float dy1 = abs(gCursor.y - hy1);

        float crossW = 0.085;
        float vA = max(lineA(dx0, crossW, aa), lineA(dx1, crossW, aa));
        float hA = max(lineA(dy0, crossW, aa), lineA(dy1, crossW, aa));
        float crossA = max(vA, hA);

        float nearBlock = 1.0 - smoothstep(0.0, 15.0, min(min(dx0, dx1), min(dy0, dy1)));
        crossA *= (0.55 + 0.45 * nearBlock);

        boxFillMax    = max(boxFillMax, inRect);
        boxOutlineMax = max(boxOutlineMax, outline);
        crossMax      = max(crossMax, crossA);
        holeMaskMax   = max(holeMaskMax, inRect);
    }

    // ============================================================
    // Cursor should also highlight NON-visible glyphs:
    // Always compute a cursor glyph core for the cell (same glyph index),
    // but only pay the cost when inside any cursor.
    // ============================================================
    float cursorGlyphCore = 0.0;
    if (holeMaskMax > 0.0) {
        cursorGlyphCore = sampleGlyph(cellUV, glyphIndexCell, 0.10, 0.65, 0.92);
    }

    // Visible glyphs outside cursor
    float glyphA_outside = glyphA * (1.0 - holeMaskMax);

    // Holes inside cursor: use cursorGlyphCore so even "empty" cells get a glyph silhouette
    float hole = cursorGlyphCore * holeMaskMax;
    float holeStrength = (0.80 + 0.25 * effect);

    // ============================================================
    // Compose
    // ============================================================
    vec3 baseCyan = vec3(0.55, 1.00, 0.90);
    vec3 redTint  = vec3(1.00, 0.25, 0.35);
    vec3 glyphCol = mix(baseCyan, redTint, selected);

    // BRIGHTER scan lines contribution
    float scanBandsFinal = scanBandsA * baseOpacity * (0.20 + 0.55 * effect);
    float scanLinesFinal = scanLinesA * baseOpacity * (0.55 + 1.05 * effect);

    float boxFillA       = boxFillMax    * baseOpacity * (0.16 + 0.45 * effect);
    float boxOutlineA    = boxOutlineMax * baseOpacity * (0.28 + 0.68 * effect);
    float crossFinalA    = crossMax      * baseOpacity * (0.14 + 0.34 * effect);

    float outA = 0.0;
    vec3 outRGB = vec3(0.0);

    outRGB += baseCyan * scanBandsFinal;  outA = max(outA, scanBandsFinal);
    outRGB += baseCyan * scanLinesFinal;  outA = max(outA, scanLinesFinal);

    outRGB += baseCyan * crossFinalA;     outA = max(outA, crossFinalA);

    outRGB += glyphCol  * glyphA_outside; outA = max(outA, glyphA_outside);

    outRGB += baseCyan * boxFillA;        outA = max(outA, boxFillA);
    outRGB += baseCyan * boxOutlineA;     outA = max(outA, boxOutlineA);

    if (outA <= 0.001) discard;

    outRGB = outRGB / max(outA, 1e-6);

    // Punch glyph holes inside cursor fill (now includes "non-visible" glyphs)
    float holeA = hole * holeStrength;
    outRGB *= (1.0 - holeA);

    float glow = smoothstep(0.25, 0.95, outA);
    outRGB = mix(outRGB, vec3(1.0), 0.10 * glow);

    fragColor = vec4(outRGB * vColor.rgb, outA);
}
