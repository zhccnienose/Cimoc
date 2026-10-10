# cimoc-core

Cimoc 漫画阅读器的**纯 JVM 核心层**，不含任何 Android 依赖。目标是把原 Android 工程里
「读图源 / 搜索 / 下载」这套逻辑抽成可被桌面客户端进程内调用的库。

## 这个模块解决什么问题

原工程把解析逻辑和 Activity/Fragment 焊死在一起，脱离 Android 设备连一次编译验证都做不了。
抽取之后最直接的收益是：**45 个图源现在可以在 CI 里离线逐个加载、构造、拼出搜索 URL**，
见 `src/test/java/com/haleydu/cimoc/SourceSmokeTest.java`。

## 目录结构

```
cimoc-core/
├── build.gradle / settings.gradle / gradle.properties / gradlew
├── verify.sh                  离线验证脚本（不依赖 Gradle）
├── libs/                      离线依赖 jar，供 verify.sh 使用（jcc 是 Gradle 也需要的那一个）
└── src/
```

```
src/main/java/
├── android/                    ← Android 兼容 shim（见下节，本次迁移的产物）
│   ├── net/Uri.java
│   └── util/{Base64,Log,Pair}.java
├── androidx/annotation/IntDef.java
└── com/haleydu/cimoc/
    ├── core/        (2)   Manga = HTTP 调度中心；CimocCore = 依赖注入点（过渡设计）
    ├── model/       (4)   Chapter / Comic / ImageUrl / Source —— 纯 POJO
    ├── parser/      (9)   Parser 接口 + MangaParser 抽象基类 + 各类遍历器
    ├── soup/        (2)   MDocument / Node，jsoup 的一层薄封装
    ├── source/      (50)  45 个可用图源 + 5 个已停用的空占位类
    └── utils/       (8)   AES 解密、JS 脚本解密、URL 编码等
```

编译产物 146 个 class（含内部类）。

## Android 兼容 shim

迁移策略是**垫片而非重写**——31 个图源文件一行未动，靠 5 个 shim 顶掉 Android 运行时 API。
这样把回归风险压到最小，代价是核心库里有 5 个顶着 `android.*` 包名的类。

| Shim | 替换 | 风险 |
|---|---|---|
| `android.util.Pair` | 635 处零改动调用点 | 低 |
| `android.util.Log` | 可插拔 `Sink`，默认写 stderr | 低 |
| `androidx.annotation.IntDef` | 纯标记注解 | 无 |
| `android.net.Uri` | 宽松解析，语义对齐 Android | **中** |
| `android.util.Base64` | flag 语义映射 | **高** |

两个高风险点单独说明：

**`Base64`**：Android 和 `java.util.Base64` 的 flag 语义**不是一一对应**的。
比如 Android 的 `DEFAULT` 编码是 76 字符换行的 MIME 编码，直接拿 `getEncoder()` 顶替会
让图源解密**静默失败**——不抛异常，只是解出垃圾数据，排查成本极高。实现里的映射表
（`DEFAULT`→`getMimeEncoder(76,"\r\n")`、`NO_WRAP`→`getEncoder()` 等）和补 padding 的
逻辑都是有意的，改动前请先看清楚。

**`Uri`**：Android 的 `Uri.parse` 比 `java.net.URI` 宽松得多，`getPath()` 的边界行为
（空 path 返回 `""` 而非 `null`）也不一样，很多图源依赖这些细节。

### 这些 shim 可以删掉吗

可以，但不是现在。正确顺序是：**先把 `git mv android/ → shim/` 之类的位置调整排进日程，
然后在图源逐个补回归测试之后，再逐个换成标准库**。在没有网络回归测试之前删 shim，
等于把「图源解析正确性」的验证全部赌在人工点检上。

## 验证

### 方式一：Gradle（标准路径，已实测通过）

```bash
cd cimoc-core
JAVA_HOME=/d/java/java8 ./gradlew test
```

> **必须用 JDK 8 跑这个构建。** Kotlin 1.5.10 的代码生成阶段在 JDK 17 上会抛后端异常，
> 这跟代码本身无关，是编译器版本问题。如果你的默认 `java` 不是 8，上面的 `JAVA_HOME=`
> 不能省，或者把 `org.gradle.java.home` 写进 `~/.gradle/gradle.properties`。

实测输出：

```
> Task :compileKotlin
> Task :compileJava
> Task :test

com.haleydu.cimoc.SourceSmokeTest > everySearchableSourceHasDefaultSourceFactory PASSED
com.haleydu.cimoc.SourceSmokeTest > disabledStubsStayDisabled PASSED
BUILD SUCCESSFUL
```

两个 Windows 相关的小坑，都已经在构建文件里处理掉了，说明一下免得被误删：

- **Guava 32+ 同时发布 android / standard-jvm 两套 variant**，Gradle 6.7.1 没法定夺，
  解析 `compileClasspath` 时会直接失败。`build.gradle` 里用
  `attribute('org.gradle.jvm.environment', 'standard-jvm')` 消除了歧义，升级到 Gradle 7+ 后可删。
- **测试输出里的中文在控制台显示为乱码**。这是 Gradle 6.7.1 在 Windows 上读取子进程
  输出时按平台编码（CP936）解码导致的，只影响控制台渲染。`build/reports/tests/test/index.html`
  里的中文是正常的 UTF-8，以那个为准。

### 方式二：verify.sh（离线，不依赖 Gradle）

```bash
cd cimoc-core
./verify.sh          # 编译 + 跑测试
./verify.sh compile  # 只编译
./verify.sh test     # 只跑测试
```

依赖 `JDK8_HOME`（默认 `/d/java/java8`）和 Gradle 缓存里的 `kotlin-compiler-embeddable`。
实测输出：

```
==> [1/3] javac: 编译 Java 主源码
    Java 类: 143
==> [2/3] kotlinc: 编译 Kotlin 主源码
    Kotlin 类: 3
    合计类: 146
==> [3/3] javac: 编译测试源码
    测试类: 1
==> JUnit
[SourceSmokeTest] 通过 45 / 45 个图源
OK (4 tests)
```

> **不要把 `verify.sh` 当成临时脚手架删掉。** 它跑得比 Gradle 快得多，适合做提交前的
> 快速反馈；而且它能验证「脱离 Gradle 也能构建」，反向确认构建里没有隐式魔法。

`libs/` 目录就是为这条路径准备的离线依赖。Gradle 路径除了 `jcc-bate-0.7.3.jar`
（繁简转换，无 Maven 坐标）之外不读它，其余依赖走 Maven 坐标。

## 冒烟测试覆盖什么

`SourceSmokeTest` 用**反射扫描 `source` 包**枚举图源，而不是维护手写清单——新增图源会
自动纳入覆盖，不会出现清单与代码不同步。**不发真实网络请求**，离线可跑。

四个断言：

1. 每个图源都是 `MangaParser` 的具体子类
2. 5 个占位类保持停用（被重新启用会主动报警）
3. 每个图源可实例化，且 `getSearchRequest(关键字, 1)` 返回 http(s) 地址
4. 每个可搜索图源都有 `public static getDefaultSource()`

有两条刻意设计的「防糊弄」机制：`NON_SEARCHABLE` 白名单（目前只有 `Locality` 本地源
和 `Null` 兜底源）要求新增例外必须写明理由；`DISABLED_STUBS` 则盯着那 5 个占位类。

## 已知问题与技术债

按处理优先级排列：

1. **构建被锁死在 Gradle 6.7.1 + JDK 8 + Kotlin 1.5.10 这个组合上。**
   原因是 Kotlin 1.5.10 在 JDK 17 上的代码生成会崩，而这套组合又和原 Android 工程一致
   （插件都已在缓存里，离线可构建）。代价是整个工具链偏旧，**长期应该整体升到
   Gradle 8 + Kotlin 1.9 + JDK 17，但那是独立的一件事，别和功能改动混在一起做。**
2. **okhttp 3.12.3 已停止维护**，带有已知 CVE。这是从原工程原样继承的版本，升级到
   4.x 需要改动 API（如 `isHttp()` 在 3.12.3 里不存在）。**这是最该优先处理的一项。**
3. **5 个图源是死占位类**（`ExHentai`、`HHSSEE`、`NHentai`、`Pic177`、`Wnacg`）：
   整个类体在原工程里就被块注释掉了，形如 `public class ExHentai/* extends MangaParser */ {}`。
   实际可用图源是 **45 个而不是 50 个**。迁移时原样保留，未擅自删除。
4. **`CimocCore` 是过渡设计。** 它是个静态持有者（`install(client, settings)` +
   `httpClient()` / `settings()`），用来顶掉原 `App.getHttpClient()` 之类的静态单例。
   正式版本应改为构造注入或依赖注入容器。见该类的 javadoc。
5. **GreenDAO 已移除，但持久层还没有替代品。** `ImageUrl.StringConverter` 里的
   `##Cimoc##` 分隔符是**已落盘数据的格式**，新持久层必须沿用，否则旧数据读不回。
6. **guava 33.0.0-jre 只为 11 个文件引入**，体积偏大，值得评估是否换成标准库。

## 前端集成方式

核心层以**进程内库**的形式暴露能力（非独立服务进程），因此要求前端本身跑在 JVM 上。
前端技术栈尚未确定——这直接影响核心层的对外接口形态，建议在选定前端后再定 API 边界。

好消息是 `parser` 层**完全同步**，没有 RxJava（原工程里只有 `Manga.java` 用了 RxJava 1.x，
且已剥离），所以对外包装成协程、`CompletableFuture` 还是阻塞调用都很自由。

## 与 Android 工程的关系

`settings.gradle` **刻意不 include `../app`**：一旦和 AGP 同构建，就很难保证
cimoc-core 不被 `android.jar` 里的 API 悄悄污染，「零 Android 依赖」这个约束会失守。
两者目前是各自独立的构建。

## 还没有做的事

- 持久层（原 GreenDAO 的替代）
- 下载器（原 `DownloadService` + `Download`，SAF 文件布局与 `index.cdif` 格式需要重新设计）
- 网络请求的失败重试/并发控制策略
- 图源的**真实网络回归测试**（当前冒烟测试只验证到「URL 拼得出来」）
