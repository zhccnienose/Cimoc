package com.haleydu.cimoc.core;

import okhttp3.OkHttpClient;

/**
 * 解析层的运行时依赖注入点，用来取代 Android 版的静态单例 {@code App}。
 *
 * <p><b>这是过渡设计，不是最终形态。</b> 现在有 7 个图源类通过静态方法拿
 * {@code App.getHttpClient()} 和 {@code App.getPreferenceManager()}，而它们的构造函数
 * 只接收 {@code Source}，没有携带依赖的位置。为了不在迁移阶段改动 51 个图源类，
 * 这里提供一个显式命名的静态注入点。
 *
 * <p>与 {@code App} 的区别在于：这里没有隐藏的 Android 上下文，依赖由调用方
 * （前端或测试）显式安装，因此可以在测试里替换成假的 HttpClient，也可以在同一个
 * 进程里并存多套配置。
 *
 * <p>后续收口方向：把 {@link #httpClient()} / {@link #settings()} 变成
 * {@code MangaParser} 的实例字段，由 {@code SourceManager} 在构造解析器时注入，
 * 届时本类可以整体删除。图源类因为只依赖 {@code MangaParser} 暴露的
 * {@code httpClient()} / {@code settings()} 方法，届时无需再改。
 */
public final class CimocCore {

    /**
     * 图源用到的配置项。
     *
     * <p>取代 Android 版的 {@code PreferenceManager}——解析层只用到
     * {@code getString(key, defaultValue)} 这一个方法。
     */
    public interface Settings {

        String getString(String key, String defaultValue);

        /** 一个只返回默认值的空实现，便于测试与首次启动。 */
        Settings EMPTY = (key, defaultValue) -> defaultValue;
    }

    private static volatile OkHttpClient sHttpClient;
    private static volatile Settings sSettings = Settings.EMPTY;

    private CimocCore() {
    }

    /**
     * 安装运行时依赖。应由前端在启动时调用一次。
     *
     * @param client   HTTP 客户端；TLS、代理、DNS 等策略由调用方决定
     * @param settings 图源配置
     */
    public static void install(OkHttpClient client, Settings settings) {
        sHttpClient = client;
        sSettings = (settings == null) ? Settings.EMPTY : settings;
    }

    /**
     * 获取 HTTP 客户端，可能为 {@code null}（表示当前不允许联网）。
     *
     * <p>调用方必须处理 {@code null}；这与 Android 版「仅 WiFi 联网」的语义一致。
     */
    public static OkHttpClient httpClient() {
        return sHttpClient;
    }

    public static Settings settings() {
        return sSettings;
    }
}
