package com.example.iconmod;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;

/** Shared drawing logic: used by the hook in the launcher AND by the app preview (same code = faithful preview). */
class IconRender {

    /** Clips the bitmap to a rounded rectangle (BitmapShader: real clip, antialiased). */
    static Bitmap mask(Bitmap content, int pct) {
        int w = content.getWidth(), h = content.getHeight();
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        p.setShader(new BitmapShader(content, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        float r = Math.min(w, h) * pct / 100f;
        new Canvas(out).drawRoundRect(new RectF(0, 0, w, h), r, r, p);
        return out;
    }

    /** Zooms the original icon (fills the transparent corners of the Samsung shape) and clips to the chosen radius. */
    static Bitmap reshape(Bitmap old, int zoomPct, int pct) {
        int s = old.getWidth();
        Bitmap content = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(content);
        float z = zoomPct / 100f;
        c.scale(z, z, s / 2f, s / 2f);
        c.drawBitmap(old, 0, 0, new Paint(Paint.FILTER_BITMAP_FLAG));
        return mask(content, pct);
    }

    static Bitmap render(Drawable src, int s, boolean clip, int pct) {
        Bitmap bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        if (src instanceof AdaptiveIconDrawable) {   // draw without the system mask
            AdaptiveIconDrawable ad = (AdaptiveIconDrawable) src;
            int i = s / 4;
            if (ad.getBackground() != null) { ad.getBackground().setBounds(-i, -i, s + i, s + i); ad.getBackground().draw(c); }
            if (ad.getForeground() != null) { ad.getForeground().setBounds(-i, -i, s + i, s + i); ad.getForeground().draw(c); }
        } else {
            src.setBounds(0, 0, s, s);
            src.draw(c);
        }
        return clip ? mask(bmp, pct) : bmp;
    }

    static Bitmap applyShape(Bitmap src, Bitmap shape, int zoomPct) {
        int w = src.getWidth(), h = src.getHeight();
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        float z = zoomPct / 100f;
        c.save(); c.scale(z, z, w / 2f, h / 2f);
        c.drawBitmap(src, 0, 0, new Paint(Paint.FILTER_BITMAP_FLAG));
        c.restore();
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));   // the shape covers the whole canvas, so the clip is total
        c.drawBitmap(shape, 0, 0, p);
        return out;
    }

    /** Icon size: shrinks/enlarges the final result around the center (transparent margin when < 100). */
    static Bitmap applySize(Bitmap b, int pct) {
        if (pct == 100) return b;
        int w = b.getWidth(), h = b.getHeight();
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        float z = pct / 100f;
        c.scale(z, z, w / 2f, h / 2f);
        c.drawBitmap(b, 0, 0, new Paint(Paint.FILTER_BITMAP_FLAG));
        return out;
    }

    private static Bitmap layer(Drawable d, int s) {
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        int i = s / 4;
        d.setBounds(-i, -i, s + i, s + i);
        d.draw(new Canvas(b));
        return b;
    }

    private static int avgColor(Bitmap b) {
        Bitmap one = Bitmap.createScaledBitmap(b, 1, 1, true);
        return one.getPixel(0, 0);
    }

    /** Lens: magnifies the center and keeps the edge fixed (monotonic radial mesh), giving a refraction feel. */
    static Bitmap warp(Bitmap src, float k) {
        if (k <= 0.001f) return src;
        int n = 28, w = src.getWidth(), h = src.getHeight();
        float[] v = new float[(n + 1) * (n + 1) * 2];
        int i = 0;
        for (int y = 0; y <= n; y++) {
            for (int x = 0; x <= n; x++) {
                float u = x / (float) n * 2 - 1, t = y / (float) n * 2 - 1;
                double r = Math.pow(Math.pow(Math.abs(u), 4) + Math.pow(Math.abs(t), 4), 0.25);
                float g = r >= 1.0 ? 1f : (float) (1 + k * (1 - r * r));
                v[i++] = w / 2f + u * w / 2f * g;
                v[i++] = h / 2f + t * h / 2f * g;
            }
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        new Canvas(out).drawBitmapMesh(src, n, n, v, 0, null, 0, new Paint(Paint.FILTER_BITMAP_FLAG));
        return out;
    }

    private static Paint ring(float width, float blur) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(width);
        if (blur > 0) p.setMaskFilter(new BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL));
        return p;
    }

    private static void roundRing(Canvas c, Paint p, float inset, float s, float rad) {
        c.drawRoundRect(new RectF(inset, inset, s - inset, s - inset), Math.max(0, rad - inset), Math.max(0, rad - inset), p);
    }

    /**
     * "Glass": translucent body (app background color + frosted veil), a lens that magnifies the glyph in the center,
     * light bevel with reflection, top glow and a corner caustic. Static approximation: no blur/refraction
     * of the live wallpaper (that would require sampling the wallpaper per view, as CherryPick does).
     */
    static Bitmap glass(Drawable src, int s, IconOpts o) {
        float edge = o.glassEdge / 100f, plate = o.glassPlate / 100f, bevel = o.glassBevel / 100f,
                lens = o.glassLens / 100f, frost = o.glassFrost / 100f;
        Drawable bg = null, fg = src;
        boolean adaptive = src instanceof AdaptiveIconDrawable, tint = false;
        if (adaptive) {
            AdaptiveIconDrawable ad = (AdaptiveIconDrawable) src;
            bg = ad.getBackground();
            fg = ad.getForeground();
            if (o.glassMono && Build.VERSION.SDK_INT >= 33 && ad.getMonochrome() != null) { fg = ad.getMonochrome(); tint = true; }
        }
        Bitmap glyph = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas gc = new Canvas(glyph);
        float gs = o.glassGlyph / 100f;   // glyph size slider: scales the glyph layer around the center
        if (adaptive) { int h = Math.round(s * 0.75f * gs), c0 = s / 2; fg.setBounds(c0 - h, c0 - h, c0 + h, c0 + h); }
        else { int h = Math.round(s * 0.38f * gs), c0 = s / 2; fg.setBounds(c0 - h, c0 - h, c0 + h, c0 + h); }
        if (tint) fg.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
        fg.draw(gc);
        fg.setColorFilter(null);

        // ---- body
        Bitmap bgB = bg != null ? layer(bg, s) : null;
        int avg = avgColor(bgB != null ? bgB : glyph);
        Bitmap body = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas bc = new Canvas(body);
        if (bgB != null) {
            Paint pp = new Paint(Paint.FILTER_BITMAP_FLAG);
            pp.setAlpha((int) (255 * plate));
            bc.drawBitmap(bgB, 0, 0, pp);
        } else {
            bc.drawColor(Color.argb((int) (255 * plate * 0.8f), Color.red(avg), Color.green(avg), Color.blue(avg)));
        }
        bc.drawColor(Color.argb((int) (255 * (0.05f + 0.22f * frost)), 255, 255, 255));          // frosted veil
        Paint vol = new Paint();                                                                   // volume: light top, dark base
        vol.setShader(new LinearGradient(0, 0, 0, s, new int[]{Color.argb(80, 255, 255, 255),
                Color.argb(0, 255, 255, 255), Color.argb(70, 0, 0, 0)}, new float[]{0f, .5f, 1f}, Shader.TileMode.CLAMP));
        bc.drawRect(0, 0, s, s, vol);
        Paint dome = new Paint(Paint.ANTI_ALIAS_FLAG);                                             // dome
        dome.setShader(new RadialGradient(s * 0.32f, s * 0.22f, s * 0.8f,
                new int[]{Color.argb((int) (140 * edge), 255, 255, 255), Color.argb(0, 255, 255, 255)}, null, Shader.TileMode.CLAMP));
        bc.drawRect(0, 0, s, s, dome);
        Paint caustic = new Paint(Paint.ANTI_ALIAS_FLAG);                                          // bottom-right caustic
        caustic.setShader(new RadialGradient(s * 0.78f, s * 0.95f, s * 0.42f,
                new int[]{Color.argb((int) (120 * edge), 255, 255, 255), Color.argb(0, 255, 255, 255)}, null, Shader.TileMode.CLAMP));
        bc.drawRect(0, 0, s, s, caustic);

        // ---- body + glyph shadow + glyph, with lens
        Bitmap comp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(comp);
        c.drawBitmap(body, 0, 0, null);
        Paint sp = new Paint(Paint.ANTI_ALIAS_FLAG);
        sp.setMaskFilter(new BlurMaskFilter(Math.max(1f, s * 0.022f), BlurMaskFilter.Blur.NORMAL));
        int[] off = new int[2];
        Bitmap shadow = glyph.extractAlpha(sp, off);
        Paint shp = new Paint();
        shp.setColor(Color.argb(95, 0, 0, 0));
        c.drawBitmap(shadow, off[0], off[1] + s * 0.02f, shp);
        c.drawBitmap(glyph, 0, 0, null);
        Bitmap out = warp(comp, 0.30f * lens);
        Canvas oc = new Canvas(out);

        // ---- edge light (not affected by the lens)
        float rad = s * o.radius / 100f, bw = s * (0.025f + 0.07f * bevel), sw = s * 0.012f;
        int white = 255;
        Paint shade = ring(bw * 0.8f, bw * 0.6f);                                                  // inner shadow (depth)
        shade.setShader(new LinearGradient(0, 0, s, s, new int[]{Color.argb(0, 0, 0, 0), Color.argb((int) (120 * edge), 0, 0, 0)},
                null, Shader.TileMode.CLAMP));
        roundRing(oc, shade, bw * 1.15f, s, rad);
        Paint bev = ring(bw, bw * 0.45f);                                                          // bevel (Fresnel light)
        bev.setShader(new LinearGradient(0, 0, s, s, new int[]{Color.argb((int) (230 * edge), white, white, white),
                Color.argb((int) (40 * edge), white, white, white), Color.argb((int) (120 * edge), white, white, white)},
                new float[]{0f, .5f, 1f}, Shader.TileMode.CLAMP));
        roundRing(oc, bev, bw / 2, s, rad);
        Paint gloss = new Paint(Paint.ANTI_ALIAS_FLAG);                                            // top gloss
        gloss.setShader(new LinearGradient(0, 0, 0, s * 0.5f, Color.argb((int) (120 * edge), white, white, white),
                Color.argb(0, white, white, white), Shader.TileMode.CLAMP));
        float gi = bw * 0.9f;
        oc.drawRoundRect(new RectF(gi, gi, s - gi, s * 0.5f), Math.max(0, rad - gi), Math.max(0, rad - gi), gloss);
        Paint edgeLine = ring(sw, 0);                                                              // crisp edge line
        edgeLine.setShader(new LinearGradient(0, 0, s, s, new int[]{Color.argb((int) (255 * edge), white, white, white),
                Color.argb(0, white, white, white), Color.argb(0, 0, 0, 0), Color.argb((int) (170 * edge), 0, 0, 0)},
                new float[]{0f, .4f, .6f, 1f}, Shader.TileMode.CLAMP));
        roundRing(oc, edgeLine, sw / 2, s, rad);
        return mask(out, o.radius);
    }

    /** Single decision point for how the final icon is made (hook and preview share this code). null = leave untouched. */
    static Bitmap make(Context ctx, ComponentName cn, Bitmap old, IconPack pack, boolean hasPack, IconOpts o) {
        return make(ctx, cn, old, pack, hasPack, o, null);
    }

    /** appIcon: already-loaded app icon (the preview reuses it; the hook passes null and loads it here). */
    static Bitmap make(Context ctx, ComponentName cn, Bitmap old, IconPack pack, boolean hasPack, IconOpts o, Drawable appIcon) {
        Drawable src = null;
        if (hasPack && pack != null) src = pack.get(cn);
        if (src == null && (o.glass || (o.corners && o.regen))) {   // glass and "regenerate" start from the original app icon
            src = appIcon;
            if (src == null) {
                try { src = ctx.getPackageManager().getActivityIcon(cn); } catch (Throwable ignored) {}
            }
        }
        int w = old.getWidth();
        boolean fade = !o.glass && o.iconAlpha < 100;      // translucent icon so the wallpaper blur shows through
        boolean glassUsed = o.glass && src != null;
        Bitmap b;
        if (glassUsed) b = glass(src, w, o);
        else if (src != null) b = render(src, w, o.corners, o.radius);
        else if (o.corners) b = reshape(old, o.zoom, o.radius);
        else if (o.size != 100 || fade) b = old;
        else return null;
        b = applySize(b, o.size);
        return fade && !glassUsed ? applyAlpha(b, o.iconAlpha) : b;
    }

    /** Whole-icon opacity (used when the blur is on but liquid glass is off). */
    static Bitmap applyAlpha(Bitmap b, int pct) {
        Bitmap out = Bitmap.createBitmap(b.getWidth(), b.getHeight(), Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        p.setAlpha(Math.round(255 * pct / 100f));
        new Canvas(out).drawBitmap(b, 0, 0, p);
        return out;
    }
}
