package de.semkoq.trainingsapp;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

/** Supplement-Erinnerungen: pro Uhrzeit ein täglicher Wecker, Benachrichtigung mit Knopf „Genommen“. */
public class SuppAlarm extends BroadcastReceiver {
    static final String FIRE = "de.semkoq.trainingsapp.SUPP_FIRE", TAKEN = "de.semkoq.trainingsapp.SUPP_TAKEN";
    static final String SNOOZE = "de.semkoq.trainingsapp.SUPP_SNOOZE", SHOW = "de.semkoq.trainingsapp.SUPP_SHOW";
    static final String CH = "supps";
    static final int MAXN = 24;

    static void saveSnooze(Context c, int min) { prefs(c).edit().putInt("snooze", Math.max(1, Math.min(120, min))).apply(); }
    static int snoozeMin(Context c) { return Math.max(1, Math.min(120, prefs(c).getInt("snooze", 10))); }

    /** Eigener Wecker für das erneute Anzeigen (getrennt vom Tageswecker, den scheduleAll verwaltet). */
    private static PendingIntent snoozePi(Context c, int i, String key) {
        Intent it = new Intent(c, SuppAlarm.class).setAction(SHOW).putExtra("i", i).putExtra("k", key);
        return PendingIntent.getBroadcast(c, 4600 + i, it, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("supps", Context.MODE_PRIVATE); }
    static String today() { return new SimpleDateFormat("yyyy-MM-dd", Locale.GERMANY).format(Calendar.getInstance().getTime()); }

    static JSONArray items(Context c) {
        try { return new JSONArray(prefs(c).getString("items", "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    static void save(Context c, String json) { prefs(c).edit().putString("items", json).apply(); }

    private static PendingIntent firePi(Context c, int i) {
        Intent it = new Intent(c, SuppAlarm.class).setAction(FIRE).putExtra("i", i);
        return PendingIntent.getBroadcast(c, 4100 + i, it, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static void scheduleAll(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        for (int i = 0; i < MAXN; i++) am.cancel(firePi(c, i));
        JSONArray a = items(c);
        String today = today();
        for (int i = 0; i < Math.min(a.length(), MAXN); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Calendar t = Calendar.getInstance();
            t.set(Calendar.HOUR_OF_DAY, o.optInt("h", 8)); t.set(Calendar.MINUTE, o.optInt("m", 0));
            t.set(Calendar.SECOND, 0); t.set(Calendar.MILLISECOND, 0);
            boolean doneToday = today.equals(o.optString("skip", ""));
            if (t.getTimeInMillis() <= System.currentTimeMillis() + 5000 || doneToday) t.add(Calendar.DAY_OF_YEAR, 1);
            long when = t.getTimeInMillis();
            if (Build.VERSION.SDK_INT >= 23) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, firePi(c, i));
            else am.set(AlarmManager.RTC_WAKEUP, when, firePi(c, i));
        }
    }

    static String takeTaken(Context c) {
        SharedPreferences p = prefs(c);
        String s = p.getString("taken", "[]");
        p.edit().putString("taken", "[]").apply();
        return s;
    }

    @Override public void onReceive(Context c, Intent in) {
        String act = in.getAction();
        if (FIRE.equals(act)) {
            int i = in.getIntExtra("i", -1);
            JSONObject o = items(c).optJSONObject(i);
            if (o != null && !today().equals(o.optString("skip", ""))) notify(c, i, o);
            scheduleAll(c);
        } else if (TAKEN.equals(act)) {
            int i = in.getIntExtra("i", -1);
            JSONObject o = items(c).optJSONObject(i);
            if (o != null) {
                try {
                    JSONArray t = new JSONArray(prefs(c).getString("taken", "[]"));
                    JSONObject x = new JSONObject();
                    x.put("key", o.optString("key")); x.put("ids", o.optString("ids")); x.put("t", System.currentTimeMillis());
                    t.put(x);
                    prefs(c).edit().putString("taken", t.toString()).apply();
                } catch (Exception ignored) {}
            }
            try { c.getSystemService(NotificationManager.class).cancel(4200 + i); } catch (Exception ignored) {}
            if (i >= 0 && i < MAXN) ((AlarmManager) c.getSystemService(Context.ALARM_SERVICE)).cancel(snoozePi(c, i, ""));
        } else if (SNOOZE.equals(act)) {
            int i = in.getIntExtra("i", -1);
            String key = in.getStringExtra("k"); if (key == null) key = "";
            try { c.getSystemService(NotificationManager.class).cancel(4200 + i); } catch (Exception ignored) {}
            if (i >= 0 && i < MAXN) {
                AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
                long when = System.currentTimeMillis() + snoozeMin(c) * 60000L;
                if (Build.VERSION.SDK_INT >= 23) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, snoozePi(c, i, key));
                else am.set(AlarmManager.RTC_WAKEUP, when, snoozePi(c, i, key));
            }
        } else if (SHOW.equals(act)) {
            int i = in.getIntExtra("i", -1);
            JSONObject o = items(c).optJSONObject(i);
            String key = in.getStringExtra("k"); // Uhrzeit hat sich seit „Später“ geändert → nichts anzeigen
            if (o != null && o.optString("key", "").equals(key == null ? "" : key) && !today().equals(o.optString("skip", ""))) notify(c, i, o);
        } else if (Intent.ACTION_BOOT_COMPLETED.equals(act) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(act)) {
            scheduleAll(c);
        }
    }

    private void notify(Context c, int i, JSONObject o) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null) {
            NotificationChannel ch = new NotificationChannel(CH, "Supplements", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Erinnerung an deine Supplements");
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(Intent.ACTION_VIEW, Uri.parse("trainingsapp://supp"), c, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pOpen = PendingIntent.getActivity(c, 4300 + i, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent tk = new Intent(c, SuppAlarm.class).setAction(TAKEN).putExtra("i", i);
        PendingIntent pTk = PendingIntent.getBroadcast(c, 4400 + i, tk, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent sz = new Intent(c, SuppAlarm.class).setAction(SNOOZE).putExtra("i", i).putExtra("k", o.optString("key", ""));
        PendingIntent pSz = PendingIntent.getBroadcast(c, 4500 + i, sz, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String text = o.optString("text", "");
        try {
            nm.notify(4200 + i, new NotificationCompat.Builder(c, CH)
                    .setSmallIcon(android.R.drawable.ic_popup_reminder)
                    .setContentTitle(o.optString("title", "Supplements"))
                    .setContentText(text)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_REMINDER)
                    .setAutoCancel(true)
                    .setContentIntent(pOpen)
                    .addAction(0, "Genommen", pTk)
                    .addAction(0, "+" + snoozeMin(c) + " Min später", pSz)
                    .build());
        } catch (SecurityException ignored) {}
    }
}
