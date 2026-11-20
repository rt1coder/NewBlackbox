package top.niunaijun.blackbox.core.engine;

public final class MocgramEngine {
    private MocgramEngine() {
    }

    public static final String NAME = "Mocgram Engine";
    public static final String VERSION = "1.0.0";
    public static final String BUILD = "dev";

    public static String full() {
        return NAME + " " + VERSION + " (build " + BUILD + ")";
    }

    public static String tag() {
        return VERSION + "-" + BUILD;
    }
}
