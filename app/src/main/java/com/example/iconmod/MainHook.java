package com.example.iconmod;

import android.content.ComponentName;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.SharedPreferences;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.LauncherActivityInfo;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewTreeObserver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.os.Process;
import dalvik.system.DexFile;
import de.robv.android.xposed.*;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Enumeration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.WeakHashMap;

/**
 * One UI 8 Home uses HoneySpace. App icons are born in
 * com.honeyspace.sdk.source.IconSource.getAppIconAndLabel(ComponentKey, Z, Z, Z, Z, Continuation)
 * (suspend function, returns IconAndLabel(Bitmap, CharSequence)).
 * The class implementing the interface is obfuscated (w0 in this build), so it is discovered at runtime.
 */
public class MainHook implements IXposedHookLoadPackage {
    static final String ME = "com.example.iconmod";
    static final String LAUNCHER = "com.sec.android.app.launcher";
    static final String ICON_SOURCE = "com.honeyspace.sdk.source.IconSource";
    static final String ICON_AND_LABEL = "com.honeyspace.sdk.source.entity.IconAndLabel";

    final IconPack pack = new IconPack();
    IconOpts opts = new IconOpts();
    String packName;
    Class<?> ialClass;
    ClassLoader cl;
    int errCount;
    boolean loggedOk;

    static String stack(Throwable t) { return android.util.Log.getStackTraceString(t); }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!LAUNCHER.equals(lp.packageName)) return;
        try {
            cl = lp.classLoader;
            XposedBridge.log("IconMod: module loaded in the launcher (v3)");

            // 1) the file the app writes INTO THE LAUNCHER's data dir on Save (always readable by us, no LSPosed/SELinux involved)
            SharedPreferences src = null;
            try {
                File cf = new File(lp.appInfo.dataDir, "files/iconmod_config.txt");
                FilePrefs fp = FilePrefs.load(cf);
                if (fp != null) {
                    src = fp;
                    XposedBridge.log("IconMod: config read from the launcher-side file " + cf + " (" + fp.size() + " keys)");
                } else XposedBridge.log("IconMod: no launcher-side config file (" + cf + "); trying the module's shared prefs");
            } catch (Throwable t) { XposedBridge.log("IconMod: launcher-side config error: " + stack(t)); }
            // 2) fallback: the module's own shared prefs via LSPosed
            if (src == null) {
                XSharedPreferences p = new XSharedPreferences(ME, "config");
                p.makeWorldReadable();
                File pf = p.getFile();
                XposedBridge.log("IconMod: prefs " + pf + " exists=" + pf.exists() + " readable=" + pf.canRead());
                src = p;
            }
            opts = IconOpts.fromPrefs(src);
            packName = src.getString("pack", "");
            pack.setOverrides(src.getStringSet("overrides", new HashSet<String>()));
            XposedBridge.log("IconMod: " + opts + " pack='" + packName + "'");

            Class<?> iface = XposedHelpers.findClassIfExists(ICON_SOURCE, cl);
            ialClass = XposedHelpers.findClassIfExists(ICON_AND_LABEL, cl);
            if (iface == null || ialClass == null) {
                XposedBridge.log("IconMod: IconSource/IconAndLabel not found (different launcher version?)");
                return;
            }
            Class<?> impl = findImpl(iface);
            if (impl == null) { XposedBridge.log("IconMod: IconSource implementation NOT found"); return; }
            XposedBridge.log("IconMod: IconSource impl = " + impl.getName());
            hook(impl);
            XposedBridge.log("IconMod: icon hook installed");
            try { hookTransition(); } catch (Throwable t) { XposedBridge.log("IconMod: hookTransition failed: " + stack(t)); }
            // blur no longer depends on liquid glass
            try { if (opts.blur) hookBlur(); } catch (Throwable t) { XposedBridge.log("IconMod: hookBlur failed: " + stack(t)); }
        } catch (Throwable t) {
            XposedBridge.log("IconMod: ERROR in handleLoadPackage: " + stack(t));
        }
    }

    /** Tries the known name (w0) and, if that fails, scans the com.honeyspace.* classes. */
    Class<?> findImpl(Class<?> iface) {
        Class<?> k = XposedHelpers.findClassIfExists("com.honeyspace.core.repository.w0", cl);
        if (k != null && iface.isAssignableFrom(k)) return k;
        try {
            Object pathList = XposedHelpers.getObjectField(cl, "pathList");
            Object[] els = (Object[]) XposedHelpers.getObjectField(pathList, "dexElements");
            for (Object e : els) {
                DexFile df = (DexFile) XposedHelpers.getObjectField(e, "dexFile");
                if (df == null) continue;
                Enumeration<String> en = df.entries();
                while (en.hasMoreElements()) {
                    String n = en.nextElement();
                    if (!n.startsWith("com.honeyspace.") || n.contains("$")) continue;
                    try {
                        Class<?> c = Class.forName(n, false, cl);
                        if (!c.isInterface() && iface.isAssignableFrom(c)) return c;
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable t) { XposedBridge.log("IconMod: scan failed " + t); }
        return null;
    }

    void hook(final Class<?> impl) {
        XposedBridge.hookAllMethods(impl, "getAppIconAndLabel", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    int last = param.args.length - 1;
                    final Object cont = param.args[last];
                    // state machine re-entry (the impl's own continuation): leave it alone, otherwise the function restarts
                    if (cont == null || cont.getClass().getName().startsWith(impl.getName() + "$")) return;
                    final Object key = param.args[0];
                    param.setObjectExtra("wrapped", Boolean.TRUE);
                    param.setObjectExtra("key", key);
                    Class<?> contIface = XposedHelpers.findClass("kotlin.coroutines.Continuation", cl);
                    param.args[last] = Proxy.newProxyInstance(cl, new Class<?>[]{contIface}, new InvocationHandler() {
                        @Override public Object invoke(Object proxy, Method m, Object[] a) throws Throwable {
                            if ("resumeWith".equals(m.getName()) && a != null && a.length == 1
                                    && a[0] != null && ialClass.isInstance(a[0])) {
                                a[0] = transform(a[0], key);
                            }
                            if ("resumeWith".equals(m.getName()) && a != null && a.length == 1) {
                                // The Kotlin runtime resumes a suspend call through cont.intercepted() (-> the caller's dispatcher).
                                // That only works for its own ContinuationImpl, not for this proxy, so do it by hand: otherwise the
                                // caller resumes on the worker thread ("DataBinding must be created in view's UI Thread").
                                try {
                                    Object ctxObj = XposedHelpers.callMethod(cont, "getContext");
                                    Class<?> ci = XposedHelpers.findClass("kotlin.coroutines.ContinuationInterceptor", cl);
                                    Object ikey = XposedHelpers.getStaticObjectField(ci, "Key");
                                    Object interceptor = XposedHelpers.callMethod(ctxObj, "get", ikey);
                                    if (interceptor != null) {
                                        Object dispatched = XposedHelpers.callMethod(interceptor, "interceptContinuation", cont);
                                        XposedHelpers.callMethod(dispatched, "resumeWith", a[0]);
                                        return null;
                                    }
                                } catch (Throwable t) { XposedBridge.log("IconMod: re-dispatch failed, resuming directly: " + stack(t)); }
                            }
                            try { return m.invoke(cont, a); }
                            catch (java.lang.reflect.InvocationTargetException e) {      // rethrow the REAL exception, not the wrapper
                                XposedBridge.log("IconMod: exception inside the resumed coroutine: " + stack(e.getCause() != null ? e.getCause() : e));
                                throw e.getCause() != null ? e.getCause() : e;
                            }
                        }
                    });
                } catch (Throwable t) { XposedBridge.log("IconMod: before error " + t); }
            }

            @Override protected void afterHookedMethod(MethodHookParam param) {
                // direct return (without suspending)
                try {
                    if (param.getObjectExtra("wrapped") == null) return;
                    Object r = param.getResult();
                    if (r != null && ialClass.isInstance(r))
                        param.setResult(transform(r, param.getObjectExtra("key")));
                } catch (Throwable t) { XposedBridge.log("IconMod: after error " + t); }
            }
        });
    }

    /** Final icon (same for the normal state and the animation): uses the same code everywhere. */
    Bitmap finalIcon(Context ctx, ComponentName cn, Bitmap old) {
        boolean hasPack = !packName.isEmpty();
        if (hasPack) pack.load(ctx, packName);
        Bitmap res = IconRender.make(ctx, cn, old, pack, hasPack, opts);
        if (res != null) {
            synchronized (iconBitmaps) { iconBitmaps.put(res, Boolean.TRUE); }
            synchronized (iconSizes) { iconSizes.add(res.getWidth()); }
        }
        return res;
    }

    // bitmaps the hook handed to the launcher (to recognize the icon's drawBitmap) and their sizes
    final java.util.Map<Bitmap, Boolean> iconBitmaps = new java.util.WeakHashMap<>();
    final java.util.HashSet<Integer> iconSizes = new java.util.HashSet<>();

    /** True when a good part of the icon is see-through (icon packs like glass icons, liquid glass, reduced opacity). */
    static boolean isTranslucent(Bitmap b) {
        try {
            int w = b.getWidth(), h = b.getHeight(), n = 0, t = 0;
            for (int i = 0; i < 16; i++) for (int j = 0; j < 16; j++) {
                int x = (int) (w * (0.2f + 0.6f * i / 15f)), y = (int) (h * (0.2f + 0.6f * j / 15f));
                n++;
                if ((b.getPixel(Math.min(x, w - 1), Math.min(y, h - 1)) >>> 24) < 235) t++;
            }
            return t * 100 / n >= 12;
        } catch (Throwable e) { return false; }
    }

    volatile Bitmap lastBgPlate;                 // full-bleed version (radius 0): the animation outline rounds it
    final WeakHashMap<Drawable, Boolean> ourBgs = new WeakHashMap<>();   // background drawables created by us
    volatile Bitmap lastPlate;
    volatile long lastPlateTime;
    final WeakHashMap<Drawable, Bitmap> plates = new WeakHashMap<>();   // animation icon drawable -> its plate

    /**
     * Composites the blurred wallpaper (cropped where the icon sits on the home screen) under the icon bitmap,
     * with the same glass effects as the live plate. Result: an opaque tile identical to the final icon.
     */
    Bitmap bakeBackdrop(Context ctx, View home, Bitmap icon) {
        try {
            Bitmap bl = blurBmp;
            if (bl == null) return null;
            opts.resolveTint(ctx);
            Matrix m = new Matrix();
            viewToScreen(home, m);
            Rect ip = null;
            try { ip = (Rect) callIconPos.call(home); } catch (Throwable ignored) {}
            if (ip == null || ip.isEmpty()) ip = new Rect(0, 0, home.getWidth(), home.getHeight());
            RectF scr = new RectF(ip);
            m.mapRect(scr);
            int ds = BackdropBlur.DS;
            int x = Math.max(0, Math.min(bl.getWidth() - 2, Math.round(scr.left / ds)));
            int y = Math.max(0, Math.min(bl.getHeight() - 2, Math.round(scr.top / ds)));
            int w = Math.max(1, Math.min(bl.getWidth() - x, Math.round(scr.width() / ds)));
            int h = Math.max(1, Math.min(bl.getHeight() - y, Math.round(scr.height() / ds)));
            int S = icon.getWidth();
            Bitmap big = Bitmap.createScaledBitmap(Bitmap.createBitmap(bl, x, y, w, h), S, S, true);
            float[] L = BackdropFx.light(scr.centerX(), scr.centerY(), bl.getWidth() * ds, bl.getHeight() * ds);
            Bitmap plate = BackdropFx.render(big, opts, Math.round(opts.size * opts.blurFit / 100f), L[0], L[1]);
            Bitmap out = plate.copy(Bitmap.Config.ARGB_8888, true);
            new Canvas(out).drawBitmap(icon, 0, 0, null);
            lastPlate = plate;                         // plate with the icon's own rounding
            lastBgPlate = BackdropFx.render(big, opts, 100, 0, L[0], L[1]);   // full-bleed: used as the animation background
            lastPlateTime = System.currentTimeMillis();
            return out;
        } catch (Throwable t) {
            XposedBridge.log("IconMod: bakeBackdrop failed: " + stack(t));
            return null;
        }
    }

    /**
     * App open/close animation: UtilsKt.getTargetIconDrawable(view, LauncherActivityInfo, Drawable, Z, Z)
     * returns the icon used in the animation (Samsung's themed/gray version). We swap it for the SAME icon
     * as the normal state, read from the cache written by the hook, so a different icon doesn't "flash".
     */
    void hookTransition() {
        Class<?> u = XposedHelpers.findClassIfExists("com.honeyspace.transition.anim.floating.UtilsKt", cl);
        if (u == null) { XposedBridge.log("IconMod: UtilsKt (animation) not found"); return; }
        XC_MethodHook animHook = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                try {
                    ComponentName cn = null;
                    for (Object a : param.args)
                        if (a instanceof LauncherActivityInfo) cn = ((LauncherActivityInfo) a).getComponentName();
                    if (cn == null) return;
                    Context ctx = (Context) XposedHelpers.callStaticMethod(
                            XposedHelpers.findClass("android.app.ActivityThread", null), "currentApplication");
                    if (ctx == null) return;
                    File f = new File(new File(ctx.getCacheDir(), "iconmod_orig"),
                            cn.getPackageName() + "__" + cn.getClassName() + ".png");
                    if (!f.exists()) { XposedBridge.log("IconMod: animation has no sample for " + cn.flattenToString()); return; }
                    dumpThemed(ctx, cn, param.getResult());
                    Bitmap old = BitmapFactory.decodeFile(f.getPath());
                    if (old == null) return;
                    Bitmap bmp = finalIcon(ctx, cn, old);
                    if (bmp == null) return;
                    // translucent icon (glass / reduced opacity): the shrinking app window shows through it (the "white circle").
                    // Bake the blurred wallpaper behind it so the animated icon is opaque and looks like the final icon.
                    lastPlate = null; lastBgPlate = null;     // never reuse the plate of another icon
                    if (opts.blur) {      // the blur plate is behind every icon: the animation uses it regardless of the icon pack
                        View home = null;
                        for (Object a : param.args) if (a instanceof View) home = (View) a;
                        Bitmap baked = home == null ? null : bakeBackdrop(ctx, home, bmp);
                        if (baked != null) bmp = baked;
                        else {
                            XposedBridge.log("IconMod: animation bake skipped: home view=" + (home != null) + " blur ready=" + (blurBmp != null));
                            if (blurBmp == null) ensureBackdrop(ctx);
                        }
                    }
                    BitmapDrawable bd = new BitmapDrawable(ctx.getResources(), bmp);
                    Bitmap bgp = lastBgPlate;
                    if (lastPlate != null && bgp != null) synchronized (plates) { plates.put(bd, bgp); }
                    param.setResult(bd);
                    XposedBridge.log("IconMod: animation icon replaced (" + param.method.getName() + "): " + cn.flattenToString());
                } catch (Throwable t) { XposedBridge.log("IconMod: animation error " + t); }
            }
        };
        XposedBridge.hookAllMethods(u, "getTargetIconDrawable", animHook);   // getDrawable is called inside it: don't hook it separately

        // The animation background (the "ball" behind the icon / app window) is built from the icon's outer colour
        // (setupBackgroundView -> getBackgroundFromDrawable -> getCommonOuterColor). Use OUR plate instead: blur + tint.
        XposedBridge.hookAllMethods(u, "getBackgroundFromDrawable", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (!opts.blur) return;
                    Drawable arg = param.args.length > 0 && param.args[0] instanceof Drawable ? (Drawable) param.args[0] : null;
                    Bitmap plate = null;
                    if (arg != null) synchronized (plates) { plate = plates.get(arg); }
                    boolean byId = plate != null;
                    if (plate == null && lastBgPlate != null && System.currentTimeMillis() - lastPlateTime < 3000) plate = lastBgPlate;
                    if (plate == null) return;
                    Context ctx = (Context) XposedHelpers.callStaticMethod(
                            XposedHelpers.findClass("android.app.ActivityThread", null), "currentApplication");
                    if (ctx == null) return;
                    BitmapDrawable bgd = new BitmapDrawable(ctx.getResources(), plate);
                    synchronized (ourBgs) { ourBgs.put(bgd, Boolean.TRUE); }
                    param.setResult(bgd);     // skips the original (flat colour circle)
                    XposedBridge.log("IconMod: animation background replaced by the glass plate (" + (byId ? "by drawable" : "by time") + ")");
                } catch (Throwable t) { XposedBridge.log("IconMod: animation background error " + stack(t)); }
            }
        });
        // The animation clips its background view with an Outline (cornerRadiusSupport$1.getOutline). For icons the radius was the
        // "full" radius (a circle). With our plate as background, use the icon's own rounding (opts.radius % of the view's size),
        // growing with the view up to the window's corner radius.
        Class<?> oc = XposedHelpers.findClassIfExists("com.honeyspace.transition.anim.floating.FloatingAnimator$Player$cornerRadiusSupport$1", cl);
        if (oc != null) XposedBridge.hookAllMethods(oc, "getOutline", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                try {
                    View v = (View) param.args[0];
                    Outline out = (Outline) param.args[1];
                    Drawable bg = v.getBackground();
                    boolean ours;
                    synchronized (ourBgs) { ours = bg != null && ourBgs.containsKey(bg); }
                    if (!ours) return;
                    int w = v.getWidth(), h = v.getHeight();
                    if (w <= 0 || h <= 0) return;
                    float r = opts.radius / 100f * Math.min(w, h);
                    try {                                   // cap = the window's corner radius (in this view's own pixels)
                        Object player = XposedHelpers.getObjectField(param.thisObject, "this$0");
                        float wr = XposedHelpers.getFloatField(player, "windowRadius");
                        float s = Math.max(Math.abs(v.getScaleX()), 0.01f);
                        if (wr > 0f) r = Math.min(r, wr / s);
                    } catch (Throwable ignored) {}
                    out.setRoundRect(0, 0, w, h, Math.min(r, Math.min(w, h) / 2f));
                } catch (Throwable t) { XposedBridge.log("IconMod: outline error " + stack(t)); }
            }
        });
        else XposedBridge.log("IconMod: cornerRadiusSupport$1 not found (the animation shape stays as the launcher's)");
        XposedBridge.log("IconMod: animation hook active");
    }

    // ------------------------------------------------------------------ wallpaper blur behind the glass
    volatile Bitmap blurBmp;
    volatile boolean building;
    boolean receiverRegistered;
    int blurLogs;
    final WeakHashMap<View, float[]> iconViews = new WeakHashMap<>();
    final WeakHashMap<ViewTreeObserver, Boolean> observers = new WeakHashMap<>();
    Handler mainHandler;   // created only when needed: the main looper doesn't exist yet when the module is instantiated

    Handler main() {
        if (mainHandler == null) mainHandler = new Handler(Looper.getMainLooper());
        return mainHandler;
    }

    // ---- view -> screen matrix (hidden View.transformMatrixToGlobal): includes the scale/translation of the pages
    // and of the open/close animations, which getLocationOnScreen() alone does not capture.
    java.lang.reflect.Method toGlobal;
    boolean toGlobalTried;
    volatile int screenW;

    /** Fills `out` with the view's local->screen matrix. Falls back to translation only if the method doesn't exist. */
    boolean viewToScreen(View v, Matrix out) {
        out.reset();
        try {
            if (!toGlobalTried) {
                toGlobalTried = true;
                try { toGlobal = XposedHelpers.findMethodExact(View.class, "transformMatrixToGlobal", Matrix.class); }
                catch (Throwable t) { XposedBridge.log("IconMod: transformMatrixToGlobal not found, using position only: " + t); }
            }
            if (toGlobal != null) { toGlobal.invoke(v, out); return true; }
        } catch (Throwable ignored) { out.reset(); }
        int[] l = new int[2];
        v.getLocationOnScreen(l);
        out.setTranslate(l[0], l[1]);
        return false;
    }

    /** Only the real home screen window: previews (wallpaper picker) and other scaled/hosted windows get no blur. */
    boolean isMainWindow(View v) {
        if (screenW == 0) screenW = BackdropBlur.screenSize(v.getContext())[0];
        View root = v.getRootView();
        return root != null && Math.abs(root.getWidth() - screenW) <= Math.max(8, screenW / 50);
    }

    final ViewTreeObserver.OnPreDrawListener moveWatcher = new ViewTreeObserver.OnPreDrawListener() {
        final Matrix m = new Matrix();
        final float[] vals = new float[9];
        @Override public boolean onPreDraw() {
            for (View v : iconViews.keySet()) {     // only the main thread touches this; put on an existing key doesn't break the iteration
                if (v == null || !v.isAttachedToWindow() || v.getVisibility() != View.VISIBLE) continue;
                viewToScreen(v, m);
                m.getValues(vals);
                float[] last = iconViews.get(v);
                boolean moved = last == null;
                if (!moved) for (int i = 0; i < 6; i++) if (Math.abs(last[i] - vals[i]) > 0.02f) { moved = true; break; }
                if (moved) {
                    iconViews.put(v, vals.clone());
                    v.invalidate();        // the background slice depends on the on-screen position AND scale
                }
            }
            return true;
        }
    };

    void ensureBackdrop(final Context ctx) {
        if (building || blurBmp != null) return;
        building = true;
        new Thread(() -> {
            try {
                Bitmap b = BackdropBlur.build(ctx, opts.blurAmount);
                blurBmp = b;
                XposedBridge.log("IconMod: blurred background " + (b == null ? "FAILED (no wallpaper)"
                        : "pronto " + b.getWidth() + "x" + b.getHeight()));
                main().post(() -> { for (View v : new ArrayList<>(iconViews.keySet())) if (v != null) v.invalidate(); });
            } finally { building = false; }
        }, "IconMod-blur").start();
        if (!receiverRegistered) {
            receiverRegistered = true;
            try {
                ctx.getApplicationContext().registerReceiver(new BroadcastReceiver() {
                    @Override public void onReceive(Context c, Intent i) { blurBmp = null; opts.materialReady = false; ensureBackdrop(c); }
                }, new IntentFilter(Intent.ACTION_WALLPAPER_CHANGED));
            } catch (Throwable t) { XposedBridge.log("IconMod: wallpaper receiver: " + t); }
        }
    }

    /** No-arg reflective call with the Method cached per class (onDraw runs every frame, for every icon). */
    static final class NoArgCall {
        final String name;
        final java.util.HashMap<Class<?>, Method> cache = new java.util.HashMap<>();
        NoArgCall(String name) { this.name = name; }
        Object call(Object o) throws Throwable {
            Class<?> k = o.getClass();
            Method m = cache.get(k);
            if (m == null) { m = XposedHelpers.findMethodBestMatch(k, name); cache.put(k, m); }
            return m.invoke(o);
        }
    }
    final NoArgCall callIcon = new NoArgCall("getIcon"), callIconPos = new NoArgCall("getIconPosition");

    // state of the onDraw in progress (everything on the main thread)
    View curView;
    final Matrix curMat = new Matrix();   // local->screen matrix of the view being drawn
    boolean plateDrawn, inBitmapHook;

    boolean isIconBitmap(Bitmap bmp) {
        synchronized (iconBitmaps) { if (iconBitmaps.containsKey(bmp)) return true; }
        if (bmp.getWidth() != bmp.getHeight()) return false;
        synchronized (iconSizes) { return iconSizes.contains(bmp.getWidth()); }
    }

    /**
     * Where the icon is ACTUALLY drawn is not what the launcher reports (getIconPosition/bounds were larger and the blur
     * ended up larger than the glass). So this intercepts the icon's own drawBitmap inside IconViewImpl.onDraw and draws
     * the blur plate right there, in the same canvas state (same position, scale and transforms).
     * If that drawBitmap is not seen, it falls back to the rectangle reported by the launcher, drawing BEHIND the icon.
     */
    void hookBlur() {
        Class<?> c = XposedHelpers.findClassIfExists("com.honeyspace.ui.common.iconview.IconViewImpl", cl);
        if (c == null) { XposedBridge.log("IconMod: IconViewImpl not found (blur disabled)"); return; }

        XposedBridge.hookAllMethods(c, "onDraw", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                curView = null; plateDrawn = false;
                try {
                    View v = (View) param.thisObject;
                    if (v.getClass().getName().contains("Folder")) return;
                    if (!isMainWindow(v)) return;       // preview windows etc.: they would misplace the plate
                    Bitmap bl = blurBmp;
                    if (bl == null) { iconViews.put(v, null); ensureBackdrop(v.getContext()); return; }
                    if (opts.tintSource > 0 && !opts.materialReady) opts.resolveTint(v.getContext());
                    viewToScreen(v, curMat);
                    float[] vals = new float[9];
                    curMat.getValues(vals);
                    iconViews.put(v, vals);
                    ViewTreeObserver vto = v.getViewTreeObserver();
                    if (vto.isAlive() && !observers.containsKey(vto)) { observers.put(vto, true); vto.addOnPreDrawListener(moveWatcher); }
                    curView = v;
                } catch (Throwable t) { XposedBridge.log("IconMod: blur error " + t); }
            }
            // no afterHook: if the icon's own drawBitmap is not recognized, NO plate is drawn (the old fallback
            // used the launcher's reported rectangle, which was larger/offset: it caused the bug in previews and animations)
            @Override protected void afterHookedMethod(MethodHookParam param) { curView = null; }
        });

        XC_MethodHook bmpHook = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (curView == null || plateDrawn || inBitmapHook) return;
                try {
                    Object[] a = param.args;
                    if (a.length < 3 || !(a[0] instanceof Bitmap)) return;
                    Bitmap bmp = (Bitmap) a[0];
                    if (!isIconBitmap(bmp)) return;
                    float l, t, r, b;
                    if (a.length == 4 && a[2] instanceof Rect) { Rect d = (Rect) a[2]; l = d.left; t = d.top; r = d.right; b = d.bottom; }
                    else if (a.length == 4 && a[2] instanceof RectF) { RectF d = (RectF) a[2]; l = d.left; t = d.top; r = d.right; b = d.bottom; }
                    else if (a.length == 4 && a[1] instanceof Float && a[2] instanceof Float) {
                        l = (Float) a[1]; t = (Float) a[2]; r = l + bmp.getWidth(); b = t + bmp.getHeight();
                    } else return;
                    Bitmap bl = blurBmp;
                    if (bl == null || r <= l || b <= t) return;
                    inBitmapHook = true;
                    plateDrawn = true;
                    if (blurLogs < 6) {
                        blurLogs++;
                        XposedBridge.log("IconMod: blur drawBitmap " + bmp.getWidth() + "x" + bmp.getHeight() + " dst=[" + l + "," + t + "," + r + "," + b
                                + "] view=" + curView.getWidth() + "x" + curView.getHeight() + " toScreen=" + curMat.toShortString());
                    }
                    BackdropBlur.draw((Canvas) param.thisObject, bl, curMat, new RectF(l, t, r, b), opts, opts.size * opts.blurFit / 100f, false);
                } catch (Throwable ex) { XposedBridge.log("IconMod: blur bmp error " + ex); }
                finally { inBitmapHook = false; }
            }
        };
        int n = 0;
        for (String name : new String[]{"android.graphics.BaseRecordingCanvas", "android.graphics.RecordingCanvas"}) {
            Class<?> k = XposedHelpers.findClassIfExists(name, null);
            if (k == null) continue;
            for (Method m : k.getDeclaredMethods())
                if (m.getName().equals("drawBitmap")) { try { XposedBridge.hookMethod(m, bmpHook); n++; } catch (Throwable ignored) {} }
        }
        XposedBridge.log("IconMod: blur hook active (drawBitmap hooks=" + n + ")");
    }

    /** Stores Samsung's THEMED icon (the animation's original result) for the app preview. */
    static void dumpThemed(Context ctx, ComponentName cn, Object result) {
        try {
            if (!(result instanceof android.graphics.drawable.Drawable)) return;
            File dir = new File(ctx.getCacheDir(), "iconmod_themed");
            if (!dir.exists()) { dir.mkdirs(); dir.setReadable(true, false); dir.setExecutable(true, false); }
            File f = new File(dir, cn.getPackageName() + "__" + cn.getClassName() + ".png");
            if (f.exists()) return;
            android.graphics.drawable.Drawable d = (android.graphics.drawable.Drawable) result;
            android.graphics.Rect saved = d.copyBounds();
            int w = d.getIntrinsicWidth() > 0 ? d.getIntrinsicWidth() : 192;
            int h = d.getIntrinsicHeight() > 0 ? d.getIntrinsicHeight() : 192;
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, w, h);
            d.draw(new android.graphics.Canvas(b));
            d.setBounds(saved);
            FileOutputStream o = new FileOutputStream(f);
            b.compress(Bitmap.CompressFormat.PNG, 100, o);
            o.close();
            f.setReadable(true, false);
        } catch (Throwable ignored) {}
    }

    /** Stores the ORIGINAL One UI Home icon (before the hook) for the module app's preview. */
    static void dump(Context ctx, ComponentName cn, Bitmap b) {
        try {
            File dir = new File(ctx.getCacheDir(), "iconmod_orig");
            if (!dir.exists()) { dir.mkdirs(); dir.setReadable(true, false); dir.setExecutable(true, false); }
            File f = new File(dir, cn.getPackageName() + "__" + cn.getClassName() + ".png");
            if (f.exists()) return;
            String[] l = dir.list();
            if (l != null && l.length > 400) return;
            FileOutputStream o = new FileOutputStream(f);
            b.compress(Bitmap.CompressFormat.PNG, 100, o);
            o.close();
            f.setReadable(true, false);
        } catch (Throwable ignored) {}
    }

    Object transform(Object ial, Object key) {
        try {
            Bitmap old = (Bitmap) XposedHelpers.callMethod(ial, "getIcon");
            CharSequence label = (CharSequence) XposedHelpers.callMethod(ial, "getLabel");
            if (old == null || old.getWidth() <= 2) return ial;          // placeholder/low-res icon
            if (!Process.myUserHandle().equals(XposedHelpers.callMethod(key, "getUser"))) return ial; // main user only
            ComponentName cn = (ComponentName) XposedHelpers.callMethod(key, "getComponentName");
            if (cn == null) return ial;

            Context ctx = (Context) XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", null), "currentApplication");
            if (ctx == null) return ial;

            dump(ctx, cn, old);
            Bitmap bmp = finalIcon(ctx, cn, old);
            if (bmp == null) {
                if (opts.blur) {   // icon left as-is: still register it so the blur plate finds the icon's drawBitmap
                    synchronized (iconBitmaps) { iconBitmaps.put(old, Boolean.TRUE); }
                    synchronized (iconSizes) { iconSizes.add(old.getWidth()); }
                }
                return ial;
            }
            if (!loggedOk) { loggedOk = true; XposedBridge.log("IconMod: first icon transformed: " + cn.flattenToString() + " " + bmp.getWidth() + "px"); }
            return XposedHelpers.newInstance(ial.getClass(), bmp, label);
        } catch (Throwable t) {
            if (errCount++ < 5) XposedBridge.log("IconMod: transform error: " + stack(t));
            return ial;
        }
    }
}
