package de.semkoq.trainingsapp;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.PowerManager;
import android.provider.Settings;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@SuppressLint("MissingPermission")
@CapacitorPlugin(name = "HrNative", permissions = {
        @Permission(alias = "ble", strings = { Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT }),
        @Permission(alias = "loc", strings = { Manifest.permission.ACCESS_FINE_LOCATION }),
        @Permission(alias = "notif", strings = { Manifest.permission.POST_NOTIFICATIONS })
})
public class HrPlugin extends Plugin {
    private final Handler h = new Handler(Looper.getMainLooper());
    private String lastUrl = null;

    @Override public void load() {
        TrainingService.listener = (name, data) -> {
            JSObject o = new JSObject();
            for (Map.Entry<String, Object> e : data.entrySet()) o.put(e.getKey(), e.getValue());
            notifyListeners(name, o);
        };
        Intent i = getActivity().getIntent();
        if (i != null && i.getData() != null) lastUrl = i.getData().toString();
    }

    @Override protected void handleOnNewIntent(Intent intent) {
        if (intent != null && intent.getData() != null) {
            lastUrl = intent.getData().toString();
            JSObject o = new JSObject(); o.put("url", lastUrl);
            notifyListeners("url", o, true);
        }
    }

    private String[] neededAliases() {
        List<String> a = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) a.add("ble"); else a.add("loc");
        if (Build.VERSION.SDK_INT >= 33) a.add("notif");
        return a.toArray(new String[0]);
    }
    private boolean granted() {
        for (String a : neededAliases()) if (!"notif".equals(a) && getPermissionState(a) != PermissionState.GRANTED) return false;
        return true;
    }

    @PluginMethod public void info(PluginCall call) {
        JSObject r = new JSObject();
        try {
            PackageInfo pi = getContext().getPackageManager().getPackageInfo(getContext().getPackageName(), 0);
            r.put("versionName", pi.versionName);
            r.put("versionCode", Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode);
        } catch (Exception e) { r.put("versionCode", 0); }
        r.put("sdk", Build.VERSION.SDK_INT);
        r.put("permissions", granted());
        PowerManager pm = (PowerManager) getContext().getSystemService(Context.POWER_SERVICE);
        r.put("batteryUnrestricted", Build.VERSION.SDK_INT < 23 || pm.isIgnoringBatteryOptimizations(getContext().getPackageName()));
        TrainingService s = TrainingService.instance;
        r.put("connected", s != null && s.connected);
        r.put("recording", s != null && s.recording);
        r.put("name", s != null ? s.getDeviceName() : null);
        r.put("battery", s != null ? s.battery : -1);
        r.put("url", lastUrl);
        call.resolve(r);
    }

    @PluginMethod public void requestPerms(PluginCall call) {
        if (granted() && (Build.VERSION.SDK_INT < 33 || getPermissionState("notif") == PermissionState.GRANTED)) { JSObject r = new JSObject(); r.put("granted", true); call.resolve(r); return; }
        requestPermissionForAliases(neededAliases(), call, "permsCb");
    }
    @PermissionCallback private void permsCb(PluginCall call) { JSObject r = new JSObject(); r.put("granted", granted()); call.resolve(r); }

    @PluginMethod public void scan(PluginCall call) {
        if (!granted()) { call.reject("Keine Bluetooth-Berechtigung"); return; }
        BluetoothManager bm = (BluetoothManager) getContext().getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter ad = bm.getAdapter();
        if (ad == null || !ad.isEnabled()) { call.reject("Bluetooth ist aus"); return; }
        BluetoothLeScanner sc = ad.getBluetoothLeScanner();
        final Map<String, JSObject> found = new LinkedHashMap<>();
        final ScanCallback cb = new ScanCallback() {
            @Override public void onScanResult(int type, ScanResult res) {
                String a = res.getDevice().getAddress();
                if (found.containsKey(a)) return;
                JSObject o = new JSObject();
                String n = res.getScanRecord() != null ? res.getScanRecord().getDeviceName() : null;
                if (n == null) n = res.getDevice().getName();
                o.put("id", a); o.put("name", n == null ? "Brustgurt" : n); o.put("rssi", res.getRssi());
                found.put(a, o);
            }
        };
        List<ScanFilter> f = new ArrayList<>();
        f.add(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(TrainingService.HR_SERVICE)).build());
        ScanSettings st = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        sc.startScan(f, st, cb);
        int ms = call.getInt("ms", 4000);
        h.postDelayed(() -> {
            try { sc.stopScan(cb); } catch (Exception ignored) {}
            JSArray arr = new JSArray(); for (JSObject o : found.values()) arr.put(o);
            JSObject r = new JSObject(); r.put("devices", arr); call.resolve(r);
        }, ms);
    }

    @PluginMethod public void connect(PluginCall call) {
        String id = call.getString("id");
        if (id == null) { call.reject("id fehlt"); return; }
        TrainingService s = TrainingService.instance;
        if (s != null) s.connect(id); else TrainingService.start(getContext(), id);
        call.resolve();
    }
    @PluginMethod public void disconnect(PluginCall call) { TrainingService s = TrainingService.instance; if (s != null) s.disconnect(); call.resolve(); }

    @PluginMethod public void start(PluginCall call) {
        TrainingService s = TrainingService.instance;
        if (s == null) { call.reject("Kein Gurt verbunden"); return; }
        s.startRecording(call.getString("type", "Training")); call.resolve();
    }
    @PluginMethod public void pause(PluginCall call) { TrainingService s = TrainingService.instance; if (s != null) s.setPaused(call.getBoolean("paused", true)); call.resolve(); }

    private JSObject dump(TrainingService s) {
        JSObject r = new JSObject();
        JSArray smp = new JSArray(), rr = new JSArray(), rrT = new JSArray(), rnd = new JSArray();
        synchronized (s) {
            for (int[] x : s.samples) { JSObject o = new JSObject(); o.put("t", x[0]); o.put("hr", x[1]); smp.put(o); }
            for (Integer v : s.rr) rr.put(v);
            for (Integer v : s.rrT) rrT.put(v);
            for (int[] x : s.rounds) { JSObject o = new JSObject(); o.put("round", x[0]); o.put("phase", x[1] == 0 ? "work" : "rest"); o.put("start", x[2]); o.put("end", x[3]); rnd.put(o); }
        }
        r.put("samples", smp); r.put("rr", rr); r.put("rrT", rrT); r.put("rounds", rnd);
        r.put("startedAt", s.startedAtWall); r.put("sec", s.sec()); r.put("recording", s.recording); r.put("paused", s.paused);
        return r;
    }
    @PluginMethod public void snapshot(PluginCall call) { TrainingService s = TrainingService.instance; if (s == null) { call.resolve(new JSObject()); return; } call.resolve(dump(s)); }
    @PluginMethod public void stop(PluginCall call) {
        TrainingService s = TrainingService.instance;
        if (s == null) { call.reject("Keine Aufzeichnung"); return; }
        s.stopRecording(); call.resolve(dump(s));
    }

    @PluginMethod public void ivStart(PluginCall call) { TrainingService s = TrainingService.instance; if (s == null) { call.reject("Dienst läuft nicht"); return; } s.startIntervals(call.getInt("rounds", 5), call.getInt("work", 120), call.getInt("rest", 60)); call.resolve(); }
    @PluginMethod public void ivPause(PluginCall call) { TrainingService s = TrainingService.instance; if (s != null) s.ivSetPaused(call.getBoolean("paused", true)); call.resolve(); }
    @PluginMethod public void ivSkip(PluginCall call) { TrainingService s = TrainingService.instance; if (s != null && s.ivOn) s.ivNext(); call.resolve(); }
    @PluginMethod public void ivStop(PluginCall call) { TrainingService s = TrainingService.instance; if (s != null) s.stopIntervals(); call.resolve(); }

    @PluginMethod public void openUrl(PluginCall call) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(call.getString("url")));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(i); call.resolve();
    }
    // ---- Supplement-Erinnerungen ----
    @PluginMethod public void suppSchedule(PluginCall call) {
        JSArray items = call.getArray("items");
        SuppAlarm.save(getContext(), items == null ? "[]" : items.toString());
        SuppAlarm.scheduleAll(getContext());
        call.resolve();
    }
    @PluginMethod public void suppTaken(PluginCall call) {
        JSObject r = new JSObject();
        try { r.put("list", new JSArray(SuppAlarm.takeTaken(getContext()))); } catch (Exception e) { r.put("list", new JSArray()); }
        call.resolve(r);
    }
    @PluginMethod public void notifPerm(PluginCall call) {
        if (Build.VERSION.SDK_INT < 33 || getPermissionState("notif") == PermissionState.GRANTED) { JSObject r = new JSObject(); r.put("granted", true); call.resolve(r); return; }
        requestPermissionForAlias("notif", call, "notifCb");
    }
    @PermissionCallback private void notifCb(PluginCall call) { JSObject r = new JSObject(); r.put("granted", Build.VERSION.SDK_INT < 33 || getPermissionState("notif") == PermissionState.GRANTED); call.resolve(r); }

    @SuppressLint("BatteryLife")
    @PluginMethod public void batterySettings(PluginCall call) {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getContext().getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); getContext().startActivity(i);
        } catch (Exception e) {
            Intent i = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS); i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); getContext().startActivity(i);
        }
        call.resolve();
    }
}
