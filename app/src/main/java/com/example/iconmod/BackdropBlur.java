package com.example.iconmod;

import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.WindowManager;

/** Blurred wallpaper (box blur, low resolution) used as the glass background. */
class BackdropBlur {
    /** Downscale factor: the blurred bitmap is 1/DS of the screen size. */
    static final int DS = 3;

    static int radiusFor(int pct) { return 1 + Math.round(pct * 0.24f); }

    private static void pass(int[] src, int[] dst, int w, int h, int r, boolean horiz) {
        int len = horiz ? w : h, lines = horiz ? h : w, div = 2 * r + 1;
        int stride = horiz ? 1 : w;
        for (int l = 0; l < lines; l++) {
            int base = horiz ? l * w : l;
            int sr = 0, sg = 0, sb = 0;
            for (int k = -r; k <= r; k++) {
                int p = src[base + Math.min(Math.max(k, 0), len - 1) * stride];
                sr += (p >> 16) & 255; sg += (p >> 8) & 255; sb += p & 255;
            }
            for (int i = 0; i < len; i++) {
                dst[base + i * stride] = 0xFF000000 | ((sr / div) << 16) | ((sg / div) << 8) | (sb / div);
                int add = src[base + Math.min(i + r + 1, len - 1) * stride];
                int sub = src[base + Math.max(i - r, 0) * stride];
                sr += ((add >> 16) & 255) - ((sub >> 16) & 255);
                sg += ((add >> 8) & 255) - ((sub >> 8) & 255);
                sb += (add & 255) - (sub & 255);
            }
        }
    }

    /** 3 box-average passes in both directions ≈ gaussian blur. */
    static Bitmap blur(Bitmap src, int r) {
        int w = src.getWidth(), h = src.getHeight();
        int[] a = new int[w * h], b = new int[w * h];
        src.getPixels(a, 0, w, 0, 0, w, h);
        for (int i = 0; i < a.length; i++) a[i] |= 0xFF000000;
        if (r >= 1) {
            for (int k = 0; k < 3; k++) { pass(a, b, w, h, r, true); pass(b, a, w, h, r, false); }
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(a, 0, w, 0, 0, w, h);
        return out;
    }

    /** Reads the home screen wallpaper (in the launcher, which has permission for it). */
    static Bitmap loadWallpaper(Context ctx) {
        try {
            WallpaperManager wm = WallpaperManager.getInstance(ctx);
            Drawable d = wm.getDrawable();
            if (d == null) d = wm.peekDrawable();
            if (d == null) return null;
            int w = d.getIntrinsicWidth(), h = d.getIntrinsicHeight();
            if (w <= 0 || h <= 0) return null;
            float sc = Math.min(1f, 900f / Math.max(w, h));
            int bw = Math.max(1, Math.round(w * sc)), bh = Math.max(1, Math.round(h * sc));
            Bitmap b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, bw, bh);
            d.draw(new Canvas(b));
            return b;
        } catch (Throwable t) {
            IconPack.log("IconMod: could not read the wallpaper: " + t);
            return null;
        }
    }

    static int[] screenSize(Context ctx) {
        try {
            Rect r = ctx.getSystemService(WindowManager.class).getMaximumWindowMetrics().getBounds();
            return new int[]{r.width(), r.height()};
        } catch (Throwable t) {
            return new int[]{ctx.getResources().getDisplayMetrics().widthPixels, ctx.getResources().getDisplayMetrics().heightPixels};
        }
    }

    /** Screen-sized / DS bitmap: the wallpaper (center-crop, like the home screen) already blurred. */
    static Bitmap build(Context ctx, int blurPct) {
        Bitmap wp = loadWallpaper(ctx);
        if (wp == null) return null;
        int[] sz = screenSize(ctx);
        int bw = Math.max(1, sz[0] / DS), bh = Math.max(1, sz[1] / DS);
        Bitmap scr = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        float sc = Math.max(bw / (float) wp.getWidth(), bh / (float) wp.getHeight());
        Matrix m = new Matrix();
        m.postScale(sc, sc);
        m.postTranslate((bw - wp.getWidth() * sc) / 2f, (bh - wp.getHeight() * sc) / 2f);
        new Canvas(scr).drawBitmap(wp, m, new Paint(Paint.FILTER_BITMAP_FLAG));
        return blur(scr, radiusFor(blurPct));
    }

    private static Paint glassPaint(BitmapShader sh) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        p.setShader(sh);
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(1.3f);                       // glass boosts the background colors
        p.setColorFilter(new ColorMatrixColorFilter(cm));
        return p;
    }

    // reused objects: draw() only runs on the launcher's main thread (onDraw), so no synchronization is needed
    private static final Paint DRAW_PAINT = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private static final Matrix DRAW_MAT = new Matrix();
    private static final RectF DRAW_DST = new RectF();
    private static Bitmap shaderBmp;
    private static BitmapShader shader;
    static {
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(1.3f);                       // glass boosts the background colors
        DRAW_PAINT.setColorFilter(new ColorMatrixColorFilter(cm));
    }

    private static final Matrix INV = new Matrix();

    /** AGSL path (Android 13+). Isolated in a class so older devices never load RuntimeShader. */
    @android.annotation.TargetApi(33)
    private static final class Agsl {
        static RuntimeShader rs;
        static boolean broken;

        static boolean apply(Paint p, BitmapShader bs, RectF dst, float radius, IconOpts o, float lx, float ly) {
            if (broken) return false;
            try {
                if (rs == null) rs = new RuntimeShader(BackdropFx.AGSL);
                rs.setInputShader("backdrop", bs);
                rs.setFloatUniform("uCenter", dst.centerX(), dst.centerY());
                rs.setFloatUniform("uHalf", dst.width() / 2f, dst.height() / 2f);
                rs.setFloatUniform("uRadius", radius);
                rs.setFloatUniform("uW", Math.min(dst.width(), dst.height()) * 0.22f);
                rs.setFloatUniform("uRefr", o.blurRefraction / 100f * 0.5f);
                rs.setFloatUniform("uAb", o.blurAberration / 100f);
                rs.setFloatUniform("uFrost", o.blurFrost / 100f);
                int tc = o.tintColor();
                rs.setFloatUniform("uTint", Color.red(tc) / 255f, Color.green(tc) / 255f, Color.blue(tc) / 255f);
                rs.setFloatUniform("uTintAmt", o.tintAmt / 100f);
                rs.setFloatUniform("uSpec", o.blurSpecular ? 0.8f : 0f);
                rs.setFloatUniform("uLight", lx, ly);
                p.setShader(rs);
                return true;
            } catch (Throwable t) {
                broken = true;
                IconPack.log("IconMod: AGSL shader failed, using the plain blur: " + t);
                return false;
            }
        }
    }

    /**
     * Draws the blurred wallpaper in the icon's shape, in the canvas' current space, with the glass effects.
     * toScreen = the view's full local->screen matrix (includes the scale/translation of the page, the open animation, etc.).
     * The blurred bitmap lives in screen px / DS, so its shader matrix is: scale(DS) then screen->local (the inverse).
     * iconRect = rectangle where the icon bitmap is actually drawn (same canvas space).
     */
    static void draw(Canvas c, Bitmap blurred, Matrix toScreen, RectF iconRect, IconOpts o, float sizePct, boolean behind) {
        float w = iconRect.width(), h = iconRect.height();
        if (w <= 0 || h <= 0 || !toScreen.invert(INV)) return;
        float k = sizePct / 100f, cx = iconRect.centerX(), cy = iconRect.centerY();
        DRAW_DST.set(cx - w * k / 2, cy - h * k / 2, cx + w * k / 2, cy + h * k / 2);
        if (shader == null || shaderBmp != blurred) {
            shader = new BitmapShader(blurred, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            shaderBmp = blurred;
        }
        DRAW_MAT.setScale(DS, DS);
        DRAW_MAT.postConcat(INV);
        shader.setLocalMatrix(DRAW_MAT);
        DRAW_PAINT.setXfermode(behind ? DST_OVER : null);
        float r = Math.min(DRAW_DST.width(), DRAW_DST.height()) * o.radius / 100f;
        boolean fx = false;
        if (o.fxActive() && android.os.Build.VERSION.SDK_INT >= 33) {
            float[] pt = {cx, cy};
            toScreen.mapPoints(pt);       // icon center on screen -> light direction (the highlight tracks the light)
            float[] L = BackdropFx.light(pt[0], pt[1], blurred.getWidth() * DS, blurred.getHeight() * DS);
            fx = Agsl.apply(DRAW_PAINT, shader, DRAW_DST, r, o, L[0], L[1]);
        }
        if (!fx) DRAW_PAINT.setShader(shader);
        c.drawRoundRect(DRAW_DST, r, r, DRAW_PAINT);
    }
    private static final PorterDuffXfermode DST_OVER = new PorterDuffXfermode(PorterDuff.Mode.DST_OVER);

    /** App preview version: blurred background already clipped to the icon shape, S x S. */
    static Bitmap shaped(Bitmap blurredFull, int sizePct, int radiusPct) {
        int s = blurredFull.getWidth();
        Bitmap out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        float k = sizePct / 100f, o = s * (1 - k) / 2f;
        RectF dst = new RectF(o, o, s - o, s - o);
        BitmapShader sh = new BitmapShader(blurredFull, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        float r = dst.width() * radiusPct / 100f;
        new Canvas(out).drawRoundRect(dst, r, r, glassPaint(sh));
        return out;
    }
}
