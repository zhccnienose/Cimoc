# cimoc-core

Cimoc 的**纯 JVM 核心层**，零 Android 依赖。把原 Android 工程里的「图源解析 / 搜索 / 下载」
抽成库，供桌面端进程内调用——脱离 Android 设备也能编译、加载、验证。当前 **42 个可用图源**。

## 目录结构

```
cimoc-core/
├── build.gradle / settings.gradle / gradle.properties / gradlew
├── verify.sh                  离线验证脚本（不依赖 Gradle）
├── libs/                      离线依赖 jar，只给 verify.sh 用
└── src/
    ├── main/java/
    │   ├── android/           Android 兼容 shim（见下节）
    │   ├── androidx/annotation/IntDef.java
    │   └── com/haleydu/cimoc/
    │       ├── core/     (2)  Manga = HTTP 调度中心；CimocCore = 依赖注入点（过渡设计）
    │       ├── model/    (4)  Chapter / Comic / ImageUrl / Source —— 纯 POJO
    │       ├── parser/   (9)  Parser 接口 + MangaParser 基类 + 各类遍历器
    │       ├── soup/     (2)  MDocument / Node，jsoup 的薄封装
    │       ├── source/   (47) 42 个可用图源 + 5 个已停用的空占位类
    │       └── utils/    (8)  AES / JS 脚本解密、URL 编码等
    └── test/java/             冒烟测试
```

`.java` 与 `.kt` 混放在 `src/main/java` 下，沿用原 Android 工程习惯。编译产物 137 个 class。

## Android 兼容 shim

迁移走**垫片而非重写**：31 个图源文件一行未动，靠 5 个小类顶掉 Android 运行时 API，
把回归风险压到最小。代价是核心库里有 5 个顶着 `android.*` 包名的类。

| Shim | 替换 | 风险 |
|---|---|---|
| `android.util.Pair` | 635 处零改动调用点 | 低 |
| `android.util.Log` | 可插拔 `Sink`，默认写 stderr | 低 |
| `androidx.annotation.IntDef` | 纯标记注解 | 无 |
| `android.net.Uri` | 宽松解析，语义对齐 Android | 中 |
| `android.util.Base64` | flag 语义映射 | **高** |

两个高风险点：

- **`Base64`**：Android 与 `java.util.Base64` 的 flag 语义**不是一一对应**的。Android 的
  `DEFAULT` 是 76 字符换行的 MIME 编码，直接拿 `getEncoder()` 顶替会让图源解密**静默失败**
  ——不抛异常，只解出垃圾数据。实现里的映射表（`DEFAULT`→`getMimeEncoder(76,"\r\n")`、
  `NO_WRAP`→`getEncoder()`）和补 padding 逻辑都是有意的，改动前先看清楚。
- **`Uri`**：Android 的 `Uri.parse` 比 `java.net.URI` 宽松得多，`getPath()` 的边界行为
  （空 path 返回 `""` 而非 `null`）也不一样，不少图源依赖这些细节。

**什么时候能删**：等图源有了网络回归测试之后，再逐个换成标准库。在那之前删 shim，
等于把「解析正确性」全赌在人工点检上。

## 构建与验证

工具链：**Gradle 8.7 + JDK 17 + Kotlin 1.9.24**。

```bash
cd cimoc-core
JAVA_HOME=/d/java/java17 ./gradlew test
```

`JAVA_HOME` 不能省——Gradle 探测不到本机 JDK，不设会在配置阶段直接报「找不到 JDK 17」。
也不要写进用户级的 `~/.gradle/gradle.properties`，那会把 JDK 17 钉给同机所有工程。
Gradle 发行包走腾讯镜像，**别动 wrapper**（`--gradle-version` 会把镜像冲回官方源并卡死）。

离线路径，不经过 Gradle：

```bash
./verify.sh          # 编译 + 测试
./verify.sh compile  # 只编译
./verify.sh test     # ⚠️ 复用上次的 out/，不重新编译
```

`./verify.sh test` 读到的是**上一次编译的 class**，改过（尤其删过）源文件要先跑
`./verify.sh` 或 `./verify.sh compile`。环境变量：`JDK_HOME`（默认 `/d/java/java17`）、
`KOTLIN_VERSION`（默认 `1.9.24`，须与 `build.gradle` 一致，首次需联网从 Gradle 缓存解析）。

它和 Gradle 是两条独立路径，这不是冗余：**Gradle 出问题时，它能立刻区分是代码问题还是
构建配置问题**。

预期结果：编译 137 个类，冒烟测试 42/42 通过（`OK (4 tests)`）。

## 冒烟测试

`SourceSmokeTest` 用**反射扫描 `source` 包**枚举图源，不维护手写清单——新增图源自动纳入覆盖。
**不发网络请求**，离线可跑。四条断言：

1. 每个图源都是 `MangaParser` 的具体子类
2. 5 个占位类保持停用（被重新启用会主动报警）
3. 每个图源可实例化，且 `getSearchRequest(关键字, 1)` 返回 http(s) 地址
4. 每个可搜索图源都有 `public static getDefaultSource()`

另有两道「防糊弄」机制：`NON_SEARCHABLE` 白名单（目前只有 `Locality` 本地源和 `Null` 兜底源）
要求新增例外必须写明理由；`DISABLED_STUBS` 盯着那 5 个占位类。

注意它只验证到「URL 拼得出来」，**不验证解析结果是否正确**。
