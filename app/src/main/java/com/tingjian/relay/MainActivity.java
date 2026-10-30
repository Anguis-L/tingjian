package com.tingjian.relay;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity {

    private static final int REQUEST_PERMISSIONS = 1001;
    private static final String PREFS = "tingjian";
    private static final String KEY_GAIN = "gain_progress";
    private static final String KEY_TREBLE = "treble_progress";

    private TextView statusText;
    private TextView routeText;
    private TextView detailText;
    private TextView gainText;
    private TextView trebleText;
    private ProgressBar levelBar;
    private SeekBar gainSeek;
    private SeekBar trebleSeek;
    private Button toggleButton;
    private Button batteryButton;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private AudioRelayService service;
    private boolean bound;
    private boolean starting;
    private boolean allowSpeakerDebug;
    private SharedPreferences prefs;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((AudioRelayService.LocalBinder) binder).getService();
            bound = true;
            service.setUiListener(uiListener);
            starting = false;
            applyServiceState();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            bound = false;
            setStoppedUi("未运行");
        }
    };

    private final AudioRelayService.UiListener uiListener = new AudioRelayService.UiListener() {
        @Override
        public void onState(boolean running, String message) {
            starting = false;
            if (running) {
                statusText.setText("正在运行");
                toggleButton.setText(R.string.stop);
                toggleButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                        getColor(R.color.danger)));
                toggleButton.setEnabled(true);
                if (service != null) {
                    updateRoute(service.getRouteText(), service.getSampleRate(), service.getBufferMs());
                }
            } else {
                setStoppedUi(message == null ? "未运行" : message);
            }
        }

        @Override
        public void onRoute(String route, int sampleRate, int bufferMs) {
            updateRoute(route, sampleRate, bufferMs);
        }

        @Override
        public void onLevel(float fraction) {
            levelBar.setProgress(Math.round(fraction * 100f));
        }

        @Override
        public void onAutoGain(float gainDb) {
            int progress = Math.max(0, Math.min(gainSeek.getMax(), Math.round(gainDb) + 6));
            gainSeek.setProgress(progress);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        routeText = findViewById(R.id.routeText);
        detailText = findViewById(R.id.detailText);
        gainText = findViewById(R.id.gainText);
        trebleText = findViewById(R.id.trebleText);
        levelBar = findViewById(R.id.levelBar);
        gainSeek = findViewById(R.id.gainSeek);
        trebleSeek = findViewById(R.id.trebleSeek);
        toggleButton = findViewById(R.id.toggleButton);
        batteryButton = findViewById(R.id.batteryButton);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        gainSeek.setProgress(prefs.getInt(KEY_GAIN, 18));
        trebleSeek.setProgress(prefs.getInt(KEY_TREBLE, 4));
        updateGainLabel();
        updateTrebleLabel();

        gainSeek.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateGainLabel();
                if (fromUser) {
                    prefs.edit().putInt(KEY_GAIN, progress).apply();
                    if (service != null && service.isRunning()) {
                        service.setGainDb(progress - 6f);
                    }
                }
            }
        });

        trebleSeek.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateTrebleLabel();
                if (fromUser) {
                    prefs.edit().putInt(KEY_TREBLE, progress).apply();
                    if (service != null && service.isRunning()) {
                        service.setTrebleDb(progress);
                    }
                }
            }
        });

        toggleButton.setOnClickListener(v -> {
            if (starting) {
                return;
            }
            if (service != null && service.isRunning()) {
                stopRelay();
            } else {
                tryStart();
            }
        });

        batteryButton.setOnClickListener(v -> requestIgnoreBatteryOptimizations());

        setStoppedUi("未运行");
        handleDebugIntent(getIntent());
    }

    /**
     * 调试入口（仅 debug 包生效）：
     * adb shell am start -n com.tingjian.relay/.MainActivity --ez allow_speaker true --ez autostart true
     * 用于在没有耳机时验证采集与播放链路，正式包会被 debuggable 判断挡掉。
     */
    private void handleDebugIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        boolean debuggable = (getApplicationInfo().flags
                & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        if (!debuggable || !intent.getBooleanExtra("allow_speaker", false)) {
            return;
        }
        allowSpeakerDebug = true;
        if (intent.getBooleanExtra("autostart", false)) {
            gainSeek.setProgress(0);
            trebleSeek.setProgress(0);
            updateGainLabel();
            updateTrebleLabel();
            mainHandler.postDelayed(this::tryStart, 600L);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleDebugIntent(intent);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!bound) {
            // 只绑定已经存在的服务，不主动创建
            bindService(new Intent(this, AudioRelayService.class), connection, 0);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (bound && service != null) {
            applyServiceState();
        }
    }

    @Override
    protected void onStop() {
        if (bound) {
            if (service != null) {
                service.setUiListener(null);
            }
            unbindService(connection);
            bound = false;
            service = null;
        }
        super.onStop();
    }

    private void applyServiceState() {
        if (service == null) {
            setStoppedUi("未运行");
            return;
        }
        int gainProgress = Math.round(service.getGainDb()) + 6;
        if (gainProgress >= 0 && gainProgress <= gainSeek.getMax()) {
            gainSeek.setProgress(gainProgress);
        }
        int trebleProgress = Math.round(service.getTrebleDb());
        if (trebleProgress >= 0 && trebleProgress <= trebleSeek.getMax()) {
            trebleSeek.setProgress(trebleProgress);
        }
        if (service.isRunning()) {
            statusText.setText("正在运行");
            toggleButton.setText(R.string.stop);
            toggleButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    getColor(R.color.danger)));
            toggleButton.setEnabled(true);
            updateRoute(service.getRouteText(), service.getSampleRate(), service.getBufferMs());
        } else {
            setStoppedUi("未运行");
        }
    }

    private void setStoppedUi(String message) {
        statusText.setText(message);
        toggleButton.setText(R.string.start);
        toggleButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                getColor(R.color.primary)));
        toggleButton.setEnabled(true);
        levelBar.setProgress(0);
        if ("未运行".equals(message)) {
            routeText.setText("耳机未连接");
            detailText.setText("");
        }
    }

    private void updateRoute(String route, int sampleRate, int bufferMs) {
        routeText.setText(route);
        if (sampleRate > 0) {
            detailText.setText(String.format(Locale.CHINA,
                    "%d Hz · 本机缓冲约 %d ms（不含蓝牙传输延迟）", sampleRate, bufferMs));
        }
    }

    private void updateGainLabel() {
        int db = gainSeek.getProgress() - 6;
        gainText.setText(String.format(Locale.CHINA, "音量增益 %+d dB", db));
    }

    private void updateTrebleLabel() {
        int db = trebleSeek.getProgress();
        trebleText.setText(String.format(Locale.CHINA, "清晰度（高频补偿）+%d dB", db));
    }

    private void tryStart() {
        List<String> missing = missingPermissions();
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), REQUEST_PERMISSIONS);
            return;
        }
        startRelay();
    }

    private List<String> missingPermissions() {
        List<String> missing = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        return missing;
    }

    private void startRelay() {
        int gainProgress = gainSeek.getProgress();
        int trebleProgress = trebleSeek.getProgress();
        prefs.edit().putInt(KEY_GAIN, gainProgress).putInt(KEY_TREBLE, trebleProgress).apply();

        Intent intent = new Intent(this, AudioRelayService.class)
                .setAction(AudioRelayService.ACTION_START)
                .putExtra(AudioRelayService.EXTRA_GAIN_DB, (float) (gainProgress - 6))
                .putExtra(AudioRelayService.EXTRA_TREBLE_DB, (float) trebleProgress)
                .putExtra(AudioRelayService.EXTRA_ALLOW_SPEAKER, allowSpeakerDebug);

        starting = true;
        statusText.setText("正在启动…");
        toggleButton.setEnabled(false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        if (!bound) {
            bindService(new Intent(this, AudioRelayService.class), connection, BIND_AUTO_CREATE);
        }
        mainHandler.postDelayed(() -> {
            if (starting) {
                starting = false;
                setStoppedUi("启动未完成，请重试");
            }
        }, 4000L);
    }

    private void stopRelay() {
        if (bound && service != null) {
            service.stopRelay();
        } else {
            startService(new Intent(this, AudioRelayService.class)
                    .setAction(AudioRelayService.ACTION_STOP));
        }
        setStoppedUi("未运行");
    }

    private void requestIgnoreBatteryOptimizations() {
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception ignored) {
                Toast.makeText(this, "请到系统设置里把本应用加入电池优化白名单", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_PERMISSIONS) {
            return;
        }
        if (missingPermissions().isEmpty()) {
            startRelay();
        } else {
            Toast.makeText(this, "没有麦克风权限，无法工作", Toast.LENGTH_LONG).show();
        }
    }

    /** 只关心进度回调的 SeekBar 监听器。 */
    private abstract static class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
        }
    }
}
