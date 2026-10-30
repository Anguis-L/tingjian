package com.tingjian.relay;

import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;

/**
 * 选择输入/输出设备，并把设备类型翻译成给人看的中文。
 * 核心策略：优先蓝牙耳机，其次有线/USB，绝不回落到外放扬声器（防止啸叫）。
 */
final class AudioRoutes {

    private AudioRoutes() {
    }

    static AudioDeviceInfo pickOutput(AudioManager am) {
        return pickOutput(am, false);
    }

    /**
     * @param allowSpeaker 仅用于开发调试（把手机当声源验证链路），正式使用永远为 false，
     *                     避免外放造成啸叫。
     */
    static AudioDeviceInfo pickOutput(AudioManager am, boolean allowSpeaker) {
        AudioDeviceInfo best = null;
        int bestScore = 0;
        AudioDeviceInfo speaker = null;
        for (AudioDeviceInfo device : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            int type = device.getType();
            if (type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                speaker = device;
                continue;
            }
            int score = outputScore(type);
            if (score > bestScore) {
                bestScore = score;
                best = device;
            }
        }
        if (bestScore > 0) {
            return best;
        }
        return allowSpeaker ? speaker : null;
    }

    static AudioDeviceInfo pickInput(AudioManager am) {
        AudioDeviceInfo fallback = null;
        for (AudioDeviceInfo device : am.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC) {
                return device;
            }
            if (fallback == null) {
                fallback = device;
            }
        }
        return fallback;
    }

    private static int outputScore(int type) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && type == AudioDeviceInfo.TYPE_BLE_HEADSET) {
            return 100; // LE Audio
        }
        if (type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) {
            return 90;
        }
        if (type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
            return 80;
        }
        if (type == AudioDeviceInfo.TYPE_USB_HEADSET) {
            return 70;
        }
        if (type == AudioDeviceInfo.TYPE_USB_DEVICE) {
            return 65;
        }
        if (type == AudioDeviceInfo.TYPE_WIRED_HEADSET) {
            return 60;
        }
        if (type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES) {
            return 55;
        }
        if (type == AudioDeviceInfo.TYPE_LINE_ANALOG) {
            return 40;
        }
        return 0;
    }

    static String describe(AudioDeviceInfo device) {
        if (device == null) {
            return "耳机未连接";
        }
        int type = device.getType();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && type == AudioDeviceInfo.TYPE_BLE_HEADSET) {
            return "蓝牙耳机（LE Audio）";
        }
        if (type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) {
            return "蓝牙耳机（A2DP）";
        }
        if (type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
            return "蓝牙耳机（免提链路）";
        }
        if (type == AudioDeviceInfo.TYPE_USB_HEADSET || type == AudioDeviceInfo.TYPE_USB_DEVICE) {
            return "USB 耳机";
        }
        if (type == AudioDeviceInfo.TYPE_WIRED_HEADSET || type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES) {
            return "有线耳机";
        }
        if (type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
            return "手机外放（仅调试）";
        }
        if (type == AudioDeviceInfo.TYPE_BUILTIN_MIC) {
            return "手机内置麦克风";
        }
        return "音频设备（类型 " + type + "）";
    }

    static boolean isBluetooth(AudioDeviceInfo device) {
        if (device == null) {
            return false;
        }
        int type = device.getType();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && type == AudioDeviceInfo.TYPE_BLE_HEADSET) {
            return true;
        }
        return type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO;
    }

    /** 设备支持的首选采样率：48k -> 44.1k -> 16k -> 设备第一个。 */
    static int pickSampleRate(AudioDeviceInfo device) {
        int[] rates = device == null ? null : device.getSampleRates();
        if (rates == null || rates.length == 0) {
            return 48000;
        }
        for (int wanted : new int[]{48000, 44100, 16000}) {
            for (int rate : rates) {
                if (rate == wanted) {
                    return wanted;
                }
            }
        }
        return rates[0] > 0 ? rates[0] : 48000;
    }
}
