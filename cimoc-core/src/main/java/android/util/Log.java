package android.util;

import java.io.PrintStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 桌面端兼容 shim，替代 Android 平台的 {@code android.util.Log}。
 *
 * <p>解析层有 22 处日志调用，其中若干处在排查图源失效时是关键线索。桌面端没有 logcat，
 * 因此这里把日志转发到一个可替换的 {@link Sink}，默认输出到 stderr。
 *
 * <p>后续可以把这个 Sink 接到真正的日志框架（slf4j / log4j2）上：
 * <pre>{@code
 * Log.setSink((level, tag, msg, t) -> logger.atLevel(...).log(msg, t));
 * }</pre>
 */
public final class Log {

    /** 日志等级，与 Android 的 Log 常量保持一致。 */
    public static final int VERBOSE = 2;
    public static final int DEBUG = 3;
    public static final int INFO = 4;
    public static final int WARN = 5;
    public static final int ERROR = 6;

    /** 日志出口。默认写 stderr。 */
    public interface Sink {
        void log(int level, String tag, String message, Throwable throwable);
    }

    private static final Sink DEFAULT_SINK = new Sink() {
        private final PrintStream out = System.err;
        private final SimpleDateFormat fmt =
                new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

        @Override
        public void log(int level, String tag, String message, Throwable throwable) {
            StringBuilder sb = new StringBuilder()
                    .append(fmt.format(new Date()))
                    .append(' ')
                    .append(levelChar(level))
                    .append('/')
                    .append(tag)
                    .append(": ")
                    .append(message);
            out.println(sb);
            if (throwable != null) {
                throwable.printStackTrace(out);
            }
        }
    };

    private static volatile Sink sSink = DEFAULT_SINK;

    private Log() {
    }

    public static void setSink(Sink sink) {
        sSink = (sink == null) ? DEFAULT_SINK : sink;
    }

    public static int v(String tag, String msg) {
        return println(VERBOSE, tag, msg, null);
    }

    public static int v(String tag, String msg, Throwable tr) {
        return println(VERBOSE, tag, msg, tr);
    }

    public static int d(String tag, String msg) {
        return println(DEBUG, tag, msg, null);
    }

    public static int d(String tag, String msg, Throwable tr) {
        return println(DEBUG, tag, msg, tr);
    }

    public static int i(String tag, String msg) {
        return println(INFO, tag, msg, null);
    }

    public static int i(String tag, String msg, Throwable tr) {
        return println(INFO, tag, msg, tr);
    }

    public static int w(String tag, String msg) {
        return println(WARN, tag, msg, null);
    }

    public static int w(String tag, String msg, Throwable tr) {
        return println(WARN, tag, msg, tr);
    }

    public static int e(String tag, String msg) {
        return println(ERROR, tag, msg, null);
    }

    public static int e(String tag, String msg, Throwable tr) {
        return println(ERROR, tag, msg, tr);
    }

    private static int println(int level, String tag, String msg, Throwable tr) {
        sSink.log(level, tag == null ? "" : tag, msg == null ? "" : msg, tr);
        return 0;
    }

    private static char levelChar(int level) {
        switch (level) {
            case VERBOSE:
                return 'V';
            case DEBUG:
                return 'D';
            case INFO:
                return 'I';
            case WARN:
                return 'W';
            case ERROR:
                return 'E';
            default:
                return '?';
        }
    }
}
