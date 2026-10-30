package com.tingjian.relay;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

/**
 * 采集（手机麦克风）-> Dsp -> 播放（蓝牙/有线耳机）的实时线程。
 *
 * 设计要点：
 * - 走 MODE_NORMAL + setPreferredDevice，不碰已废弃的 SCO，保证手机麦克风可用；
 * - 低延迟播放通道 + 小缓冲，降低本机侧延迟；
 * - 监听设备插拔，耳机换了就自动重开流；
 * - 检测到持续过载（啸叫/爆音）自动降增益。
 */
public final class AudioEngine {

    public interface Listener {
        void onState(boolean running, String message);

        void onRoute(String routeText, int sampleRate, int bufferMs);

        void onLevel(float fraction);

        void onAutoGain(float gainDb);
    }

    private static final String TAG = "TingjianEngine";
    private static final int FRAMES = 256;
    /** 打开音频流后这么长时间内的设备变化事件不触发重连（有些机型开流本身会广播设备变化）。 */
    private static final long ROUTE_SUPPRESS_MS = 1500L;

    private final Context appContext;
    private final AudioManager audioManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Dsp dsp = new Dsp();
    private final Listener listener;

    private volatile boolean running;
    private volatile boolean routeChanged;
    private volatile float gainDb = 12f;
    private volatile float trebleDb = 4f;
    private volatile int currentSampleRate = 48000;
    private volatile boolean allowSpeaker;
    private volatile long lastStreamOpenAt;

    private Thread worker;
    private volatile AudioRecord record;
    private volatile AudioTrack track;
    private AudioDeviceCallback deviceCallback;

    public AudioEngine(Context context, Listener listener) {
        this.appContext = context.getApplicationContext();
        this.audioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
        this.listener = listener;
    }

    public boolean isRunning() {
        return running;
    }

    public float getGainDb() {
        return gainDb;
    }

    public void setGainDb(float db) {
        gainDb = Math.max(-12f, Math.min(30f, db));
        dsp.setGainDb(gainDb);
    }

    public void setTrebleDb(float db) {
        trebleDb = Math.max(0f, Math.min(12f, db));
        dsp.setTrebleDb(trebleDb, currentSampleRate);
    }

    /** 仅调试构建使用：允许回落到手机外放，用来在没有耳机时验证音频链路。 */
    public void setAllowSpeaker(boolean allow) {
        allowSpeaker = allow;
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        routeChanged = false;
        dsp.setGainDb(gainDb);
        registerDeviceCallback();
        worker = new Thread(this::loop, "TingjianAudio");
        worker.start();
    }

    public void stop() {
        boolean wasRunning = running;
        running = false;
        // 主动打断阻塞读，避免 stop 卡住
        try {
            AudioRecord r = record;
            if (r != null) {
                r.stop();
            }
        } catch (Throwable ignored) {
        }
        try {
            AudioTrack t = track;
            if (t != null) {
                t.stop();
            }
        } catch (Throwable ignored) {
        }
        Thread w = worker;
        worker = null;
        if (w != null) {
            try {
                w.join(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        unregisterDeviceCallback();
        if (wasRunning) {
            postState(false, "已停止");
        }
    }

    private void loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        try {
            audioManager.setMode(AudioManager.MODE_NORMAL);
        } catch (Throwable ignored) {
        }

        while (running) {
            if (routeChanged) {
                routeChanged = false;
                Log.i(TAG, "audio route changed, reopening streams");
                postState(true, "耳机已变化，正在重新连接…");
            }
            try {
                runStream();
            } catch (Throwable t) {
                Log.w(TAG, "stream error", t);
                if (running) {
                    postState(true, "音频流异常，正在重试…");
                }
            }
            if (!running) {
                break;
            }
            if (!routeChanged) {
                sleepQuietly(600);
            }
        }
    }

    private void runStream() {
        AudioDeviceInfo output = AudioRoutes.pickOutput(audioManager, allowSpeaker);
        if (output == null) {
            running = false;
            postState(false, "未检测到耳机，已停止（避免外放啸叫）");
            return;
        }
        AudioDeviceInfo input = AudioRoutes.pickInput(audioManager);

        int sampleRate = AudioRoutes.pickSampleRate(output);
        int minIn = AudioRecord.getMinBufferSize(sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int minOut = AudioTrack.getMinBufferSize(sampleRate,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minIn <= 0 || minOut <= 0) {
            running = false;
            postState(false, "这台设备不支持实时录音/播放");
            return;
        }

        AudioRecord rec = null;
        AudioTrack trk = null;
        try {
            int recBuf = Math.max(minIn, FRAMES * 4);
            int trkBuf = Math.max(minOut, FRAMES * 4);

            rec = new AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build())
                    .setBufferSizeInBytes(recBuf)
                    .build();

            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();
            trk = new AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(trkBuf)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build();

            if (rec.getState() != AudioRecord.STATE_INITIALIZED
                    || trk.getState() != AudioTrack.STATE_INITIALIZED) {
                running = false;
                postState(false, "音频设备初始化失败");
                return;
            }

            rec.setPreferredDevice(input);
            trk.setPreferredDevice(output);

            currentSampleRate = sampleRate;
            dsp.reset();
            dsp.setGainDb(gainDb);
            dsp.setTrebleDb(trebleDb, sampleRate);

            record = rec;
            track = trk;
            lastStreamOpenAt = SystemClock.uptimeMillis();
            // 开流之前排队的设备变化事件不应把刚开好的流立刻拆掉
            routeChanged = false;

            rec.startRecording();
            trk.play();
            try {
                trk.setBufferSizeInFrames(FRAMES * 2);
            } catch (Throwable ignored) {
            }

            int trackFrames = trk.getBufferSizeInFrames();
            int bufferMs = Math.round((FRAMES * 2 + Math.max(trackFrames, 0)) * 1000f / sampleRate);
            Log.i(TAG, "stream opened: rate=" + sampleRate + " trackFrames=" + trackFrames
                    + " out=" + AudioRoutes.describe(output) + " in=" + AudioRoutes.describe(input));
            postRoute(AudioRoutes.describe(output), sampleRate, bufferMs);
            postState(true, "正在运行");

            short[] buffer = new short[FRAMES];
            long lastMeter = 0L;
            int overloadTicks = 0;

            while (running && !routeChanged) {
                int read;
                try {
                    read = rec.read(buffer, 0, FRAMES, AudioRecord.READ_BLOCKING);
                } catch (Throwable t) {
                    break;
                }
                if (read <= 0) {
                    Log.w(TAG, "record.read returned " + read);
                    if (read == AudioRecord.ERROR_DEAD_OBJECT) {
                        break;
                    }
                    sleepQuietly(5);
                    continue;
                }

                float peak = dsp.process(buffer, read);

                int written;
                try {
                    written = trk.write(buffer, 0, read, AudioTrack.WRITE_BLOCKING);
                } catch (Throwable t) {
                    break;
                }
                if (written < 0) {
                    Log.w(TAG, "track.write returned " + written);
                    break;
                }

                long now = SystemClock.uptimeMillis();
                if (now - lastMeter >= 100) {
                    lastMeter = now;
                    postLevel(levelFraction(peak));

                    if (dsp.getReductionDb() > 6f) {
                        overloadTicks++;
                        if (overloadTicks >= 8) {
                            overloadTicks = 0;
                            float next = Math.max(0f, gainDb - 3f);
                            if (next < gainDb) {
                                gainDb = next;
                                dsp.setGainDb(next);
                                postAutoGain(next);
                                postState(true, "检测到啸叫/过载，已自动降低增益");
                            }
                        }
                    } else {
                        overloadTicks = 0;
                    }
                }
            }
        } finally {
            record = null;
            track = null;
            releaseQuietly(rec, trk);
            try {
                audioManager.setMode(AudioManager.MODE_NORMAL);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void releaseQuietly(AudioRecord rec, AudioTrack trk) {
        if (rec != null) {
            try {
                if (rec.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    rec.stop();
                }
            } catch (Throwable ignored) {
            }
            try {
                rec.release();
            } catch (Throwable ignored) {
            }
        }
        if (trk != null) {
            try {
                if (trk.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                    trk.stop();
                }
            } catch (Throwable ignored) {
            }
            try {
                trk.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private void registerDeviceCallback() {
        if (deviceCallback != null) {
            return;
        }
        deviceCallback = new AudioDeviceCallback() {
            @Override
            public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
                markRouteChanged();
            }

            @Override
            public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
                markRouteChanged();
            }
        };
        try {
            audioManager.registerAudioDeviceCallback(deviceCallback, mainHandler);
        } catch (Throwable t) {
            Log.w(TAG, "register callback failed", t);
        }
    }

    private void markRouteChanged() {
        if (SystemClock.uptimeMillis() - lastStreamOpenAt < ROUTE_SUPPRESS_MS) {
            Log.i(TAG, "device change ignored right after opening streams");
            return;
        }
        routeChanged = true;
    }

    private void unregisterDeviceCallback() {
        AudioDeviceCallback callback = deviceCallback;
        deviceCallback = null;
        if (callback != null) {
            try {
                audioManager.unregisterAudioDeviceCallback(callback);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 把输入峰值换算成 0..1 的电平条（-60 dBFS 到 0 dBFS）。 */
    private static float levelFraction(float peak) {
        if (peak <= 0.0005f) {
            return 0f;
        }
        float db = (float) (20.0 * Math.log10(peak));
        float fraction = (db + 60f) / 60f;
        return Math.max(0f, Math.min(1f, fraction));
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void postState(final boolean isRunning, final String message) {
        mainHandler.post(() -> listener.onState(isRunning, message));
    }

    private void postRoute(final String routeText, final int sampleRate, final int bufferMs) {
        mainHandler.post(() -> listener.onRoute(routeText, sampleRate, bufferMs));
    }

    private void postLevel(final float fraction) {
        mainHandler.post(() -> listener.onLevel(fraction));
    }

    private void postAutoGain(final float db) {
        mainHandler.post(() -> listener.onAutoGain(db));
    }
}
