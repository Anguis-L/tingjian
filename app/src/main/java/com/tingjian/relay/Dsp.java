package com.tingjian.relay;

/**
 * 实时处理链：直流阻断 -> 高频搁架（清晰度）-> 增益 -> 软限幅。
 * 全部原地处理，不分配对象，避免在音频线程里触发 GC。
 */
final class Dsp {

    private static final float LIMIT_THRESHOLD = 0.86f;
    private static final float SHELF_HZ = 3000f;

    private float gain = 4f;
    private boolean shelfOn = false;
    private float b0 = 1f, b1 = 0f, b2 = 0f, a1 = 0f, a2 = 0f;
    private float x1 = 0f, x2 = 0f, y1 = 0f, y2 = 0f;
    private float env = 0f;
    private float dcPrevIn = 0f;
    private float dcPrevOut = 0f;
    private float reductionDb = 0f;

    void setGainDb(float db) {
        gain = (float) Math.pow(10.0, db / 20.0);
    }

    void setTrebleDb(float db, int sampleRate) {
        if (Math.abs(db) < 0.01f) {
            shelfOn = false;
            b0 = 1f;
            b1 = 0f;
            b2 = 0f;
            a1 = 0f;
            a2 = 0f;
            return;
        }
        shelfOn = true;
        double a = Math.pow(10.0, db / 40.0);
        double w0 = 2.0 * Math.PI * SHELF_HZ / sampleRate;
        double cos = Math.cos(w0);
        double sin = Math.sin(w0);
        double alpha = sin / 2.0 * Math.sqrt(2.0);
        double sqrtA = Math.sqrt(a);
        double a0 = (a + 1) - (a - 1) * cos + 2 * sqrtA * alpha;

        b0 = (float) ((a * ((a + 1) + (a - 1) * cos + 2 * sqrtA * alpha)) / a0);
        b1 = (float) ((-2 * a * ((a - 1) + (a + 1) * cos)) / a0);
        b2 = (float) ((a * ((a + 1) + (a - 1) * cos - 2 * sqrtA * alpha)) / a0);
        a1 = (float) ((2 * ((a - 1) - (a + 1) * cos)) / a0);
        a2 = (float) (((a + 1) - (a - 1) * cos - 2 * sqrtA * alpha) / a0);
    }

    void reset() {
        x1 = x2 = y1 = y2 = 0f;
        env = 0f;
        dcPrevIn = 0f;
        dcPrevOut = 0f;
        reductionDb = 0f;
    }

    /** 处理一段 16 位单声道 PCM，返回输入峰值（0..1），供电平表使用。 */
    float process(short[] buffer, int length) {
        float peak = 0f;
        for (int i = 0; i < length; i++) {
            float sample = buffer[i] / 32768f;
            float absIn = Math.abs(sample);
            if (absIn > peak) {
                peak = absIn;
            }

            // 直流阻断
            float dcOut = sample - dcPrevIn + 0.995f * dcPrevOut;
            dcPrevIn = sample;
            dcPrevOut = dcOut;
            sample = dcOut;

            // 高频搁架
            if (shelfOn) {
                float out = b0 * sample + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
                x2 = x1;
                x1 = sample;
                y2 = y1;
                y1 = out;
                sample = out;
            }

            // 增益
            sample *= gain;

            // 软限幅：峰值包络跟随后按比例回退，避免硬削波的破音
            float absOut = Math.abs(sample);
            if (absOut > env) {
                env = env + (absOut - env) * 0.30f;
            } else {
                env = env + (absOut - env) * 0.002f;
            }
            if (env > LIMIT_THRESHOLD) {
                float over = env / LIMIT_THRESHOLD;
                sample /= over;
                float db = (float) (20.0 * Math.log10(over));
                reductionDb = Math.max(db, reductionDb * 0.92f);
            } else {
                reductionDb *= 0.92f;
            }

            if (sample > 1f) {
                sample = 1f;
            } else if (sample < -1f) {
                sample = -1f;
            }
            buffer[i] = (short) (sample * 32767f);
        }
        return peak;
    }

    /** 限幅器当前压缩量（dB），用于检测持续过载/啸叫。 */
    float getReductionDb() {
        return reductionDb;
    }
}
