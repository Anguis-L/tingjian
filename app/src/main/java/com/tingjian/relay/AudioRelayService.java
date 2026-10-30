package com.tingjian.relay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

/**
 * 前台服务：持有音频引擎，保证息屏/切后台后继续采集与回放。
 * Android 14 起 microphone 类型的前台服务必须由用户可见界面启动，这里由 Activity 触发。
 */
public final class AudioRelayService extends Service implements AudioEngine.Listener {

    public static final String ACTION_START = "com.tingjian.relay.action.START";
    public static final String ACTION_STOP = "com.tingjian.relay.action.STOP";
    public static final String EXTRA_GAIN_DB = "gain_db";
    public static final String EXTRA_TREBLE_DB = "treble_db";
    /** 仅 debug 包有效：允许回落到手机外放，用于没有耳机时验证音频链路。 */
    public static final String EXTRA_ALLOW_SPEAKER = "allow_speaker";

    private static final String TAG = "TingjianService";
    private static final String CHANNEL_ID = "relay_running";
    private static final int NOTIFICATION_ID = 41;

    public interface UiListener {
        void onState(boolean running, String message);

        void onRoute(String routeText, int sampleRate, int bufferMs);

        void onLevel(float fraction);

        void onAutoGain(float gainDb);
    }

    public final class LocalBinder extends Binder {
        public AudioRelayService getService() {
            return AudioRelayService.this;
        }
    }

    private final IBinder binder = new LocalBinder();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private AudioEngine engine;
    private PowerManager.WakeLock wakeLock;
    private NotificationManager notificationManager;

    private volatile UiListener uiListener;
    private volatile boolean relayRunning;
    private volatile String routeText = "耳机未连接";
    private volatile int sampleRate;
    private volatile int bufferMs;
    private volatile float gainDb = 12f;
    private volatile float trebleDb = 4f;
    private volatile boolean shuttingDown;

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        createChannel();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            shutdown();
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action) || action == null) {
            gainDb = intent == null ? gainDb : intent.getFloatExtra(EXTRA_GAIN_DB, gainDb);
            trebleDb = intent == null ? trebleDb : intent.getFloatExtra(EXTRA_TREBLE_DB, trebleDb);
            boolean allowSpeaker = intent != null
                    && intent.getBooleanExtra(EXTRA_ALLOW_SPEAKER, false)
                    && isDebuggable();
            startRelay(allowSpeaker);
        }
        return START_NOT_STICKY;
    }

    private void startRelay(boolean allowSpeaker) {
        if (engine != null && engine.isRunning()) {
            return;
        }
        shuttingDown = false;
        startForegroundCompat();
        acquireWakeLock();

        AudioEngine newEngine = new AudioEngine(this, this);
        newEngine.setAllowSpeaker(allowSpeaker);
        newEngine.setGainDb(gainDb);
        newEngine.setTrebleDb(trebleDb);
        engine = newEngine;
        newEngine.start();
        relayRunning = true;
    }

    private boolean isDebuggable() {
        return (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    private void startForegroundCompat() {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    | ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK;
            startForeground(NOTIFICATION_ID, notification, type);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void createChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "助听运行状态", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("显示「听见」是否正在运行");
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.setShowBadge(false);
        notificationManager.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, AudioRelayService.class).setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_relay)
                .setContentTitle("听见 · 正在助听")
                .setContentText(routeText)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setPriority(Notification.PRIORITY_LOW);
        builder.addAction(new Notification.Action.Builder(
                R.drawable.ic_stat_relay, "停止", stopPending).build());
        return builder.build();
    }

    private void refreshNotification() {
        if (notificationManager != null) {
            notificationManager.notify(NOTIFICATION_ID, buildNotification());
        }
    }

    private void acquireWakeLock() {
        if (wakeLock != null) {
            return;
        }
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Tingjian::RelayAudio");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
    }

    private void releaseWakeLock() {
        PowerManager.WakeLock lock = wakeLock;
        wakeLock = null;
        if (lock != null && lock.isHeld()) {
            try {
                lock.release();
            } catch (Throwable t) {
                Log.w(TAG, "release wakelock", t);
            }
        }
    }

    private void shutdown() {
        if (shuttingDown) {
            return;
        }
        shuttingDown = true;
        AudioEngine current = engine;
        engine = null;
        relayRunning = false;
        if (current != null) {
            current.stop();
        }
        releaseWakeLock();
        try {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } catch (Throwable ignored) {
        }
        stopSelf();
    }

    // ---- 供界面调用的接口 ----

    public boolean isRunning() {
        return relayRunning;
    }

    public String getRouteText() {
        return routeText;
    }

    public int getSampleRate() {
        return sampleRate;
    }

    public int getBufferMs() {
        return bufferMs;
    }

    public float getGainDb() {
        return gainDb;
    }

    public float getTrebleDb() {
        return trebleDb;
    }

    public void setUiListener(UiListener listener) {
        uiListener = listener;
    }

    public void stopRelay() {
        shutdown();
    }

    public void setGainDb(float db) {
        gainDb = db;
        AudioEngine current = engine;
        if (current != null) {
            current.setGainDb(db);
        }
    }

    public void setTrebleDb(float db) {
        trebleDb = db;
        AudioEngine current = engine;
        if (current != null) {
            current.setTrebleDb(db);
        }
    }

    // ---- AudioEngine 回调（都在主线程被调用） ----

    @Override
    public void onState(boolean running, String message) {
        relayRunning = running;
        if (!running) {
            mainHandler.post(this::shutdown);
        }
        forwardState(running, message);
    }

    @Override
    public void onRoute(String text, int rate, int ms) {
        routeText = text;
        sampleRate = rate;
        bufferMs = ms;
        refreshNotification();
        UiListener listener = uiListener;
        if (listener != null) {
            listener.onRoute(text, rate, ms);
        }
    }

    @Override
    public void onLevel(float fraction) {
        UiListener listener = uiListener;
        if (listener != null) {
            listener.onLevel(fraction);
        }
    }

    @Override
    public void onAutoGain(float db) {
        gainDb = db;
        UiListener listener = uiListener;
        if (listener != null) {
            listener.onAutoGain(db);
        }
    }

    private void forwardState(final boolean running, final String message) {
        final UiListener listener = uiListener;
        if (listener == null) {
            return;
        }
        mainHandler.post(() -> {
            if (uiListener == listener) {
                listener.onState(running, message);
            }
        });
    }

    @Override
    public void onDestroy() {
        AudioEngine current = engine;
        engine = null;
        relayRunning = false;
        if (current != null) {
            current.stop();
        }
        releaseWakeLock();
        super.onDestroy();
    }
}
