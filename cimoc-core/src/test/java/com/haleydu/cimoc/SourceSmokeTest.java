package com.haleydu.cimoc;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.haleydu.cimoc.core.CimocCore;
import com.haleydu.cimoc.model.Source;
import com.haleydu.cimoc.parser.MangaParser;
import com.haleydu.cimoc.parser.Parser;
import com.haleydu.cimoc.source.HHAAZZ;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import okhttp3.Request;

/**
 * 图源冒烟测试。
 *
 * <p>这是「核心层与 UI 解耦」重构最直接的收益：Android 版把解析逻辑和 Activity/Fragment
 * 焊死在一起，脱离设备没法跑任何验证；迁移到纯 JVM 之后，全部图源可以在 CI 里
 * 逐个加载、逐个构造、逐个拼出搜索 URL。
 *
 * <p>测试通过<b>反射扫描 {@code com.haleydu.cimoc.source} 包</b>枚举图源，而不是维护手写
 * 清单——新增图源会自动纳入覆盖，不会出现清单与代码不同步。
 *
 * <p>本测试<b>不发真实网络请求</b>，离线可跑。验证内容：
 * <ol>
 *   <li>每个图源都能被加载，且是 {@link MangaParser} 的具体子类</li>
 *   <li>都能实例化（{@code getDefaultSource()} + 构造函数，或直接无参构造）</li>
 *   <li>{@code getTitle()} 返回非空标题</li>
 *   <li>{@code getSearchRequest(关键字, 1)} 返回带 http(s) 地址的请求</li>
 * </ol>
 */
public class SourceSmokeTest {

    private static final String SOURCE_PACKAGE = "com.haleydu.cimoc.source";

    /**
     * 源码整体被注释掉的占位类，不是可用图源。
     *
     * <p>这 5 个文件在原项目里形如 {@code public class ExHentai/* extends MangaParser *&#47; {}，
     * 类体全部在块注释内，实际是个空类。迁移时原样保留，但不应计入图源数量。
     * {@link #disabledStubsStayDisabled()} 会盯着它们，一旦被重新启用就会报警。
     */
    private static final List<String> DISABLED_STUBS = Arrays.asList(
            "ExHentai", "HHSSEE", "NHentai", "Pic177", "Wnacg");

    /**
     * 没有「网络搜索」语义的图源，不做搜索断言。
     *
     * <p>新增例外必须写明理由——这是刻意的，避免用「加白名单」把真实回归糊过去。
     */
    private static final List<String> NON_SEARCHABLE = Arrays.asList(
            // 本地漫画源
            "Locality",
            // 兜底占位源，SourceManager 在遇到未知 type 时使用
            "Null");

    private static final String SEARCH_KEYWORD = "test";

    private static List<Class<?>> sourceClasses;

    @BeforeClass
    public static void setUpOnce() throws Exception {
        // 验证 Settings 注入链路：HHAAZZ 的搜索地址来自用户配置，
        // 这里给它一个测试值，证明「配置 -> 图源」这条通路是通的。
        CimocCore.install(null, (key, defaultValue) ->
                HHAAZZ.PREF_BASEURL.equals(key) ? "https://smoke-test.local" : defaultValue);

        sourceClasses = scanSourceClasses();
        assertTrue("扫描到的图源类过少，类路径或包名可能有问题", sourceClasses.size() >= 40);
    }

    @Test
    public void everySourceClassIsConcreteMangaParser() {
        for (Class<?> clazz : sourceClasses) {
            assertTrue(clazz.getName() + " 不是 MangaParser 的子类",
                    MangaParser.class.isAssignableFrom(clazz));
            assertTrue(clazz.getName() + " 是抽象类/接口，不应出现在 source 包",
                    !java.lang.reflect.Modifier.isAbstract(clazz.getModifiers()) && !clazz.isInterface());
        }
    }

    /** 占位类必须保持关闭状态——被重新启用时应当主动纳入冒烟测试，而不是被静默漏掉。 */
    @Test
    public void disabledStubsStayDisabled() throws Exception {
        for (String name : DISABLED_STUBS) {
            Class<?> clazz = Class.forName(SOURCE_PACKAGE + "." + name);
            assertFalse(name + " 已被重新启用。请把它从 DISABLED_STUBS 移除，"
                            + "并补齐它的解析实现后再纳入冒烟测试",
                    MangaParser.class.isAssignableFrom(clazz));
        }
    }

    @Test
    public void everySourceCanBeInstantiatedAndBuildSearchUrl() throws Exception {
        // TreeMap 保证失败输出顺序稳定，便于对比两次运行
        Map<String, String> failures = new TreeMap<>();
        int checked = 0;

        for (Class<?> clazz : sourceClasses) {
            String name = clazz.getSimpleName();
            try {
                Parser parser = instantiate(clazz);
                if (parser == null) {
                    failures.put(name, "无法实例化");
                    continue;
                }

                if (NON_SEARCHABLE.contains(name)) {
                    checked++;
                    continue;
                }

                String title = parser.getTitle();
                if (title == null || title.isEmpty()) {
                    failures.put(name, "getTitle() 返回空");
                    continue;
                }

                Request request = parser.getSearchRequest(SEARCH_KEYWORD, 1);
                if (request == null) {
                    failures.put(name, "getSearchRequest() 返回 null");
                } else if (request.url() == null) {
                    failures.put(name, "getSearchRequest() 的 url 为 null");
                } else if (!"http".equals(request.url().scheme())
                        && !"https".equals(request.url().scheme())) {
                    failures.put(name, "getSearchRequest() 的 url 不是 http(s): " + request.url());
                } else {
                    checked++;
                }
            } catch (Exception | LinkageError e) {
                failures.put(name, e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }

        System.out.println("[SourceSmokeTest] 通过 " + checked + " / " + sourceClasses.size() + " 个图源");
        if (!failures.isEmpty()) {
            StringBuilder sb = new StringBuilder("以下图源未通过冒烟检查:\n");
            for (Map.Entry<String, String> entry : failures.entrySet()) {
                sb.append("  - ").append(entry.getKey()).append(" -> ").append(entry.getValue()).append('\n');
            }
            fail(sb.toString());
        }
    }

    @Test
    public void everySearchableSourceHasDefaultSourceFactory() throws Exception {
        List<String> missing = new ArrayList<>();
        for (Class<?> clazz : sourceClasses) {
            if (NON_SEARCHABLE.contains(clazz.getSimpleName())) {
                continue;
            }
            try {
                clazz.getMethod("getDefaultSource");
            } catch (NoSuchMethodException e) {
                missing.add(clazz.getSimpleName());
            }
        }
        if (!missing.isEmpty()) {
            fail("以下图源缺少 public static getDefaultSource(): " + missing);
        }
    }

    // ---------------------------------------------------------------- helpers

    /** 常规图源用 {@code getDefaultSource()} + {@code 构造函数(Source)}；特殊源退回无参构造。 */
    private static Parser instantiate(Class<?> clazz) throws Exception {
        try {
            Source source = (Source) clazz.getMethod("getDefaultSource").invoke(null);
            assertNotNull(clazz.getSimpleName() + ".getDefaultSource() 返回 null", source);
            return (Parser) clazz.getConstructor(Source.class).newInstance(source);
        } catch (NoSuchMethodException e) {
            return (Parser) clazz.getConstructor().newInstance();
        }
    }

    /**
     * 扫描 source 包下的所有 .class 并加载。
     *
     * <p>刻意不引入 ClassGraph / Reflections 之类的类路径扫描库——核心库保持零多余依赖，
     * 测试用例直接读编译输出目录已经够用。
     *
     * <p>注意这里用的是 {@code getResources()}（复数）而不是 {@code getResource()}：
     * Gradle 的 Kotlin 插件默认把 Kotlin 的 class 输出到 {@code build/classes/kotlin/main}，
     * 与 Java 的 {@code build/classes/java/main} 是两个不同目录。只取第一个匹配的
     * 话，{@code MangaBZ.kt} 会被静默漏掉。
     */
    private static List<Class<?>> scanSourceClasses() throws Exception {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        String resourcePath = SOURCE_PACKAGE.replace('.', '/');

        List<Class<?>> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        boolean foundAnyRoot = false;

        Enumeration<URL> roots = loader.getResources(resourcePath);
        while (roots.hasMoreElements()) {
            URL url = roots.nextElement();
            if (!"file".equals(url.getProtocol())) {
                // jar 包场景不展开，交给打包后的集成测试覆盖
                continue;
            }
            foundAnyRoot = true;

            File dir = new File(url.toURI());
            File[] files = dir.listFiles((d, n) -> n.endsWith(".class") && n.indexOf('$') < 0);
            if (files == null) {
                continue;
            }
            for (File file : files) {
                String simpleName =
                        file.getName().substring(0, file.getName().length() - ".class".length());
                if (!seen.add(simpleName)) {
                    continue;
                }
                Class<?> clazz = Class.forName(SOURCE_PACKAGE + "." + simpleName);
                if (MangaParser.class.isAssignableFrom(clazz)) {
                    result.add(clazz);
                }
            }
        }

        if (!foundAnyRoot) {
            throw new IllegalStateException("类路径下找不到 " + resourcePath + " 目录");
        }
        result.sort(Comparator.comparing(Class::getSimpleName));
        return result;
    }
}
