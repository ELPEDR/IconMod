package com.example.iconmod;

import android.content.ComponentName;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.util.Xml;
import de.robv.android.xposed.XposedBridge;
import org.xmlpull.v1.XmlPullParser;
import java.io.InputStream;
import java.util.*;

/** Reads an icon pack's appfilter.xml (Nova/Apex/ADW format). Works in the launcher (hook) and in the module app. */
class IconPack {
    static void log(String s) {
        try { XposedBridge.log(s); } catch (Throwable t) { Log.d("IconMod", s); }
    }

    private final Map<String, String> map = new HashMap<>();
    private final Map<String, String> overrides = new HashMap<>();
    private final LinkedHashSet<String> filterNames = new LinkedHashSet<>();
    private final List<String> backs = new ArrayList<>(), masks = new ArrayList<>();
    private List<String> iconNames = new ArrayList<>();
    private Resources res;
    private String pkg;
    private boolean loaded;
    private Bitmap shapeCache;

    private XmlPullParser open(Context pc, String name) throws Exception {
        int id = res.getIdentifier(name, "xml", pkg);
        if (id != 0) return res.getXml(id);
        try {
            InputStream is = pc.getAssets().open(name + ".xml");
            XmlPullParser xp = Xml.newPullParser();
            xp.setInput(is, "utf-8");
            return xp;
        } catch (Exception e) { return null; }
    }

    synchronized void load(Context ctx, String pack) {
        if (loaded) return;
        loaded = true;
        try {
            pkg = pack;
            Context pc = ctx.createPackageContext(pack, 0);
            res = pc.getResources();
            XmlPullParser xp = open(pc, "appfilter");
            if (xp != null) {
                int ev;
                while ((ev = xp.next()) != XmlPullParser.END_DOCUMENT) {
                    if (ev != XmlPullParser.START_TAG) continue;
                    String tag = xp.getName();
                    if ("iconback".equals(tag) || "iconmask".equals(tag)) {
                        List<String> dst = "iconback".equals(tag) ? backs : masks;
                        for (int a = 0; a < xp.getAttributeCount(); a++)
                            if (xp.getAttributeName(a).startsWith("img")) dst.add(xp.getAttributeValue(a));
                    } else if ("item".equals(tag)) {
                        String comp = xp.getAttributeValue(null, "component");
                        String dr = xp.getAttributeValue(null, "drawable");
                        if (comp == null || dr == null) continue;
                        if (comp.startsWith("ComponentInfo{") && comp.endsWith("}"))
                            comp = comp.substring(14, comp.length() - 1);
                        map.put(comp, dr);
                        filterNames.add(dr);
                    }
                }
            }
            // pack icon list: res/xml/drawable.xml (standard) or, if missing, the ones from appfilter
            LinkedHashSet<String> dn = new LinkedHashSet<>();
            try {
                XmlPullParser dx = open(pc, "drawable");
                if (dx != null) {
                    int ev;
                    while ((ev = dx.next()) != XmlPullParser.END_DOCUMENT)
                        if (ev == XmlPullParser.START_TAG && "item".equals(dx.getName())) {
                            String d = dx.getAttributeValue(null, "drawable");
                            if (d != null) dn.add(d);
                        }
                }
            } catch (Throwable ignored) {}
            iconNames = new ArrayList<>(dn.isEmpty() ? filterNames : dn);
            Collections.sort(iconNames);
            log("IconMod: icon pack loaded, " + map.size() + " items, " + iconNames.size() + " icons");
        } catch (Throwable t) {
            log("IconMod: failed to load icon pack " + pack + ": " + t);
        }
    }

    /** Manually chosen "pkg/class|drawable_name" entries. */
    synchronized void setOverrides(Set<String> set) {
        overrides.clear();
        if (set == null) return;
        for (String e : set) {
            int i = e.indexOf('|');
            if (i > 0) overrides.put(e.substring(0, i), e.substring(i + 1));
        }
    }

    List<String> names() { return iconNames; }

    Drawable drawable(String name) {
        if (res == null || name == null) return null;
        int id = res.getIdentifier(name, "drawable", pkg);
        if (id == 0) return null;
        try { return res.getDrawable(id, null); } catch (Throwable t) { return null; }
    }

    synchronized Drawable get(ComponentName cn) {
        if (res == null) return null;
        String flat = cn.flattenToString();
        String d = overrides.get(flat);
        if (d == null) d = map.get(flat);
        return drawable(d);
    }

    /** 0 = ok in pack, 1 = no icon in pack, 2 = mapped but the drawable doesn't exist, 3 = custom. */
    int status(ComponentName cn) {
        if (res == null) return 1;
        String flat = cn.flattenToString();
        String o = overrides.get(flat);
        if (o != null && res.getIdentifier(o, "drawable", pkg) != 0) return 3;
        String d = map.get(flat);
        if (d == null) return 1;
        return res.getIdentifier(d, "drawable", pkg) != 0 ? 0 : 2;
    }

    private Bitmap toBitmap(String name, int w, int h) {
        Drawable d = drawable(name);
        if (d == null) return null;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, w, h);
        d.draw(new Canvas(b));
        return b;
    }

    /** The pack's shape (iconmask or iconback) as a bitmap whose ALPHA defines the kept area. null if the pack has no shape. */
    synchronized Bitmap shape(int w, int h) {
        if (shapeCache != null && shapeCache.getWidth() == w && shapeCache.getHeight() == h) return shapeCache;
        Bitmap out = null;
        if (!masks.isEmpty()) {                       // Nova/ADW standard: opaque mask pixels are removed
            Bitmap m = toBitmap(masks.get(0), w, h);
            if (m != null) {
                out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(out);
                c.drawColor(Color.WHITE);
                Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
                p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
                c.drawBitmap(m, 0, 0, p);
            }
        }
        if (out == null && !backs.isEmpty()) out = toBitmap(backs.get(0), w, h);   // iconback alpha
        shapeCache = out;
        return out;
    }
}
