package com.exhish;

public class Native {
    static { System.loadLibrary("exhish"); }

    public static volatile int fd = -1;

    public static native String tarballUrl();
    public static native void init(int rows, int cols);
    public static native void resize(int fd, int rows, int cols);
    public static native int[] spawn(String exe, String[] args, String[] env, String cwd, int rows, int cols);
    public static native int pump(int fd);
    public static native void write(int fd, byte[] data);
    public static native void feed(byte[] data);
    public static native void banner();
    public static native void snapshot(int[] out, int first, int count);
    public static native void info(int[] out);
    public static native int modes();
    public static native int waitFor(int pid);
    public static native void kill(int pid);
    public static native void closeFd(int fd);
}
