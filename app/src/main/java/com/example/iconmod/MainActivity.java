package com.example.iconmod;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.*;

public class MainActivity extends Activity {
    static final String LAUNCHER = "com.sec.android.app.launcher";
    static final String[] ACTIONS = {"org.adw.launcher.THEMES", "com.novalauncher.THEME", "com.anddoes.launcher.THEME",
            "com.gau.go.launcherex.theme", "org.adw.ActivityStarter.THEMES"};
    static final String[] STATUS = {"OK in pack", "No icon in pack", "Failed to load icon", "Custom"};

    static class PackInfo { String pkg; CharSequence label; Drawable icon; }
    static class Row { ComponentName cn; CharSequence label; ResolveInfo ri; int status; }

    SharedPreferences prefs;
    String chosenPack = "";
    CharSequence chosenLabel = "None";
    IconPack pack;
    String packFor;

    // Settings tab
    Switch swCorners, swRegen, swGlass, swMono, swThemed, swBlur;
    SeekBar sbR, sbZ, sbSize, sbPlate, sbEdge, sbBevel, sbLens, sbFrost, sbBlur;
    // async preview: rendering runs on a single thread; stale requests are discarded (renderGen)
    final java.util.concurrent.ExecutorService renderEx = java.util.concurrent.Executors.newSingleThreadExecutor();
    final java.util.concurrent.atomic.AtomicInteger renderGen = new java.util.concurrent.atomic.AtomicInteger();
    volatile boolean dragging, samplesDirty = true;
    List<Sample> samples = new ArrayList<>();      // only the render thread touches this
    boolean samplesThemed;
    String sampleHint = "";
    final List<ImageView> origViews = new ArrayList<>(), resViews = new ArrayList<>();
    SeekBar sbFit, sbGlyph, sbAlpha, sbRefr, sbAb, sbBFrost, sbTHue, sbTAmt;
    Switch swSpec;
    RadioGroup rgTint;

    static class Sample { ComponentName cn; Bitmap orig, slice, blSmall; Drawable appIcon; int blAmt = -1; }
    Button pick;
    LinearLayout previewRow;
    TextView previewHint;
    LinearLayout configView;   // fixed preview on top + ScrollView with the controls
    // Icons tab
    LinearLayout iconsView;
    EditText search;
    CheckBox showAll;
    TextView listHint;
    final List<Row> allRows = new ArrayList<>(), shown = new ArrayList<>();
    BaseAdapter rowAdapter;
    Button tab0, tab1;

    int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }
    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    // ------------------------------------------------------------ Material You (dynamic color on Android 12+, static palette below)
    boolean night() { return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES; }

    int dyn(int lightRes, int nightRes, int lightFallback, int nightFallback) {
        boolean n = night();
        if (Build.VERSION.SDK_INT >= 31) return getColor(n ? nightRes : lightRes);
        return n ? nightFallback : lightFallback;
    }
    int cPrimary()            { return dyn(android.R.color.system_accent1_600, android.R.color.system_accent1_200, 0xFF3F6BB6, 0xFFAAC7FF); }
    int cOnPrimary()          { return dyn(android.R.color.system_accent1_0,   android.R.color.system_accent1_800, 0xFFFFFFFF, 0xFF002F65); }
    int cPrimaryContainer()   { return dyn(android.R.color.system_accent1_100, android.R.color.system_accent1_700, 0xFFD8E2FF, 0xFF214783); }
    int cOnPrimaryContainer() { return dyn(android.R.color.system_accent1_900, android.R.color.system_accent1_100, 0xFF001A41, 0xFFD8E2FF); }
    int cTertiary()           { return dyn(android.R.color.system_accent3_600, android.R.color.system_accent3_200, 0xFF815600, 0xFFF9BC5B); }
    int cSurface()            { return dyn(android.R.color.system_neutral1_10, android.R.color.system_neutral1_900, 0xFFFDFBFF, 0xFF1A1B1F); }
    int cSurfaceContainer()   { return dyn(android.R.color.system_neutral2_100, android.R.color.system_neutral2_800, 0xFFE9EDF4, 0xFF2B2F36); }
    int cSurfaceVariant()     { return dyn(android.R.color.system_neutral2_200, android.R.color.system_neutral2_700, 0xFFC4C6D0, 0xFF44474F); }
    int cOnSurface()          { return dyn(android.R.color.system_neutral1_900, android.R.color.system_neutral1_100, 0xFF1A1B1F, 0xFFE3E2E6); }
    int cOnSurfaceVariant()   { return dyn(android.R.color.system_neutral2_700, android.R.color.system_neutral2_200, 0xFF44474F, 0xFFC4C6D0); }
    int cOutline()            { return dyn(android.R.color.system_neutral2_500, android.R.color.system_neutral2_400, 0xFF74777F, 0xFF8E9099); }

    GradientDrawable roundRect(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    /** Pill button with a ripple; bg/fg are Material You colors. */
    void styleButton(Button b, int bg, int fg) {
        b.setAllCaps(false);
        b.setTextColor(fg);
        b.setStateListAnimator(null);
        b.setMinHeight(dp(48));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf((fg & 0x00FFFFFF) | 0x33000000), roundRect(bg, 24), roundRect(Color.WHITE, 24)));
    }

    void styleTab(Button b, boolean selected) {
        styleButton(b, selected ? cPrimaryContainer() : cSurfaceContainer(), selected ? cOnPrimaryContainer() : cOnSurfaceVariant());
    }

    LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        c.setBackground(roundRect(cSurfaceContainer(), 24));
        return c;
    }

    void addCard(LinearLayout parent, LinearLayout card) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = dp(12);
        parent.addView(card, p);
    }

    TextView header(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setTextColor(cPrimary());
        t.setPadding(0, 0, 0, dp(6));
        return t;
    }

    void openUrl(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))); }
        catch (Throwable t) { toast("Could not open " + url); }
    }

    LinearLayout.LayoutParams btnLp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(8);
        return p;
    }

    SeekBar bar(final TextView tv, final String fmt, final int min, int max, int cur) {
        SeekBar sb = new SeekBar(this);
        tv.setTextColor(cOnSurfaceVariant());
        tv.setTextSize(13);
        tv.setPadding(0, dp(10), 0, 0);
        ColorStateList accent = ColorStateList.valueOf(cPrimary());
        sb.setProgressTintList(accent);
        sb.setThumbTintList(accent);
        sb.setProgressBackgroundTintList(ColorStateList.valueOf(cSurfaceVariant()));
        sb.setMax(max - min);
        sb.setProgress(cur - min);
        tv.setText(String.format(fmt, cur));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int v, boolean u) {
                tv.setText(String.format(fmt, v + min));
                if (u) refreshPreview();
            }
            public void onStartTrackingTouch(SeekBar s) { dragging = true; }
            public void onStopTrackingTouch(SeekBar s) { dragging = false; refreshPreview(); }
        });
        return sb;
    }

    IconPack currentPack() {
        if (chosenPack.isEmpty()) return null;
        if (pack == null || !chosenPack.equals(packFor)) {
            pack = new IconPack();
            pack.load(this, chosenPack);
            packFor = chosenPack;
        }
        pack.setOverrides(prefs.getStringSet("overrides", new HashSet<String>()));
        return pack;
    }

    // ------------------------------------------------------------ installed icon packs
    List<PackInfo> findPacks() {
        PackageManager pm = getPackageManager();
        Map<String, PackInfo> map = new LinkedHashMap<>();
        for (String a : ACTIONS)
            for (ResolveInfo ri : pm.queryIntentActivities(new Intent(a), 0)) {
                String pk = ri.activityInfo.packageName;
                if (map.containsKey(pk)) continue;
                PackInfo p = new PackInfo();
                p.pkg = pk; p.label = ri.loadLabel(pm); p.icon = ri.loadIcon(pm);
                map.put(pk, p);
            }
        List<PackInfo> list = new ArrayList<>(map.values());
        Collections.sort(list, (a, b) -> a.label.toString().compareToIgnoreCase(b.label.toString()));
        return list;
    }

    void showPackPicker() {
        final List<PackInfo> packs = findPacks();
        PackInfo none = new PackInfo(); none.pkg = ""; none.label = "None (default icons)";
        packs.add(0, none);
        ArrayAdapter<PackInfo> ad = new ArrayAdapter<PackInfo>(this, 0, packs) {
            @Override public View getView(int i, View v, ViewGroup parent) {
                LinearLayout row = new LinearLayout(MainActivity.this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(16), dp(8), dp(16), dp(8));
                ImageView iv = new ImageView(MainActivity.this);
                PackInfo p = getItem(i);
                if (p.icon != null) iv.setImageDrawable(p.icon);
                row.addView(iv, new LinearLayout.LayoutParams(dp(40), dp(40)));
                TextView tv = new TextView(MainActivity.this);
                tv.setText(p.label); tv.setTextSize(16); tv.setPadding(dp(16), 0, 0, 0);
                row.addView(tv);
                return row;
            }
        };
        new AlertDialog.Builder(this)
                .setTitle(packs.size() == 1 ? "No icon packs found" : "Choose icon pack")
                .setAdapter(ad, (d, which) -> {
                    PackInfo p = packs.get(which);
                    chosenPack = p.pkg; chosenLabel = p.label;
                    pick.setText("Icon pack: " + chosenLabel);
                    refreshPreview();
                    loadRows();
                }).show();
    }

    // ------------------------------------------------------------ root / su
    void su(final String cmd, final Runnable onDone) {
        new Thread(() -> {
            String out = "";
            try {
                Process pr = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
                BufferedReader r = new BufferedReader(new InputStreamReader(pr.getInputStream()));
                StringBuilder sb = new StringBuilder(); String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
                pr.waitFor();
                out = sb.toString().trim();
            } catch (Exception e) { out = "su failed: " + e; }
            final String res = out;
            runOnUiThread(() -> { if (!res.isEmpty()) toast(res); if (onDone != null) onDone.run(); });
        }).start();
    }

    void suOut(final String cmd, final java.util.function.Consumer<String> cb) {
        new Thread(() -> {
            String out;
            try {
                Process pr = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
                BufferedReader r = new BufferedReader(new InputStreamReader(pr.getInputStream()));
                StringBuilder sb = new StringBuilder(); String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
                pr.waitFor();
                out = sb.toString();
            } catch (Exception e) { out = "su failed: " + e; }
            final String res = out;
            runOnUiThread(() -> cb.accept(res));
        }).start();
    }

    /** Collects what matters to track down problems: saved prefs, module log, crashes and LSPosed logs. */
    void diagnose() {
        String cmd = "echo '== prefs =='; ls -l /data/data/com.example.iconmod/shared_prefs; "
                + "cat /data/data/com.example.iconmod/shared_prefs/config.xml; "
                + "echo; echo '== module log (IconMod) =='; logcat -d | grep 'IconMod:' | tail -n 120; "
                + "echo; echo '== crashes =='; logcat -d -b crash | grep -A28 'FATAL EXCEPTION' | tail -n 90; "
                + "echo; echo '== lspd =='; grep -h 'IconMod:' /data/adb/lspd/log/*.log 2>/dev/null | tail -n 80";
        toast("Collecting diagnostics…");
        suOut(cmd, out -> {
            final TextView tv = new TextView(this);
            tv.setText(out.isEmpty() ? "(empty)" : out);
            tv.setTextSize(10);
            tv.setTypeface(android.graphics.Typeface.MONOSPACE);
            tv.setTextIsSelectable(true);
            tv.setPadding(dp(12), dp(8), dp(12), dp(8));
            ScrollView sv = new ScrollView(this);
            sv.addView(tv);
            new AlertDialog.Builder(this).setTitle("Diagnostics").setView(sv)
                    .setPositiveButton("Copy", (d, w) -> {
                        android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("diagnostics", out));
                        toast("Copied");
                    }).setNegativeButton("Close", null).show();
        });
    }

    void syncSamples() {
        String base = getFilesDir().getAbsolutePath();
        int uid = android.os.Process.myUid();
        String cache = "/data/data/" + LAUNCHER + "/cache";
        StringBuilder cmd = new StringBuilder();
        for (String[] m : new String[][]{{"iconmod_orig", "orig"}, {"iconmod_themed", "themed"}}) {
            String dst = base + "/" + m[1];
            cmd.append("mkdir -p ").append(dst).append(" && cp -rf ").append(cache).append('/').append(m[0]).append("/. ").append(dst)
               .append("/ ; chown -R ").append(uid).append(':').append(uid).append(' ').append(dst)
               .append(" ; chmod -R a+rX ").append(dst).append(" ; restorecon -R ").append(dst).append(" ; ");
        }
        cmd.append("echo orig=$(ls ").append(base).append("/orig | wc -l) themed=$(ls ").append(base).append("/themed | wc -l)");
        su(cmd.toString(), () -> { samplesDirty = true; refreshPreview(); loadRows(); });
    }

    /** Current options read from the controls (the preview uses exactly what will be saved). */
    IconOpts opts() {
        IconOpts o = new IconOpts();
        o.corners = swCorners.isChecked();
        o.regen = swRegen.isChecked();
        o.radius = sbR.getProgress();
        o.zoom = sbZ.getProgress() + 100;
        o.size = sbSize.getProgress() + 50;
        o.glass = swGlass.isChecked();
        o.glassMono = swMono.isChecked();
        o.glassPlate = sbPlate.getProgress();
        o.glassEdge = sbEdge.getProgress();
        o.glassBevel = sbBevel.getProgress();
        o.glassLens = sbLens.getProgress();
        o.glassFrost = sbFrost.getProgress();
        o.blurFit = sbFit.getProgress() + 60;
        o.glassGlyph = sbGlyph.getProgress() + 70;
        o.iconAlpha = sbAlpha.getProgress() + 20;
        o.blurRefraction = sbRefr.getProgress();
        o.blurAberration = sbAb.getProgress();
        o.blurFrost = sbBFrost.getProgress();
        o.tintSource = Math.max(0, rgTint.getCheckedRadioButtonId() - 100);
        o.resolveTint(this);
        o.tintHue = sbTHue.getProgress();
        o.tintAmt = sbTAmt.getProgress();
        o.blurSpecular = swSpec.isChecked();
        o.blur = swBlur.isChecked();
        o.blurAmount = sbBlur.getProgress();
        return o;
    }

    // ------------------------------------------------------------ preview
    /** Test background with sharp shapes (so the glass blur is visible). */
    Bitmap pattern(int w, int h, int idx) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(Color.rgb(28, 52, 98));
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Random rnd = new Random(idx * 31L + 7);
        for (int i = 0; i < 16; i++) {
            p.setColor(Color.rgb(70 + rnd.nextInt(185), 70 + rnd.nextInt(185), 70 + rnd.nextInt(185)));
            float x = rnd.nextInt(w), y = rnd.nextInt(h);
            if (i % 2 == 0) c.drawCircle(x, y, dp(5 + rnd.nextInt(14)), p);
            else c.drawRect(x, y, x + dp(14 + rnd.nextInt(26)), y + dp(3 + rnd.nextInt(5)), p);
        }
        return b;
    }

    /** Blurred background (the pattern slice behind the icon) + glass icon on top. The slice blur is cached per intensity. */
    Bitmap withBackdrop(Bitmap res, Sample sm, Rect r, IconOpts o) {
        int S = res.getWidth();
        if (sm.blSmall == null || sm.blAmt != o.blurAmount) {
            Bitmap crop = Bitmap.createBitmap(sm.slice, r.left, r.top, r.width(), r.height());
            int dsw = Math.max(8, r.width() / BackdropBlur.DS);
            Bitmap small = Bitmap.createScaledBitmap(crop, dsw, dsw, true);
            sm.blSmall = BackdropBlur.blur(small, BackdropBlur.radiusFor(o.blurAmount));
            sm.blAmt = o.blurAmount;
        }
        Bitmap big = Bitmap.createScaledBitmap(sm.blSmall, S, S, true);
        Bitmap out = BackdropFx.render(big, o, Math.round(o.size * o.blurFit / 100f), -0.6f, -0.8f);   // same effects as the launcher
        new Canvas(out).drawBitmap(res, 0, 0, null);
        return out;
    }

    Sample newSample(ComponentName cn, Bitmap orig, int idx) {
        Sample sm = new Sample();
        sm.cn = cn; sm.orig = orig;
        sm.slice = pattern(dp(72), dp(140), idx);
        try { sm.appIcon = getPackageManager().getActivityIcon(cn); } catch (Throwable ignored) {}
        return sm;
    }

    /** (render thread) Loads and caches the samples: only re-reads from disk after a sync or toggling "themed". */
    void loadSamples(boolean themed) {
        List<Sample> list = new ArrayList<>();
        int themedUsed = 0;
        File[] fs = new File(getFilesDir(), "orig").listFiles();
        if (fs != null) {
            Arrays.sort(fs);
            for (File f : fs) {
                if (list.size() >= 8) break;
                String nm = f.getName();
                int i = nm.indexOf("__");
                if (!nm.endsWith(".png") || i < 0) continue;
                ComponentName cn = new ComponentName(nm.substring(0, i), nm.substring(i + 2, nm.length() - 4));
                File base = f;
                if (themed) {
                    File t = new File(new File(getFilesDir(), "themed"), nm);
                    if (t.exists()) { base = t; themedUsed++; }
                }
                Bitmap old = BitmapFactory.decodeFile(base.getPath());
                if (old == null) continue;
                list.add(newSample(cn, old, list.size()));
            }
        }
        if (!list.isEmpty()) {
            sampleHint = "Top: One UI Home icon" + (themed ? " (Samsung themed, when a sample exists)" : "")
                    + ". Bottom: hook result with all options." + (themed && themedUsed == 0
                    ? " No themed samples yet: open and close an app, then sync." : "");
        } else {
            sampleHint = "No launcher samples yet. Restart the launcher, open and close an app, then tap “Sync samples”. "
                    + "Meanwhile, showing an approximation using the apps' icons.";
            PackageManager pm = getPackageManager();
            Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
                if (list.size() >= 8) break;
                try {
                    ComponentName cn = new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name);
                    Bitmap old = IconRender.render(pm.getActivityIcon(cn), 192, true, 30);   // "original" aproximado
                    list.add(newSample(cn, old, list.size()));
                } catch (Throwable ignored) {}
            }
        }
        samples = list;
        samplesThemed = themed;
        samplesDirty = false;
    }

    /** Ensures n preview columns (reuses the views; only rebuilds if the sample count changed). */
    void ensureViews(List<Sample> snap) {
        if (origViews.size() == snap.size()) return;
        previewRow.removeAllViews();
        origViews.clear(); resViews.clear();
        for (Sample sm : snap) {
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(dp(4), dp(4), dp(4), dp(4));
            col.setBackground(new BitmapDrawable(getResources(), sm.slice));
            ImageView o = new ImageView(this), r = new ImageView(this);
            col.addView(o, new LinearLayout.LayoutParams(dp(64), dp(64)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(64), dp(64));
            lp.topMargin = dp(4);
            col.addView(r, lp);
            previewRow.addView(col);
            origViews.add(o); resViews.add(r);
        }
    }

    /**
     * Preview: real One UI Home icons (samples recorded by the hook) going through the SAME code as the hook.
     * Runs off the UI thread. While a slider is dragged it renders at low resolution; on release, at full resolution.
     * Each call invalidates the previous ones, so dragging fast doesn't queue up work.
     */
    void refreshPreview() {
        if (previewRow == null) return;
        final IconOpts o = opts();
        final IconPack pk = currentPack();
        final boolean themed = swThemed.isChecked();
        final int px = dragging ? dp(32) : dp(64);
        final int gen = renderGen.incrementAndGet();
        renderEx.execute(() -> {
            if (gen != renderGen.get()) return;
            try {
                if (samplesDirty || themed != samplesThemed) loadSamples(themed);
                final List<Sample> snap = new ArrayList<>(samples);
                final List<Bitmap> origs = new ArrayList<>(), ress = new ArrayList<>();
                for (Sample sm : snap) {
                    if (gen != renderGen.get()) return;
                    Bitmap old = Bitmap.createScaledBitmap(sm.orig, px, px, true);
                    Bitmap res = IconRender.make(this, sm.cn, old, pk, pk != null, o, sm.appIcon);
                    if (res == null) res = old;
                    if (o.blur) {   // blur works with or without liquid glass
                        try { res = withBackdrop(res, sm, new Rect(dp(4), dp(72), dp(68), dp(136)), o); }
                        catch (Throwable ignored) {}
                    }
                    origs.add(old); ress.add(res);
                }
                final String hint = sampleHint;
                runOnUiThread(() -> {
                    if (gen != renderGen.get() || isDestroyed()) return;
                    ensureViews(snap);
                    for (int i = 0; i < ress.size() && i < origViews.size(); i++) {
                        origViews.get(i).setImageBitmap(origs.get(i));
                        resViews.get(i).setImageBitmap(ress.get(i));
                    }
                    previewHint.setText(hint);
                });
            } catch (Throwable t) { android.util.Log.w("IconMod", "preview: " + t); }
        });
    }

    @Override protected void onDestroy() { renderEx.shutdownNow(); super.onDestroy(); }

    // ------------------------------------------------------------ Icons tab
    void loadRows() {
        allRows.clear();
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
            if (getPackageName().equals(ri.activityInfo.packageName)) continue;
            Row r = new Row();
            r.ri = ri;
            r.cn = new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name);
            r.label = ri.loadLabel(pm);
            allRows.add(r);
        }
        Collections.sort(allRows, (a, b) -> a.label.toString().compareToIgnoreCase(b.label.toString()));
        rebuildList();
    }

    void rebuildList() {
        if (rowAdapter == null) return;
        shown.clear();
        IconPack pk = currentPack();
        if (pk == null) {
            listHint.setText("Choose an icon pack in the Settings tab.");
        } else {
            String q = search.getText().toString().trim().toLowerCase();
            int missing = 0;
            for (Row r : allRows) {
                r.status = pk.status(r.cn);
                if (r.status == 1 || r.status == 2) missing++;
                boolean wanted = showAll.isChecked() || r.status != 0;
                if (!wanted) continue;
                if (!q.isEmpty() && !r.label.toString().toLowerCase().contains(q)
                        && !r.cn.getPackageName().toLowerCase().contains(q)) continue;
                shown.add(r);
            }
            listHint.setText(missing + " app(s) without an icon in this pack. Tap an app to pick an icon from the pack.");
        }
        rowAdapter.notifyDataSetChanged();
    }

    void setOverride(ComponentName cn, String drawable) {
        Set<String> cur = new HashSet<>(prefs.getStringSet("overrides", new HashSet<String>()));
        String flat = cn.flattenToString();
        for (Iterator<String> it = cur.iterator(); it.hasNext(); ) if (it.next().startsWith(flat + "|")) it.remove();
        if (drawable != null) cur.add(flat + "|" + drawable);
        prefs.edit().putStringSet("overrides", cur).commit();
        toast(drawable == null ? "Customization removed" : "Saved. Tap “Save and restart launcher” to apply.");
        rebuildList();
        refreshPreview();
    }

    void pickIcon(final Row r) {
        final IconPack pk = currentPack();
        if (pk == null) { toast("Choose an icon pack first"); return; }
        final List<String> all = pk.names();
        final List<String> list = new ArrayList<>();
        LinearLayout lay = new LinearLayout(this);
        lay.setOrientation(LinearLayout.VERTICAL);
        lay.setPadding(dp(12), dp(8), dp(12), 0);
        final EditText q = new EditText(this);
        q.setHint("Search pack icon");
        q.setSingleLine(true);
        lay.addView(q);
        GridView gv = new GridView(this);
        gv.setNumColumns(4);
        lay.addView(gv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(360)));
        final ArrayAdapter<String> ad = new ArrayAdapter<String>(this, 0, list) {
            @Override public View getView(int i, View v, ViewGroup parent) {
                LinearLayout cell = new LinearLayout(MainActivity.this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER_HORIZONTAL);
                cell.setPadding(dp(2), dp(6), dp(2), dp(6));
                String name = getItem(i);
                ImageView iv = new ImageView(MainActivity.this);
                Drawable d = pk.drawable(name);
                if (d != null) iv.setImageDrawable(d);
                cell.addView(iv, new LinearLayout.LayoutParams(dp(48), dp(48)));
                TextView tv = new TextView(MainActivity.this);
                tv.setText(name); tv.setTextSize(9); tv.setSingleLine(true);
                tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
                cell.addView(tv);
                return cell;
            }
        };
        gv.setAdapter(ad);
        final Runnable filter = () -> {
            String s = q.getText().toString().trim().toLowerCase().replace(' ', '_');
            list.clear();
            for (String n : all) {
                if (s.isEmpty() || n.toLowerCase().contains(s)) list.add(n);
                if (list.size() >= 400) break;
            }
            ad.notifyDataSetChanged();
        };
        q.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence a, int b, int c, int d) {}
            public void onTextChanged(CharSequence a, int b, int c, int d) {}
            public void afterTextChanged(Editable e) { filter.run(); }
        });
        String guess = r.label.toString().toLowerCase().split("\\s+")[0];
        q.setText(guess);
        if (list.isEmpty()) q.setText("");     // no match for the app name: show everything
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle(r.label).setView(lay)
                .setNegativeButton("Cancel", null);
        if (r.status == 3) b.setNeutralButton("Remove", (d, w) -> setOverride(r.cn, null));
        final AlertDialog dlg = b.create();
        gv.setOnItemClickListener((p, v, pos, id) -> { setOverride(r.cn, list.get(pos)); dlg.dismiss(); });
        dlg.show();
    }

    void buildIconsTab() {
        iconsView = new LinearLayout(this);
        iconsView.setOrientation(LinearLayout.VERTICAL);
        iconsView.setPadding(dp(12), dp(8), dp(12), 0);
        search = new EditText(this);
        search.setHint("Search app");
        search.setSingleLine(true);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence a, int b, int c, int d) {}
            public void onTextChanged(CharSequence a, int b, int c, int d) {}
            public void afterTextChanged(Editable e) { rebuildList(); }
        });
        showAll = new CheckBox(this);
        showAll.setText("Also show apps that already have an icon");
        showAll.setOnCheckedChangeListener((b, c) -> rebuildList());
        listHint = new TextView(this);
        listHint.setTextColor(cOnSurfaceVariant());
        listHint.setPadding(0, dp(4), 0, dp(8));
        search.setTextColor(cOnSurface());
        search.setHintTextColor(cOnSurfaceVariant());
        showAll.setTextColor(cOnSurface());
        ListView lv = new ListView(this);
        rowAdapter = new BaseAdapter() {
            public int getCount() { return shown.size(); }
            public Object getItem(int i) { return shown.get(i); }
            public long getItemId(int i) { return i; }
            public View getView(int i, View v, ViewGroup parent) {
                Row r = shown.get(i);
                LinearLayout row = new LinearLayout(MainActivity.this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, dp(6), 0, dp(6));
                ImageView iv = new ImageView(MainActivity.this);
                Drawable d = null;
                IconPack pk = currentPack();
                if (r.status == 3 && pk != null) d = pk.get(r.cn);
                if (d == null) d = r.ri.loadIcon(getPackageManager());
                iv.setImageDrawable(d);
                row.addView(iv, new LinearLayout.LayoutParams(dp(44), dp(44)));
                LinearLayout col = new LinearLayout(MainActivity.this);
                col.setOrientation(LinearLayout.VERTICAL);
                col.setPadding(dp(12), 0, 0, 0);
                TextView t1 = new TextView(MainActivity.this);
                t1.setText(r.label); t1.setTextSize(16); t1.setTextColor(cOnSurface());
                TextView t2 = new TextView(MainActivity.this);
                t2.setText(STATUS[r.status] + " · " + r.cn.getPackageName());
                t2.setTextSize(11); t2.setSingleLine(true);
                t2.setTextColor(r.status == 3 ? cPrimary() : (r.status == 0 ? cOnSurfaceVariant() : cTertiary()));
                col.addView(t1); col.addView(t2);
                row.addView(col);
                return row;
            }
        };
        lv.setDivider(null);
        lv.setAdapter(rowAdapter);
        lv.setOnItemClickListener((p, v, pos, id) -> pickIcon(shown.get(pos)));
        iconsView.addView(search); iconsView.addView(showAll); iconsView.addView(listHint);
        iconsView.addView(lv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    // ------------------------------------------------------------ Settings tab
    Switch sw(String text, boolean checked) {
        Switch w = new Switch(this);
        w.setText(text);
        w.setTextColor(cOnSurface());
        w.setTextSize(15);
        w.setPadding(0, dp(8), 0, dp(8));
        int[][] st = {{android.R.attr.state_checked}, {}};
        w.setThumbTintList(new ColorStateList(st, new int[]{cOnPrimary(), cOutline()}));
        w.setTrackTintList(new ColorStateList(st, new int[]{cPrimary(), cSurfaceVariant()}));
        w.setChecked(checked);
        w.setOnCheckedChangeListener((b, c) -> refreshPreview());
        return w;
    }

    void buildConfigTab() {
        configView = new LinearLayout(this);
        configView.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(8), dp(16), dp(16));
        scroll.addView(root);

        previewHint = new TextView(this);
        previewHint.setTextSize(12);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        previewRow = new LinearLayout(this);
        hs.addView(previewRow);
        Button sync = new Button(this);
        sync.setText("Sync launcher samples (root)");
        sync.setOnClickListener(v -> syncSamples());
        Button diag = new Button(this);
        diag.setText("Diagnostics (module log)");
        diag.setOnClickListener(v -> diagnose());
        swThemed = sw("Preview using Samsung's themed icons", prefs.getBoolean("previewThemed", false));

        swCorners = sw("Modify icon corners", prefs.getBoolean("corners", true));
        final TextView tvR = new TextView(this);
        sbR = bar(tvR, "Corner radius: %d%%  (0 = square, 50 = circle)", 0, 50, prefs.getInt("radius", 30));
        final TextView tvS = new TextView(this);
        sbSize = bar(tvS, "Icon size: %d%%", 50, 110, prefs.getInt("size", 100));
        final TextView tvZ = new TextView(this);
        sbZ = bar(tvZ, "Original icon zoom: %d%%  (fills the corners; 100 = no zoom)", 100, 140, prefs.getInt("zoom", 115));
        swRegen = sw("Regenerate from app (full control, loses Samsung theming)", prefs.getBoolean("regen", false));

        swGlass = sw("Liquid glass (translucent plate + edge highlight; uses the app icon)", prefs.getBoolean("glass", false));
        final TextView tvP = new TextView(this);
        sbPlate = bar(tvP, "Plate opacity: %d%%  (0 = transparent background)", 0, 100, prefs.getInt("glassPlate", 22));
        final TextView tvE = new TextView(this);
        sbEdge = bar(tvE, "Light intensity (bevel, glow, reflection): %d%%", 0, 100, prefs.getInt("glassEdge", 80));
        final TextView tvB = new TextView(this);
        sbBevel = bar(tvB, "Bevel thickness: %d%%", 0, 100, prefs.getInt("glassBevel", 55));
        final TextView tvL = new TextView(this);
        sbLens = bar(tvL, "Refraction (center lens): %d%%", 0, 100, prefs.getInt("glassLens", 35));
        final TextView tvF = new TextView(this);
        sbFrost = bar(tvF, "Frost (white veil on the body): %d%%", 0, 100, prefs.getInt("glassFrost", 35));
        swBlur = sw("Background blur (blurred wallpaper behind the icon; works without liquid glass)", prefs.getBoolean("blur", false));
        final TextView tvBl = new TextView(this);
        sbBlur = bar(tvBl, "Blur intensity: %d%%", 0, 100, prefs.getInt("blurAmount", 50));
        final TextView tvFit = new TextView(this);
        sbFit = bar(tvFit, "Blur plate size adjustment: %d%%  (100 = same as icon)", 60, 110, prefs.getInt("blurFit", 100));
        final TextView tvGl = new TextView(this);
        sbGlyph = bar(tvGl, "Glyph size: %d%%  (liquid glass; raise it if the glass icons look small)", 70, 160, prefs.getInt("glassGlyph", 100));
        final TextView tvA = new TextView(this);
        sbAlpha = bar(tvA, "Icon opacity: %d%%  (without liquid glass; lower it so the blur shows through)", 20, 100, prefs.getInt("iconAlpha", 100));
        final TextView tvRefr = new TextView(this);
        sbRefr = bar(tvRefr, "Refraction: %d%%  (how much light bends at the rim: subtle sheen to deep glassy pull)", 0, 100, prefs.getInt("blurRefraction", 40));
        final TextView tvAb = new TextView(this);
        sbAb = bar(tvAb, "Aberration: %d%%  (colour split at the lens edge: clinical to cinematic)", 0, 100, prefs.getInt("blurAberration", 20));
        final TextView tvBFr = new TextView(this);
        sbBFrost = bar(tvBFr, "Frost: %d%%  (diffuses the scene behind the glass)", 0, 100, prefs.getInt("blurFrost", 20));
        final TextView tvTint = header("Tint colour");
        rgTint = new RadioGroup(this);
        String[] tintNames = {"Custom hue", "Material You: primary", "Material You: secondary", "Material You: tertiary"};
        int[] accentIds = {0, android.R.color.system_accent1_300, android.R.color.system_accent2_300, android.R.color.system_accent3_300};
        for (int i = 0; i < 4; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setId(100 + i);
            rb.setText(tintNames[i]);
            rb.setTextColor(cOnSurface());
            rb.setButtonTintList(android.content.res.ColorStateList.valueOf(cPrimary()));
            if (i > 0 && android.os.Build.VERSION.SDK_INT >= 31) {      // swatch with the real system colour
                android.graphics.drawable.GradientDrawable sw = new android.graphics.drawable.GradientDrawable();
                sw.setColor(getColor(accentIds[i]));
                sw.setCornerRadius(dp(8));
                sw.setSize(dp(28), dp(28));
                rb.setCompoundDrawablesWithIntrinsicBounds(null, null, sw, null);
                rb.setCompoundDrawablePadding(dp(12));
            }
            rgTint.addView(rb);
        }
        rgTint.check(100 + Math.max(0, Math.min(3, prefs.getInt("tintSource", 0))));
        rgTint.setOnCheckedChangeListener((g, id) -> refreshPreview());
        final TextView tvTH = new TextView(this);
        sbTHue = bar(tvTH, "Custom tint hue: %d°", 0, 360, prefs.getInt("tintHue", 210));
        final TextView tvTA = new TextView(this);
        sbTAmt = bar(tvTA, "Tint strength: %d%%  (0 = no tint)", 0, 100, prefs.getInt("tintAmt", 0));
        swSpec = sw("Specular highlight (follows the light as the icon moves across the screen)", prefs.getBoolean("blurSpecular", true));
        swMono = sw("Monochrome white glyph (the app's themed icon, if any)", prefs.getBoolean("glassMono", false));

        pick = new Button(this);
        pick.setText("Icon pack: " + chosenLabel);
        pick.setOnClickListener(v -> showPackPicker());

        styleButton(sync, cPrimaryContainer(), cOnPrimaryContainer());
        styleButton(diag, cPrimaryContainer(), cOnPrimaryContainer());
        styleButton(pick, cPrimaryContainer(), cOnPrimaryContainer());
        previewHint.setTextColor(cOnSurfaceVariant());
        previewHint.setPadding(0, 0, 0, dp(8));
        hs.setHorizontalScrollBarEnabled(false);

        // preview pinned at the top: it stays visible while the controls scroll
        previewHint.setMaxLines(2);
        previewHint.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout cPrev = card();
        cPrev.addView(header("Preview"));
        cPrev.addView(previewHint); cPrev.addView(hs);
        LinearLayout.LayoutParams pinned = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pinned.setMargins(dp(16), dp(8), dp(16), dp(4));
        configView.addView(cPrev, pinned);
        configView.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout cSamples = card();
        cSamples.addView(header("Launcher samples"));
        cSamples.addView(swThemed);
        cSamples.addView(sync, btnLp()); cSamples.addView(diag, btnLp());
        addCard(root, cSamples);

        LinearLayout cPack = card();
        cPack.addView(header("Icon pack"));
        cPack.addView(pick);
        addCard(root, cPack);

        LinearLayout cShape = card();
        cShape.addView(header("Shape"));
        cShape.addView(swCorners); cShape.addView(tvR); cShape.addView(sbR);
        cShape.addView(tvS); cShape.addView(sbSize);
        cShape.addView(tvZ); cShape.addView(sbZ); cShape.addView(swRegen);
        addCard(root, cShape);

        LinearLayout cGlass = card();
        cGlass.addView(header("Liquid glass"));
        cGlass.addView(swGlass);
        cGlass.addView(tvGl); cGlass.addView(sbGlyph);
        cGlass.addView(tvP); cGlass.addView(sbPlate);
        cGlass.addView(tvE); cGlass.addView(sbEdge);
        cGlass.addView(tvB); cGlass.addView(sbBevel);
        cGlass.addView(tvL); cGlass.addView(sbLens);
        cGlass.addView(tvF); cGlass.addView(sbFrost);
        cGlass.addView(swMono);
        addCard(root, cGlass);

        LinearLayout cBlur = card();
        cBlur.addView(header("Background blur"));
        cBlur.addView(swBlur); cBlur.addView(tvBl); cBlur.addView(sbBlur);
        cBlur.addView(tvFit); cBlur.addView(sbFit);
        cBlur.addView(tvA); cBlur.addView(sbAlpha);
        cBlur.addView(tvRefr); cBlur.addView(sbRefr);
        cBlur.addView(tvAb); cBlur.addView(sbAb);
        cBlur.addView(tvBFr); cBlur.addView(sbBFrost);
        cBlur.addView(tvTint); cBlur.addView(rgTint);
        cBlur.addView(tvTH); cBlur.addView(sbTHue);
        cBlur.addView(tvTA); cBlur.addView(sbTAmt);
        cBlur.addView(swSpec);
        addCard(root, cBlur);

        LinearLayout cAbout = card();
        cAbout.addView(header("About"));
        TextView about = new TextView(this);
        about.setText("IconMod  v" + BuildConfig.VERSION_NAME + "\nDeveloped with Claude (Anthropic)\nCreator: @s20fhype (TikTok)");
        about.setTextColor(cOnSurface());
        about.setTextSize(14);
        about.setLineSpacing(dp(2), 1f);
        cAbout.addView(about);
        Button rep = new Button(this);
        rep.setText("Repair config files (root)");
        styleButton(rep, cPrimaryContainer(), cOnPrimaryContainer());
        rep.setOnClickListener(v -> repairConfig());
        cAbout.addView(rep, btnLp());
        Button gh = new Button(this);
        gh.setText("GitHub: ELPEDR/IconMod");
        styleButton(gh, cPrimaryContainer(), cOnPrimaryContainer());
        gh.setOnClickListener(v -> openUrl("https://github.com/ELPEDR/IconMod/tree/main"));
        cAbout.addView(gh, btnLp());
        Button tt = new Button(this);
        tt.setText("TikTok: @s20fhype");
        styleButton(tt, cPrimaryContainer(), cOnPrimaryContainer());
        tt.setOnClickListener(v -> openUrl("https://www.tiktok.com/@s20fhype"));
        cAbout.addView(tt, btnLp());
        addCard(root, cAbout);
    }

    /** Writes all options to the module's shared prefs. Returns false if the file couldn't be written (e.g. root-owned). */
    boolean writePrefs(IconOpts o) {
        return prefs.edit().putBoolean("corners", o.corners).putInt("radius", o.radius).putInt("zoom", o.zoom)
                .putInt("size", o.size).putBoolean("regen", o.regen).putBoolean("glass", o.glass).putBoolean("blur", o.blur).putInt("blurAmount", o.blurAmount)
                .putBoolean("glassMono", o.glassMono).putInt("glassPlate", o.glassPlate).putInt("glassEdge", o.glassEdge).putInt("glassBevel", o.glassBevel)
                .putInt("glassLens", o.glassLens).putInt("glassFrost", o.glassFrost).putInt("blurFit", o.blurFit).putInt("glassGlyph", o.glassGlyph).putInt("iconAlpha", o.iconAlpha)
                .putInt("blurRefraction", o.blurRefraction).putInt("blurAberration", o.blurAberration).putInt("blurFrost", o.blurFrost)
                .putInt("tintHue", o.tintHue).putInt("tintSource", o.tintSource).putInt("tintAmt", o.tintAmt).putBoolean("blurSpecular", o.blurSpecular)
                .putBoolean("previewThemed", swThemed.isChecked())
                .putString("pack", chosenPack).commit();
    }

    static final String SELF = "/data/data/com.example.iconmod";

    /** Gives the module's config files back to the app (a root-edited / root-created file can't be rewritten by the app). */
    String repairCmd() {
        int uid = android.os.Process.myUid();
        return "chown -R " + uid + ":" + uid + " " + SELF + "/shared_prefs 2>/dev/null; chmod 755 " + SELF + "/shared_prefs; "
                + "chmod 644 " + SELF + "/shared_prefs/*.xml 2>/dev/null; rm -f " + SELF + "/shared_prefs/config.xml.bak; "
                + "chmod a+rx " + SELF + "; restorecon -R " + SELF + "/shared_prefs 2>/dev/null";
    }

    /** Copies the config into the launcher's own data dir (the hook reads it from there) and optionally restarts the launcher. */
    void pushToLauncher(boolean restart) {
        try {
            File tmp = new File(getFilesDir(), "iconmod_config.txt");
            try (java.io.OutputStreamWriter w = new java.io.OutputStreamWriter(new java.io.FileOutputStream(tmp), "UTF-8")) {
                w.write(FilePrefs.serialize(prefs.getAll()));
            }
            String L = "/data/data/" + LAUNCHER;
            String cmd = repairCmd() + "; U=$(stat -c %u " + L + "); mkdir -p " + L + "/files; "
                    + "cp -f " + tmp.getAbsolutePath() + " " + L + "/files/iconmod_config.txt && chown $U:$U " + L + "/files/iconmod_config.txt "
                    + "&& chmod 644 " + L + "/files/iconmod_config.txt && restorecon " + L + "/files/iconmod_config.txt 2>/dev/null; "
                    + (restart ? "am force-stop " + LAUNCHER : "true");
            su(cmd, () -> toast(restart ? "Saved. Launcher restarted." : "Saved."));
        } catch (Throwable t) { toast("Could not write the config: " + t); }
    }

    void saveAndRestart() {
        final IconOpts o = opts();
        if (writePrefs(o)) { pushToLauncher(true); return; }
        // the config.xml is not writable (typically root-owned after a manual edit): fix ownership and retry once
        toast("config.xml was not writable. Repairing it and retrying…");
        su(repairCmd(), () -> {
            if (!writePrefs(o)) toast("Still can't write config.xml. The launcher will still get the settings through its own file.");
            pushToLauncher(true);
        });
    }

    void repairConfig() {
        su(repairCmd(), () -> {
            toast("Config files repaired. Reopen the app.");
            finishAffinity();
            android.os.Process.killProcess(android.os.Process.myPid());     // drop the cached (stale) SharedPreferences
        });
    }

    void showTab(int i) {
        configView.setVisibility(i == 0 ? View.VISIBLE : View.GONE);
        iconsView.setVisibility(i == 1 ? View.VISIBLE : View.GONE);
        styleTab(tab0, i == 0);
        styleTab(tab1, i == 1);
        if (i == 1) loadRows();
    }

    @SuppressWarnings("deprecation")
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        SharedPreferences sp;
        try { sp = getSharedPreferences("config", MODE_WORLD_READABLE); }
        catch (SecurityException e) {
            sp = getSharedPreferences("config", MODE_PRIVATE);
            toast("Enable the module in LSPosed and reopen the app");
        }
        prefs = sp;
        chosenPack = prefs.getString("pack", "");
        if (!chosenPack.isEmpty()) {
            try { chosenLabel = getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(chosenPack, 0)); }
            catch (Exception e) { chosenLabel = chosenPack; }
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(cSurface());

        TextView title = new TextView(this);
        title.setText("IconMod");
        title.setTextSize(28);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setTextColor(cOnSurface());
        title.setPadding(dp(24), dp(20), dp(24), dp(8));

        LinearLayout tabs = new LinearLayout(this);
        tabs.setPadding(dp(16), dp(4), dp(16), dp(8));
        tab0 = new Button(this); tab0.setText("Settings");
        tab1 = new Button(this); tab1.setText("Pack icons");
        LinearLayout.LayoutParams l0 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        l0.setMargins(dp(4), 0, dp(4), 0); l1.setMargins(dp(4), 0, dp(4), 0);
        tabs.addView(tab0, l0);
        tabs.addView(tab1, l1);
        tab0.setOnClickListener(v -> showTab(0));
        tab1.setOnClickListener(v -> showTab(1));

        buildConfigTab();
        buildIconsTab();
        FrameLayout content = new FrameLayout(this);
        content.addView(configView);
        content.addView(iconsView);

        Button save = new Button(this);
        save.setText("Save and restart launcher");
        styleButton(save, cPrimary(), cOnPrimary());
        save.setOnClickListener(v -> saveAndRestart());
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sl.setMargins(dp(16), dp(8), dp(16), dp(12));

        root.addView(title);
        root.addView(tabs);
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(save, sl);
        setContentView(root);
        showTab(0);
        refreshPreview();
    }
}
