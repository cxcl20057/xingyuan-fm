package com.rea.radio;

/** 播放器的进程级单例，供广播接收器（可能早于 Activity 创建）访问。 */
final class RadioPlayerHolder {

    private static volatile RadioPlayer instance;

    private RadioPlayerHolder() {
    }

    static void set(RadioPlayer p) {
        instance = p;
    }

    static RadioPlayer get() {
        return instance;
    }
}
