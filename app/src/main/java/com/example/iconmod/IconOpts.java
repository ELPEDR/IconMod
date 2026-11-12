package com.example.iconmod;

import android.content.SharedPreferences;
import android.graphics.Color;

/** All appearance options. Used identically by the hook (XSharedPreferences) and the app preview. */
class IconOpts {
    boolean corners = true, regen = false, glass = false, glassMono = false, blur = false;
    int radius = 30, zoom = 115, size = 100;
    int glassPlate = 22;   // opacity of the background plate (0 = fully transparent)
    int glassEdge = 80;    // light intensity (bevel, glow, reflection)
    int blurAmount = 50;   // blur of the wallpaper behind the glass
    int glassBevel = 55;   // bevel thickness
    int glassLens = 35;    // refraction: magnifies the center and compresses the edges
    int glassFrost = 35;   // white frosted veil on the body
    int glassGlyph = 100;  // glyph size inside the liquid glass plate (100 = default)
    int iconAlpha = 100;   // whole-icon opacity when liquid glass is off (lets the blur show through)
    int blurRefraction = 40;   // backdrop lens: how much the light bends at the rim
    int blurAberration = 20;   // colour split at the lens edge
    int blurFrost = 20;        // diffuses the scene behind the glass
    int tintHue = 210, tintAmt = 0;
    int tintSource = 0;                // 0 = custom hue, 1/2/3 = Material You primary/secondary/tertiary
    int materialTint;                  // resolved system colour
    boolean materialReady;   // tint colour (hue 0-360) and strength
    boolean blurSpecular = true;      // specular highlight that follows the light as the icon moves
    int blurFit = 100;     // fine-tune of the blur plate size in the launcher (100 = same as the icon)

    /** Resolves the Material You colour (system_accent1/2/3). Call with any Context; no-op below Android 12. */
    void resolveTint(android.content.Context c) {
        if (tintSource == 0 || materialReady || android.os.Build.VERSION.SDK_INT < 31) return;
        try {
            int[] ids = {android.R.color.system_accent1_300, android.R.color.system_accent2_300, android.R.color.system_accent3_300};
            materialTint = c.getColor(ids[tintSource - 1]);
            materialReady = true;
        } catch (Throwable ignored) {}
    }

    int tintColor() {
        if (tintSource > 0 && materialReady) return materialTint;
        return Color.HSVToColor(new float[]{tintHue, 0.7f, 1f});
    }

    boolean fxActive() { return blurRefraction > 0 || blurAberration > 0 || blurFrost > 0 || tintAmt > 0 || blurSpecular; }

    static IconOpts fromPrefs(SharedPreferences p) {
        IconOpts o = new IconOpts();
        o.corners = p.getBoolean("corners", true);
        o.regen = p.getBoolean("regen", false);
        o.radius = p.getInt("radius", 30);
        o.zoom = p.getInt("zoom", 115);
        o.size = p.getInt("size", 100);
        o.glass = p.getBoolean("glass", false);
        o.glassMono = p.getBoolean("glassMono", false);
        o.glassPlate = p.getInt("glassPlate", 22);
        o.glassEdge = p.getInt("glassEdge", 80);
        o.blur = p.getBoolean("blur", false);
        o.blurAmount = p.getInt("blurAmount", 50);
        o.glassBevel = p.getInt("glassBevel", 55);
        o.glassLens = p.getInt("glassLens", 35);
        o.glassFrost = p.getInt("glassFrost", 35);
        o.blurFit = p.getInt("blurFit", 100);
        o.glassGlyph = p.getInt("glassGlyph", 100);
        o.blurRefraction = p.getInt("blurRefraction", 40);
        o.blurAberration = p.getInt("blurAberration", 20);
        o.blurFrost = p.getInt("blurFrost", 20);
        o.tintHue = p.getInt("tintHue", 210);
        o.tintAmt = p.getInt("tintAmt", 0);
        o.tintSource = p.getInt("tintSource", 0);
        o.blurSpecular = p.getBoolean("blurSpecular", true);
        o.iconAlpha = p.getInt("iconAlpha", 100);
        return o;
    }

    @Override public String toString() {
        return "corners=" + corners + " radius=" + radius + " zoom=" + zoom + " size=" + size
                + " regen=" + regen + " glass=" + glass + " plate=" + glassPlate + " edge=" + glassEdge
                + " blur=" + blur + "/" + blurAmount + " bevel=" + glassBevel + " lens=" + glassLens + " frost=" + glassFrost + " fit=" + blurFit + " refr=" + blurRefraction + " ab=" + blurAberration + " bfrost=" + blurFrost + " tint=" + tintHue + "/" + tintAmt + " spec=" + blurSpecular + " glyph=" + glassGlyph + " alpha=" + iconAlpha;
    }
}
