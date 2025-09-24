// dev/fouriis/karmagate/client/hologram/HoloFrameIndex.java
package dev.fouriis.karmagate.hologram;

import com.google.gson.*;
import net.minecraft.util.Identifier;

import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

public final class HoloFrameIndex {
    public static final class Frame {
        public final float u0, v0, u1, v1;
        /** Trimmed frame width/height (actual sub-rectangle). */
        public final int w, h;
        /** Original untrimmed source dimensions (sourceSize in JSON). */
        public final int sourceW, sourceH;
        /** Offset of trimmed rect inside original (spriteSourceSize.x/y). */
        public final int offX, offY;
        public Frame(float u0, float v0, float u1, float v1,
                     int w, int h,
                     int sourceW, int sourceH,
                     int offX, int offY) {
            this.u0=u0; this.v0=v0; this.u1=u1; this.v1=v1;
            this.w=w; this.h=h; this.sourceW=sourceW; this.sourceH=sourceH;
            this.offX=offX; this.offY=offY;
        }
    }

    private final Map<String, Frame> map = new HashMap<>();
    public Frame get(String name) { return map.get(name); }

    public static HoloFrameIndex load(String sheetPngPath, String framesJsonPath) {
        // We need the sheet size to normalize UVs
        // Easiest: hardcode once, or store alongside your json.
        // If you know your sheet size, fill these:
        final int sheetW = 520; // <-- put your sheet width here
        final int sheetH = 510; // <-- put your sheet height here

        Identifier jsonId = Identifier.of(framesJsonPath);
        try (var is = HoloFrameIndex.class.getClassLoader().getResourceAsStream("assets/" + jsonId.getNamespace() + "/" + jsonId.getPath())) {
            if (is == null) throw new RuntimeException("Missing frames JSON: " + framesJsonPath);
            JsonObject root = JsonParser.parseReader(new InputStreamReader(is)).getAsJsonObject();
            JsonObject frames = root.getAsJsonObject("frames");

            HoloFrameIndex idx = new HoloFrameIndex();
            for (var e : frames.entrySet()) {
                String key = e.getKey();             // e.g. "gateSymbol1.png"
                JsonObject f = e.getValue().getAsJsonObject();
                JsonObject fr = f.getAsJsonObject("frame");
                int x = fr.get("x").getAsInt();
                int y = fr.get("y").getAsInt();
                int w = fr.get("w").getAsInt();
                int h = fr.get("h").getAsInt();

                // Optional extra metadata for consistent scaling
                int sourceW = w, sourceH = h, offX = 0, offY = 0;
                if (f.has("sourceSize")) {
                    JsonObject ss = f.getAsJsonObject("sourceSize");
                    if (ss.has("w")) sourceW = ss.get("w").getAsInt();
                    if (ss.has("h")) sourceH = ss.get("h").getAsInt();
                }
                if (f.has("spriteSourceSize")) {
                    JsonObject sss = f.getAsJsonObject("spriteSourceSize");
                    if (sss.has("x")) offX = sss.get("x").getAsInt();
                    if (sss.has("y")) offY = sss.get("y").getAsInt();
                }
                float u0 = (float)x / sheetW;
                float v0 = (float)y / sheetH;
                float u1 = (float)(x + w) / sheetW;
                float v1 = (float)(y + h) / sheetH;
                idx.map.put(key, new Frame(u0, v0, u1, v1, w, h, sourceW, sourceH, offX, offY));
            }
            return idx;
        } catch (Exception ex) {
            throw new RuntimeException("Failed loading holo frames", ex);
        }
    }
}
