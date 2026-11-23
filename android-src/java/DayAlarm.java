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

/** Tägliche Erinnerungen (z. B. Abend-Journal): pro Eintrag ein Wecker, Tippen öffnet die App per Deep-Link. */
public class DayAlarm extends BroadcastReceiver {
    static final String FIRE = "de.semkoq.trainingsapp.DAY_FIRE";
    static final String CH = "reminders";
    static final int MAXN = 8;

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("dayalarm", Context.MODE_PRIVATE); }

    static JSONArray items(Context c) {
        try { return new JSONArray(prefs(c).getString("items", "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    static void save(Context c, String json) { prefs(c).edit().putString("items", json).apply(); }

    static String today() { return new SimpleDateFormat("yyyy-MM-dd", Locale.GERMANY).format(Calendar.getInstance().getTime()); }

    private static PendingIntent firePi(Context c, int i) {
        Intent it = new Intent(c, DayAlarm.class).setAction(FIRE).putExtra("i", i);
        return PendingIntent.getBroadcast(c, 5100 + i, it, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static void scheduleAll(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        for (int i = 0; i < MAXN; i++) am.cancel(firePi(c, i));
        JSONArray a = items(c);
        for (int i = 0; i < Math.min(a.length(), MAXN); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Calendar t = Calendar.getInstance();
            t.set(Calendar.HOUR_OF_DAY, o.optInt("h", 21)); t.set(Calendar.MINUTE, o.optInt("m", 0));
            t.set(Calendar.SECOND, 0); t.set(Calendar.MILLISECOND, 0);
            if (t.getTimeInMillis() <= System.currentTimeMillis() + 5000) t.add(Calendar.DAY_OF_YEAR, 1);
            long when = t.getTimeInMillis();
            if (Build.VERSION.SDK_INT >= 23) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, firePi(c, i));
            else am.set(AlarmManager.RTC_WAKEUP, when, firePi(c, i));
        }
    }

    @Override public void onReceive(Context c, Intent in) {
        String act = in.getAction();
        if (FIRE.equals(act)) {
            int i = in.getIntExtra("i", -1);
            JSONObject o = items(c).optJSONObject(i);
            // "skip": Tage (JJJJ-MM-TT, mit Komma getrennt), an denen die Erinnerung ausfällt – z. B. weil schon eingetragen
            boolean skip = o != null && ("," + o.optString("skip", "") + ",").contains("," + today() + ",");
            if (o != null && !skip) notify(c, i, o);
            scheduleAll(c);
        } else if (Intent.ACTION_BOOT_COMPLETED.equals(act) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(act)) {
            scheduleAll(c);
        }
    }

    private void notify(Context c, int i, JSONObject o) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null) {
            NotificationChannel ch = new NotificationChannel(CH, "Erinnerungen", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Abend-Journal und andere tägliche Erinnerungen");
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(Intent.ACTION_VIEW, Uri.parse(o.optString("url", "trainingsapp://open")), c, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pOpen = PendingIntent.getActivity(c, 5300 + i, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String text = o.optString("text", "");
        try {
            nm.notify(5200 + i, new NotificationCompat.Builder(c, CH)
                    .setSmallIcon(android.R.drawable.ic_popup_reminder)
                    .setContentTitle(o.optString("title", "Trainings-App"))
                    .setContentText(text)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_REMINDER)
                    .setAutoCancel(true)
                    .setContentIntent(pOpen)
                    .build());
        } catch (SecurityException ignored) {}
    }
}
