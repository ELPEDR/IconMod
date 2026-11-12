package com.example.iconmod;

import android.graphics.Bitmap;
import android.graphics.Color;

/**
 * Glass effects applied to the blurred wallpaper behind the icon: refraction (lens at the rim), chromatic aberration,
 * frost, tint and a specular highlight that follows a fixed light as the icon moves on screen.
 *
 * The SAME math exists twice: AGSL (RuntimeShader, Android 13+) for the live plate in the launcher, and this CPU version
 * for the app preview and for the opaque icon baked for the app open/close animation.
 */
class BackdropFx {

    /** AGSL version (BackdropBlur uses it with a RuntimeShader). Keep it in sync with render() below. */
    static final String AGSL =
            "uniform shader backdrop;\n"
          + "uniform float2 uCenter;\n"
          + "uniform float2 uHalf;\n"
          + "uniform float uRadius;\n"
          + "uniform float uW;\n"
          + "uniform float uRefr;\n"
          + "uniform float uAb;\n"
          + "uniform float uFrost;\n"
          + "uniform float3 uTint;\n"
          + "uniform float uTintAmt;\n"
          + "uniform float uSpec;\n"
          + "uniform float2 uLight;\n"
          + "half4 main(float2 frag) {\n"
          + "    float2 p = frag - uCenter;\n"
          + "    float2 q = abs(p) - (uHalf - float2(uRadius));\n"
          + "    float2 qq = max(q, float2(0.0));\n"
          + "    float ql = length(qq);\n"
          + "    float d = ql + min(max(q.x, q.y), 0.0) - uRadius;\n"
          + "    float2 g = ql > 0.0 ? qq / ql : (q.x > q.y ? float2(1.0, 0.0) : float2(0.0, 1.0));\n"
          + "    float2 n = g * float2(p.x < 0.0 ? -1.0 : 1.0, p.y < 0.0 ? -1.0 : 1.0);\n"
          + "    float e = 1.0 - clamp(-d / uW, 0.0, 1.0);\n"
          + "    float disp = uRefr * uW * e * e;\n"
          + "    float ab = uAb * 0.06 * uW * e * sqrt(e);\n"
          + "    float3 col;\n"
          + "    col.r = backdrop.eval(frag - n * (disp + ab)).r;\n"
          + "    col.g = backdrop.eval(frag - n * disp).g;\n"
          + "    col.b = backdrop.eval(frag - n * (disp - ab)).b;\n"
          + "    float lum = dot(col, float3(0.299, 0.587, 0.114));\n"
          + "    col = mix(col, float3(lum), 0.4 * uFrost);\n"
          + "    col = mix(col, float3(1.0), 0.14 * uFrost);\n"
          + "    col = mix(col, uTint, uTintAmt * 0.6);\n"
          + "    if (uSpec > 0.0) {\n"
          + "        float rim = pow(clamp(1.0 + d / (uW * 0.45), 0.0, 1.0), 2.0);\n"
          + "        float f1 = max(dot(n, uLight), 0.0);\n"
          + "        float f2 = max(dot(n, -uLight), 0.0);\n"
          + "        col += rim * (0.85 * pow(f1, 2.5) + 0.35 * pow(f2, 3.0)) * uSpec;\n"
          + "    }\n"
          + "    return half4(half3(clamp(col, float3(0.0), float3(1.0))), 1.0);\n"
          + "}\n";

    /** Unit vector (screen space) from the icon at (sx, sy) towards a virtual light above the top-left of the screen. */
    static float[] light(float sx, float sy, int sw, int sh) {
        float lx = 0.2f * sw - sx, ly = -0.15f * sh - sy;
        float l = (float) Math.hypot(lx, ly);
        if (l < 1e-3f) return new float[]{-0.7f, -0.7f};
        return new float[]{lx / l, ly / l};
    }

    private static float clamp01(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }
    private static int clampI(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }

    /** Bilinear sample (pixel-center convention) -> packed RGB. */
    private static int samp(int[] px, int s, float fx, float fy) {
        fx -= 0.5f; fy -= 0.5f;
        int x0 = (int) Math.floor(fx), y0 = (int) Math.floor(fy);
        float tx = fx - x0, ty = fy - y0;
        int x1 = clampI(x0 + 1, 0, s - 1), y1 = clampI(y0 + 1, 0, s - 1);
        x0 = clampI(x0, 0, s - 1); y0 = clampI(y0, 0, s - 1);
        int a = px[y0 * s + x0], b = px[y0 * s + x1], c = px[y1 * s + x0], d = px[y1 * s + x1];
        int out = 0;
        for (int sh = 16; sh >= 0; sh -= 8) {
            float top = ((a >> sh) & 255) * (1 - tx) + ((b >> sh) & 255) * tx;
            float bot = ((c >> sh) & 255) * (1 - tx) + ((d >> sh) & 255) * tx;
            out |= Math.round(top * (1 - ty) + bot * ty) << sh;
        }
        return out;
    }

    /**
     * CPU render: src = S x S blurred backdrop already cropped at the icon position. Returns the plate (anti-aliased
     * rounded rect, size = sizePct) with refraction, aberration, frost, tint, specular and a +30% saturation boost.
     */
    static Bitmap render(Bitmap src, IconOpts o, int sizePct, float lx, float ly) {
        return render(src, o, sizePct, o.radius, lx, ly);
    }

    /** Same as above with an explicit corner radius (%): 0 = full-bleed rectangle (the animation outline does the rounding). */
    static Bitmap render(Bitmap src, IconOpts o, int sizePct, int radiusPct, float lx, float ly) {
        int S = src.getWidth();
        int[] in = new int[S * S];
        src.getPixels(in, 0, S, 0, 0, S, S);
        int[] out = new int[S * S];
        float half = S * (sizePct / 100f) / 2f, cx = S / 2f, cy = S / 2f;
        float r = half * 2f * radiusPct / 100f, W = half * 2f * 0.22f;
        float refr = o.blurRefraction / 100f * 0.5f, ab = o.blurAberration / 100f, fr = o.blurFrost / 100f;
        int tc = o.tintColor();
        float tr = Color.red(tc) / 255f, tg = Color.green(tc) / 255f, tb = Color.blue(tc) / 255f, ta = o.tintAmt / 100f;
        float spec = o.blurSpecular ? 0.8f : 0f;
        for (int y = 0; y < S; y++) {
            for (int x = 0; x < S; x++) {
                float fx = x + 0.5f, fy = y + 0.5f, px = fx - cx, py = fy - cy;
                float qx = Math.abs(px) - (half - r), qy = Math.abs(py) - (half - r);
                float qqx = Math.max(qx, 0f), qqy = Math.max(qy, 0f);
                float ql = (float) Math.hypot(qqx, qqy);
                float d = ql + Math.min(Math.max(qx, qy), 0f) - r;
                float cov = clamp01(0.5f - d);
                if (cov <= 0f) continue;                    // outside the plate: stays transparent
                float gx, gy;
                if (ql > 0f) { gx = qqx / ql; gy = qqy / ql; } else if (qx > qy) { gx = 1f; gy = 0f; } else { gx = 0f; gy = 1f; }
                float nx = gx * (px < 0f ? -1f : 1f), ny = gy * (py < 0f ? -1f : 1f);
                float e = 1f - clamp01(-d / W);
                float disp = refr * W * e * e;
                float a = ab * 0.06f * W * e * (float) Math.sqrt(e);
                float R = ((samp(in, S, fx - nx * (disp + a), fy - ny * (disp + a)) >> 16) & 255) / 255f;
                float G = ((samp(in, S, fx - nx * disp, fy - ny * disp) >> 8) & 255) / 255f;
                float B = (samp(in, S, fx - nx * (disp - a), fy - ny * (disp - a)) & 255) / 255f;
                float lum = 0.299f * R + 0.587f * G + 0.114f * B;
                R += (lum - R) * 0.4f * fr; G += (lum - G) * 0.4f * fr; B += (lum - B) * 0.4f * fr;
                R += (1f - R) * 0.14f * fr; G += (1f - G) * 0.14f * fr; B += (1f - B) * 0.14f * fr;
                float t = ta * 0.6f;
                R += (tr - R) * t; G += (tg - G) * t; B += (tb - B) * t;
                if (spec > 0f) {
                    float rim = clamp01(1f + d / (W * 0.45f));
                    rim *= rim;
                    float f1 = Math.max(nx * lx + ny * ly, 0f), f2 = Math.max(-(nx * lx + ny * ly), 0f);
                    float sp = rim * (0.85f * (float) Math.pow(f1, 2.5) + 0.35f * (float) Math.pow(f2, 3.0)) * spec;
                    R += sp; G += sp; B += sp;
                }
                R = clamp01(R); G = clamp01(G); B = clamp01(B);
                float l2 = 0.299f * R + 0.587f * G + 0.114f * B;                       // +30% saturation (like the launcher paint)
                R = clamp01(l2 + (R - l2) * 1.3f); G = clamp01(l2 + (G - l2) * 1.3f); B = clamp01(l2 + (B - l2) * 1.3f);
                out[y * S + x] = (Math.round(cov * 255f) << 24) | (Math.round(R * 255f) << 16) | (Math.round(G * 255f) << 8) | Math.round(B * 255f);
            }
        }
        Bitmap bmp = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888);
        bmp.setPixels(out, 0, S, 0, 0, S, S);
        return bmp;
    }
}
