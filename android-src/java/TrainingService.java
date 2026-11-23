package de.semkoq.trainingsapp;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.tts.TextToSpeech;

import androidx.core.app.NotificationCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;

/**
 * Vordergrund-Dienst: hält die Bluetooth-Verbindung zum Brustgurt, zeichnet Puls + RR auf
 * und spielt den Intervall-Timer – auch bei gesperrtem Bildschirm.
 */
@SuppressLint("MissingPermission")
public class TrainingService extends Service {
    public static final UUID HR_SERVICE = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb");
    public static final UUID HR_MEAS = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb");
    public static final UUID BAT_SERVICE = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb");
    public static final UUID BAT_LEVEL = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");
    public static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final String CH = "training";
    private static final int NID = 42;

    public interface Listener { void onEvent(String name, java.util.Map<String, Object> data); }

    public static TrainingService instance;
    public static Listener listener;
    private static String pendingAddress;

    private final Handler h = new Handler(Looper.getMainLooper());
    private BluetoothGatt gatt;
    private String address, deviceName;
    private boolean wantConnected = false;
    public volatile boolean connected = false;
    public volatile int hr = 0, battery = -1;
    private long hrAt = 0;

    // Aufzeichnung
    public volatile boolean recording = false, paused = false;
    private long activeMs = 0, segStart = 0;
    public long startedAtWall = 0;
    public final List<int[]> samples = new ArrayList<>();      // {sec, hr}
    public final List<Integer> rr = new ArrayList<>(), rrT = new ArrayList<>();
    public final List<int[]> rounds = new ArrayList<>();       // {round, phase(0=work,1=rest), start, end}
    private PowerManager.WakeLock wake;
    private String type = "Training";

    // Intervalle
    public volatile boolean ivOn = false, ivPaused = false;
    public int ivRounds, ivWork, ivRest, ivRound = 1, ivPhase = 0; // 0 work, 1 rest
    private long ivEnd = 0, ivLeftPaused = 0;
    private int ivLastSec = -1, ivPhaseStartSec = -1;
    // Kombi-Ansagen (Sprachausgabe während der Belastung)
    private TextToSpeech tts;
    private boolean ttsOk = false;
    private String[] ivCombos = new String[0];
    private int ivEvery = 0;
    private double ivNextCombo = 2;
    public String ivCombo = "";
    private final Random rnd = new Random();
    private ToneGenerator tone;

    public static void start(Context c, String addr) {
        pendingAddress = addr;
        Intent i = new Intent(c, TrainingService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CH, "Training", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
        try { tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 100); } catch (Exception e) { tone = null; }
        try { tts = new TextToSpeech(this, st -> { if (st == TextToSpeech.SUCCESS && tts != null) { tts.setLanguage(Locale.GERMAN); tts.setSpeechRate(1.15f); ttsOk = true; } }); } catch (Exception e) { tts = null; }
        h.post(tick);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = buildNotif("Brustgurt wird verbunden …");
        if (Build.VERSION.SDK_INT >= 29) startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        else startForeground(NID, n);
        if (pendingAddress != null) { connect(pendingAddress); pendingAddress = null; }
        return START_STICKY;
    }

    private Notification buildNotif(String text) {
        Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CH)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentTitle(recording ? type + (paused ? " · Pause" : "") : "Trainings-App")
                .setContentText(text)
                .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
                .setContentIntent(pi)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .build();
    }

    private void updateNotif() {
        String t;
        if (!connected) t = wantConnected ? "Verbindung weg – verbinde neu …" : "Getrennt";
        else if (recording) t = fmt(sec()) + " · " + (fresh() ? hr + " bpm" : "-- bpm")
                + (ivOn ? " · " + (ivPhase == 0 ? "Runde " + ivRound + "/" + ivRounds : "Pause") + " " + fmt((int) Math.ceil(ivLeftMs() / 1000.0)) : "");
        else t = "Verbunden · " + (fresh() ? hr + " bpm" : "warte auf Puls");
        try { getSystemService(NotificationManager.class).notify(NID, buildNotif(t)); } catch (Exception ignored) {}
    }

    private static String fmt(int s) { int m = s / 60; return (m >= 60 ? (m / 60) + ":" + String.format("%02d", m % 60) : String.valueOf(m)) + ":" + String.format("%02d", s % 60); }
    private boolean fresh() { return hr > 0 && SystemClock.elapsedRealtime() - hrAt < 5000; }
    public int sec() { return (int) ((activeMs + (recording && !paused ? SystemClock.elapsedRealtime() - segStart : 0)) / 1000); }

    // ---------------- Bluetooth ----------------
    public void connect(String addr) {
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter ad = bm.getAdapter();
        if (ad == null) return;
        address = addr; wantConnected = true;
        BluetoothDevice dev = ad.getRemoteDevice(addr);
        deviceName = dev.getName();
        if (gatt != null) { try { gatt.close(); } catch (Exception ignored) {} }
        gatt = dev.connectGatt(this, false, cb, BluetoothDevice.TRANSPORT_LE);
    }

    public void disconnect() {
        wantConnected = false;
        if (gatt != null) { try { gatt.disconnect(); gatt.close(); } catch (Exception ignored) {} gatt = null; }
        connected = false; hr = 0;
        emitState();
        if (!recording && !ivOn) { stopForeground(true); stopSelf(); }
    }

    private final BluetoothGattCallback cb = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int state) {
            if (state == BluetoothProfile.STATE_CONNECTED) { h.post(() -> g.discoverServices()); }
            else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                connected = false; emitState();
                if (wantConnected) h.postDelayed(() -> { if (wantConnected && !connected && address != null) connect(address); }, 2000);
            }
        }
        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            BluetoothGattService s = g.getService(HR_SERVICE);
            if (s == null) return;
            BluetoothGattCharacteristic c = s.getCharacteristic(HR_MEAS);
            if (c == null) return;
            g.setCharacteristicNotification(c, true);
            BluetoothGattDescriptor d = c.getDescriptor(CCCD);
            if (d != null) {
                if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                else { d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE); g.writeDescriptor(d); }
            }
            connected = true; emitState();
        }
        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            BluetoothGattService b = g.getService(BAT_SERVICE);
            if (b != null && b.getCharacteristic(BAT_LEVEL) != null) g.readCharacteristic(b.getCharacteristic(BAT_LEVEL));
        }
        @Override public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] v, int status) { onBat(c.getUuid(), v); }
        @SuppressWarnings("deprecation")
        @Override public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int status) { if (Build.VERSION.SDK_INT < 33) onBat(c.getUuid(), c.getValue()); }
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] v) { onHr(v); }
        @SuppressWarnings("deprecation")
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) { if (Build.VERSION.SDK_INT < 33) onHr(c.getValue()); }
    };

    private void onBat(UUID u, byte[] v) { if (BAT_LEVEL.equals(u) && v != null && v.length > 0) { battery = v[0] & 0xff; emitState(); } }

    private void onHr(byte[] v) {
        if (v == null || v.length < 2) return;
        int flags = v[0] & 0xff, o = 1, val;
        if ((flags & 1) != 0) { if (v.length < 3) return; val = (v[1] & 0xff) | ((v[2] & 0xff) << 8); o = 3; } else { val = v[1] & 0xff; o = 2; }
        if ((flags & 0x08) != 0) o += 2;
        if (val < 25 || val > 240) return;
        hr = val; hrAt = SystemClock.elapsedRealtime();
        if ((flags & 0x10) != 0 && recording && !paused) {
            int s = sec();
            synchronized (this) {
                for (; o + 1 < v.length; o += 2) {
                    int raw = (v[o] & 0xff) | ((v[o + 1] & 0xff) << 8);
                    int ms = Math.round(raw / 1024f * 1000f);
                    if (ms > 250 && ms < 2500) { rr.add(ms); rrT.add(s); }
                }
            }
        }
    }

    // ---------------- Aufzeichnung ----------------
    public synchronized void startRecording(String t) {
        type = t == null ? "Training" : t;
        samples.clear(); rr.clear(); rrT.clear(); rounds.clear();
        activeMs = 0; segStart = SystemClock.elapsedRealtime(); startedAtWall = System.currentTimeMillis();
        recording = true; paused = false;
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "trainingsapp:rec");
        wake.acquire(6 * 60 * 60 * 1000L);
        updateNotif();
    }
    public synchronized void setPaused(boolean p) {
        if (!recording || p == paused) return;
        if (p) { activeMs += SystemClock.elapsedRealtime() - segStart; paused = true; }
        else { segStart = SystemClock.elapsedRealtime(); paused = false; }
        updateNotif();
    }
    public synchronized void stopRecording() {
        if (!recording) return;
        if (ivOn) stopIntervals();
        if (!paused) activeMs += SystemClock.elapsedRealtime() - segStart;
        recording = false; paused = false;
        if (wake != null && wake.isHeld()) wake.release();
        updateNotif();
    }

    private int lastSampleSec = -1;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            long now = SystemClock.elapsedRealtime();
            if (recording && !paused) {
                int s = sec();
                if (s != lastSampleSec && fresh()) { synchronized (TrainingService.this) { samples.add(new int[]{s, hr}); } lastSampleSec = s; }
            }
            if (ivOn) ivTick();
            java.util.Map<String, Object> m = new java.util.HashMap<>();
            m.put("hr", fresh() ? hr : 0); m.put("connected", connected); m.put("sec", sec()); m.put("recording", recording); m.put("paused", paused);
            if (listener != null) listener.onEvent("hr", m);
            if ((now / 1000) % 5 == 0) updateNotif();
            h.postDelayed(this, ivOn ? 100 : 1000 - (now % 1000));
        }
    };

    // ---------------- Intervalle ----------------
    public long ivLeftMs() { return ivPaused ? ivLeftPaused : Math.max(0, ivEnd - SystemClock.elapsedRealtime()); }
    public void startIntervals(int rounds, int work, int rest, String[] combos, int every) {
        ivRounds = rounds; ivWork = work; ivRest = rest; ivOn = true; ivPaused = false;
        ivCombos = combos == null ? new String[0] : combos; ivEvery = every;
        beep(1, 450); phase(0, 1);
    }
    public boolean say(String t) {
        if (tts == null || !ttsOk || t == null) return false;
        try { tts.speak(t.replace("-", ", "), TextToSpeech.QUEUE_FLUSH, null, "c" + SystemClock.elapsedRealtime()); return true; } catch (Exception e) { return false; }
    }
    private void phase(int ph, int round) {
        ivPhase = ph; ivRound = round; ivCombo = ""; ivNextCombo = 2;
        ivEnd = SystemClock.elapsedRealtime() + (ph == 0 ? ivWork : ivRest) * 1000L;
        ivLastSec = -1; ivPhaseStartSec = recording ? sec() : -1;
        emitIv();
    }
    private void logPhase() {
        if (recording && ivPhaseStartSec >= 0) {
            int end = sec();
            if (end - ivPhaseStartSec >= 3) synchronized (this) { rounds.add(new int[]{ivRound, ivPhase, ivPhaseStartSec, end}); }
        }
    }
    public void ivNext() {
        logPhase();
        if (ivPhase == 0 && ivRound < ivRounds && ivRest > 0) phase(1, ivRound);
        else if (ivPhase == 0 && ivRound < ivRounds) phase(0, ivRound + 1);
        else if (ivPhase == 1) phase(0, ivRound + 1);
        else { ivOn = false; emitIv(); updateNotif(); }
    }
    public void ivSetPaused(boolean p) {
        if (!ivOn || p == ivPaused) return;
        if (p) { ivLeftPaused = ivLeftMs(); ivPaused = true; }
        else { ivEnd = SystemClock.elapsedRealtime() + ivLeftPaused; ivPaused = false; ivLastSec = (int) Math.ceil(ivLeftPaused / 1000.0); }
        emitIv();
    }
    public void stopIntervals() { if (ivOn) logPhase(); ivOn = false; ivPaused = false; emitIv(); }
    private void ivTick() {
        if (ivPaused) { emitIv(); return; }
        long left = ivLeftMs();
        int s = (int) Math.ceil(left / 1000.0);
        int dur = ivPhase == 0 ? ivWork : ivRest;
        if (s != ivLastSec) {
            if (ivLastSec >= 0) {
                if (s == 10 && dur > 15) beep(2, 120);
                else if (s >= 1 && s <= 3) beep(1, 120);
            }
            ivLastSec = s;
        }
        if (ivPhase == 0 && ivEvery > 0 && ivCombos.length > 0 && left > 4000) {
            double el = dur - left / 1000.0;
            if (el >= ivNextCombo) { ivCombo = ivCombos[rnd.nextInt(ivCombos.length)]; say(ivCombo); ivNextCombo = el + ivEvery; }
        }
        if (left <= 0) { beep(4, 100); ivNext(); }
        emitIv();
    }
    private void emitIv() {
        java.util.Map<String, Object> m = new java.util.HashMap<>();
        m.put("on", ivOn); m.put("paused", ivPaused); m.put("phase", ivPhase == 0 ? "work" : "rest"); m.put("round", ivRound);
        m.put("rounds", ivRounds); m.put("left", ivLeftMs()); m.put("dur", (ivPhase == 0 ? ivWork : ivRest) * 1000L);
        m.put("combo", ivCombo);
        if (listener != null) listener.onEvent("iv", m);
    }
    private void beep(int times, int len) {
        for (int i = 0; i < times; i++) {
            final int d = i * (len + 80);
            h.postDelayed(() -> { try { if (tone != null) tone.startTone(ToneGenerator.TONE_PROP_BEEP, len); } catch (Exception ignored) {} }, d);
        }
        try {
            Vibrator vb = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (vb != null) {
                long[] pat = new long[times * 2]; for (int i = 0; i < times; i++) { pat[i * 2] = i == 0 ? 0 : 80; pat[i * 2 + 1] = len; }
                if (Build.VERSION.SDK_INT >= 26) vb.vibrate(VibrationEffect.createWaveform(pat, -1)); else vb.vibrate(pat, -1);
            }
        } catch (Exception ignored) {}
    }

    public void emitState() {
        java.util.Map<String, Object> m = new java.util.HashMap<>();
        m.put("connected", connected); m.put("name", deviceName == null ? "Brustgurt" : deviceName); m.put("battery", battery); m.put("want", wantConnected);
        if (listener != null) listener.onEvent("state", m);
        updateNotif();
    }
    public String getDeviceName() { return deviceName; }

    @Override public void onDestroy() {
        h.removeCallbacksAndMessages(null);
        if (gatt != null) { try { gatt.close(); } catch (Exception ignored) {} }
        if (wake != null && wake.isHeld()) wake.release();
        if (tone != null) tone.release();
        if (tts != null) { try { tts.shutdown(); } catch (Exception ignored) {} tts = null; }
        instance = null;
        super.onDestroy();
    }
}
