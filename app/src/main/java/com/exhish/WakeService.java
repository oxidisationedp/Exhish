package com.exhish;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

public class WakeService extends Service {
    private PowerManager.WakeLock lock;

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel("exwake", "Exhish background",
                NotificationManager.IMPORTANCE_LOW));
            b = new Notification.Builder(this, "exwake");
        } else {
            b = new Notification.Builder(this);
        }
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        int pf = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, pf);
        b.setContentTitle("Exhish is awake")
            .setContentText("Running in the background. Type exwake --gotosleep to stop.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setContentIntent(pi);
        startForeground(1, b.build());
        if (lock == null) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "exhish:wake");
            lock.acquire();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (lock != null && lock.isHeld()) lock.release();
        lock = null;
        super.onDestroy();
    }
}
