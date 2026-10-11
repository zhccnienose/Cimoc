# Cimoc 桌面端

原 Cimoc 是一个 Android 在线漫画阅读器。本仓库正在把它改造成**桌面端项目**，前后端分离。

- 只做五件事：**收藏**、**检查收藏夹更新**、**多图源搜索**、**下载**、**支持后续添加图源**
- **不作为阅读器使用**——下载产物是 CBZ，交给第三方漫画阅读器打开
- 后端是**库**，没有命令行入口；界面是 Compose Desktop

原 Android 的 `app/` 模块已整体移除（提交 `287acca`），仓库里只保留它的资源文件。

---

## 仓库结构

```
Cimoc/
├── cimoc-core/       纯 JVM 解析层（42 个图源），可独立构建，零 Android 依赖
├── cimoc-backend/    后端库（Kotlin + 协程），23 个文件 / 3323 行
├── cimoc-desktop/    桌面界面（Compose Desktop），8 个文件 / 1317 行
├── app/              原 Android 模块的残留资源（图标、图片等），无源码
├── category.py       独立小工具：抓某个图源的分类清单，与后端无关
├── screenshot/
├── LICENSE.md
└── hellovr10.jks     原 Android 签名密钥（见「安全事项」）
```

三个模块各自有 `settings.gradle` 和 `gradlew`，可**独立构建**。依赖靠 Gradle 复合构建串起来：

```
cimoc-desktop ──includeBuild──▶ cimoc-backend ──includeBuild──▶ cimoc-core
```

把 `com.haleydu.cimoc:cimoc-*:0.1.0` 这些坐标替换成本地构建产物，中间层不用发布到任何仓库，
核心层「零 Android 依赖」这条约束也因此不会被 `android.jar` 污染。

`cimoc-desktop` 只写了一句 `includeBuild('../cimoc-backend')`，**不需要**再写
`includeBuild('../cimoc-core')`——嵌套复合构建是传递的，实测验过（Gradle 8.7）。

---

## 运行

```bash
cd cimoc-desktop
JAVA_HOME="D:/java/java17" ./gradlew run
```

**数据位置**（用户会问的第一件事，所以定成固定规则）：

```
%APPDATA%/Cimoc/
├── cimoc.db      收藏、章节缓存、下载任务、设置
├── download/     CBZ 成品与 .partial 临时页
└── .cover/       封面磁盘缓存
```

选 `%APPDATA%` 而不是 exe 同级目录：Program Files 下的目录默认不可写，「装在 Program Files
就用不了」是个很常见的坑。环境变量 `CIMOC_HOME` 可以整体覆盖（调试、便携模式、跑第二份
互不干扰的数据）。

打包用 `./gradlew packageAppImage` 或 `createDistributable`。**不要用 `packageMsi`**——
Windows 上打 MSI 需要本机装着 WiX 3.x，没装会直接失败。

---

## 完成进度

| 部分 | 状态 |
|---|---|
| `cimoc-core` 抽取为纯 JVM 模块 | ✅ 完成 |
| `cimoc-core` 工具链升级到 Gradle 8.7 + JDK 17 + Kotlin 1.9.24 | ✅ 完成 |
| 移除 Android `app/` 源码与构建体系 | ✅ 完成 |
| `cimoc-backend` 后端库（五功能） | 🟡 代码写完、编译通过，**真实网络链路只验证了一半** |
| Compose Desktop 前端：工程骨架 + 应用外壳 | ✅ 完成（能启动、能开后端、六个入口可切换、下载角标实时） |
| Compose Desktop 前端：搜索页 + 详情/章节页 | ✅ 完成（搜索、结果网格、简介与章节多选、入队下载） |
| Compose Desktop 前端：收藏夹 / 下载 / 图源 / 设置 | ✅ 完成 |
| Compose Desktop 前端：更新检查 | ⬜ 仍是空占位 |

### 近期提交

```
a960a27  docs: 重写根 README，记录后端进度、图源健康度与未完成项
2cd1973  cimoc-backend: 新增后端库（收藏 / 检查更新 / 多源搜索 / 下载 / 图源注册）
7d2f95e  cimoc-core: 删除 3 个已停站的图源，可用图源 45 → 42
9204ac1  cimoc-core: 修复 Chuiyao 的 TYPE 冲突与 Tencent 搜索 URL 的 %s
287acca  清理：移除 Android 应用源码与构建体系，仅保留资源
47e1bdf  cimoc-core: 工具链升级到 Gradle 8.7 + JDK 17 + Kotlin 1.9.24
8286aff  cimoc-core: 迁移完成，Gradle 6.7.1 + JDK 8 + Kotlin 1.5.10 验证通过
```

---

## 数据结构

**不兼容原 Android 数据，全新设计**：原 schema 没有任何索引、没有 `(source, cid)` 唯一约束，
`Chapter.sourceComic` 还是用 `Long.parseLong(source + "000" + id)` 拼出来的**假外键**。
这里全部换成真外键 + 真约束。

| 表 | 关键约束 / 用途 |
|---|---|
| `setting` | `key TEXT PRIMARY KEY`，通用 KV，取代 SharedPreferences |
| `source_config` | `type INTEGER PRIMARY KEY`，只存「用户改过哪个图源的开关」，没记录就用代码里的默认值 |
| `comic` | `UNIQUE(source, cid)`；`favorite_at` 非 NULL 即已收藏；`highlight` 标记有新章节 |
| `chapter` | `UNIQUE(comic_id, path)` 取代假外键，`ON DELETE CASCADE` |
| `download_task` | `UNIQUE(chapter_id)`，同一章只有一个任务；`state` / `progress` / `total` / `file_path` |

- `PRAGMA foreign_keys = ON`；迁移用 `PRAGMA user_version`
- **刻意不建**：`image_url`（CBZ 落盘后图片清单只是任务的临时状态）、`tag`/`tag_ref`
  （标签不在功能清单里）、`history`（不做阅读器，没有阅读历史）
- **合成 ID**：`sourceComic = source + "000" + comicRowId`，
  `chapterId = sourceComic + "000" + index`

---

## 图源注册

反射扫描 `com.haleydu.cimoc.source`，取代原版两处**手写清单**（`SourceManager.getParser` 的
43 分支 switch、`UpdateHelper.initSource` 的 44 行 `list.add`）。判定条件是「有静态
`getDefaultSource()` 且返回非 null 的 `MangaParser` 子类」，筛出 **40 个**。

两个必须注意的点：

- **类路径根要同时支持 `file:` 和 `jar:`**，且必须用 `getResources()`（**复数**）——Java 与
  Kotlin 编译到两个不同的输出目录，`MangaBZ.kt` 落在 Kotlin 那个目录里，单数
  `getResource()` 会静默漏掉它。踩了这个坑的表现是「开发期一切正常、打包后图源全空」
- `Chuiyao` 和 `YYLS` 都声明了 `TYPE = 9`。Android 版靠「switch 里不写 `Chuiyao`、注册清单里
  也不加它」把冲突藏起来——代价是**该源在 Android 版里从来没被注册过**。反射注册会让两者
  抢号，所以 `Chuiyao` 改用没被占用的 `103`（提交 `9204ac1`）

---

## 下载

CBZ = 每章一个 zip，命名 `<下载根>/<图源标题>/<漫画标题>/<三位序号> <章节标题>.cbz`，
待下载的临时页放在 `<下载根>/.partial/<章节行号>/`，重启后跳过已下好的页实现续传。
单页失败默认重试 4 次（原版 20 次过于激进），带线性退避。

CBZ 的内容约定决定第三方阅读器能不能正确翻页，所以这三条是硬要求：

- **条目名零填充**（`001.jpg`、`002.jpg`…）——阅读器普遍按条目名字典序排序，不填充的话
  `10.jpg` 会排在 `2.jpg` 前面
- **图片直接放根目录**，不套子目录（部分阅读器不递归查找）
- 先写 `.part` 再原子改名，中途崩溃留下的是明显的半成品，而不是看起来正常、实际缺页的 CBZ

---

## 未完成

> **口径**：图源的**网络可达性不计入待办**。本机连不上的那批源（超时 / DNS / TLS / reset）
> 在没有第二套网络环境复测之前判断不了，是环境问题而不是项目工作。下面每条都是
> 「换一套网络、或者站点自己恢复」之后依然要动手的欠账。

**代码与功能**

1. **Compose Desktop 前端还剩「更新检查」一个界面**——**搜索页、详情/章节页、收藏夹、下载页、
   图源页、设置页**已可用（搜索 → 结果网格 → 简介与章节多选 → 入队下载 → 下载页看每章的进度；
   收藏会把简介和章节目录写成 JSON 快照，收藏夹点开读那份快照，不发网络请求；设置页可改下载
   目录、收藏页标题字号等，并能把设置导入导出成 JSON 配置文件）。`CimocBackend` 是唯一入口，
   `open()` 一次拿到各子系统，收集 `Flow` 驱动界面。剩下的页面照 [FRONTEND.md](FRONTEND.md)
   第三节的接法写
2. **下载出 CBZ 的真实端到端验证**（未做）——「解析章节 → 下图片 → 出 CBZ → 第三方阅读器
   能打开」只验证了离线部分（打包、命名、目录布局）。它需要一个能返回结果的图源才能跑通，
   所以挂在第 4 项后面
3. **补日志绑定**——`org.xerial:sqlite-jdbc` 拉进了 `slf4j-api` 但没有绑定，启动时会打印
   `SLF4J: Defaulting to no-operation (NOP) logger implementation`。加一个 `logback-classic`
   即可，同时也补上了原计划里的日志依赖

**图源解析（站点可达、只是解析过期——与网络无关）**

4. **修 4 个「页面 / 接口改版」的源**：`MH57(8)`、`咚漫(11)`、`腾讯动漫(51)`、`漫画台(49)`。
   其中 **Tencent 的漫画列表由 JS 渲染**，HTML 里的 `comic-item` 其实是 EJS 模板占位符
   （`<%=localList[i].title%>`），jsoup 命中 0 条——**它要改走腾讯的 JSON 接口，
   不是改一行选择器**，成本明显高于其余三个
5. **`漫画柜 IKanman(0)` 的搜索选择器过期**——代码里是 `li.cf` / `a.bcover` / `.book-detail`，
   当前页面是 `div.cont-list > ul#detail > li`（标题在 `h3`、封面是惰性的 `img[data-src]`、
   链接 `/comic/60452/`），命中 0 次，搜索会解析出 0 条。**工作区里已有未提交的改动**
   （顺手删掉了文件末尾那 150 行注释掉的 `Category` 内部类），但选择器还没改
6. **`Webtoon(6)` Java 侧 `HttpException` 的原因未查明**——curl 直连 200，Java 侧却抛异常，
   和选择器无关，值得单独查一次
7. **清掉两个已确认的域名停放源**：`Dmzj(1)`、`QiMiaoMH(56)`（当前状态是「暂留」）

**仓库**

8. **6 个提交还没推**（见「近期提交」）
9. **`test.html` 与 `test_result/` 未纳入版本控制**——抓图源页面留下的 HTML 转储，
   要么写进 `.gitignore`，要么删掉

### 图源现状一览

40 个源，本机跑下来**没有一个能返回可用结果**。按可动性分成三类：

- **已删**：`MangaNel(43)` / `Mangakakalot(44)` / `MH50(80)`——域名停放或转卖
- **站点可达、只是解析过期**：第 4~6 项的那几个，**这才是能动手的**
- **本机连不上**：26 个（超时 / DNS / TLS / reset，另有 `HHAAZZ(2)` 因 baseurl 未配置根本没
  发请求）。是下线还是到不了，**没有第二套网络环境复测之前无法区分**，不计入待办

---

## 已知技术债

- **`okhttp 3.12.3` 已停止维护且有已知 CVE**——从原工程继承（`cimoc-core` 的 `api` 依赖），
  本次不动，但值得排进日程
- **`fastjson 1.2.83` 在 JDK 17 上有反射风险**（`ManHuaDB` 用它），目前实测没触发，属运气
- **`CimocCore.install()` 这个静态注入点是过渡设计**——40 个图源类仍静态取
  `httpClient()` / `settings()`，收口方向是改成构造注入（要动 40 个文件）。由此产生一条
  **必须遵守的次序**：`install` 必须在任何解析器被构造之前完成（`CimocBackend.open()` 内部
  保证了这点），副作用是单进程单配置
- **没有回归测试**（按要求不编写），也没有命令行入口，前端接上后如果某个源解析出的结果不对，
  只能人工点检
- **`cimoc-core` 里遗留的两处异常处理问题**（本次未动）：`UicodeBackslashU.unicodeToCn(String)`
  没有判空，`StringUtils.match` 匹配失败时直接 NPE；`Manga.getResponseBody` 对「连不上」和
  「HTTP 非 2xx」抛的是同一个 `NetworkErrorException`，排查时分不清是网络问题还是站点返回了错误码

---

## 安全事项

> 这两条是**有意保留**的原版行为，不是疏忽，但代价必须说清楚。

1. **TLS 默认 trust-all**。`HttpClientFactory` 默认装配 trust-all 的 `X509TrustManager` +
   绕过主机名校验，与原 Android 版行为一致，用来保证图源可用性。

   **代价**：默认状态下客户端不验证服务器证书，**下载内容可被中间人篡改且无法察觉**。
   这与原版一致，但原版是手机 App，桌面端的使用场景（公共 Wi-Fi、公司网络）不一定相同。

   唯一的安全出口是配置项 `http.strict_tls=true`，置 true 后换成 JDK 默认的 `SSLSocketFactory`
   与主机名校验。这个开关是**真的接上去的**（在 `CimocBackend.open()` 里读取并作用于客户端
   构造），不是摆设。注意它只在**启动时读一次**，改这个开关需要重开后端。

2. **仓库里有签名密钥**：`hellovr10.jks` 与 `app/.key.jks` 是被有意保留下来的原 Android
   签名文件。**删掉文件并不能把它们从 git 历史里移除**。如果这两个密钥还需要继续使用，
   真正的补救办法是轮换密钥 + 重写历史。

3. 下载的文件与压缩包一律视为不可信数据；`CbzWriter` 只做打包，不做解包。

---

## 开发约定

- 三个模块都需要 **JDK 17**。构建时先设 `JAVA_HOME`：

  ```bash
  JAVA_HOME="D:/java/java17" ./gradlew build
  ```

  不设会在**配置阶段**就报 `Cannot find a Java installation matching {languageVersion=17}`
  —— Windows 注册表里注册的 `D:\java` 只有 `java17` / `java8` 两个子目录、本身没有 java
  可执行文件，Gradle 的 toolchain 自动探测认不出来。**是探测不到，不是没装**。

  也可以改 `cimoc-backend/gradle.properties` 里的 `org.gradle.java.home`。**不要**写进用户级的
  `~/.gradle/gradle.properties`——那会影响机器上所有 Gradle 项目，包括同仓库里需要
  JDK 8/11 的 Android 工程（AGP 4.2.2 会被强制 JDK 17 弄坏）。

- 不要跑 `./gradlew wrapper --gradle-version`，会把 wrapper 里的腾讯镜像冲回官方源（实测会卡死）。
- 源码含中文注释与中文字符串字面量，`gradle.properties` 里已固定 `-Dfile.encoding=UTF-8`。
- **`cimoc-desktop/build.gradle` 的 `mavenCentral()` 之外还有 `google()`，删不掉**：Compose 的
  desktop 产物依赖 `androidx.annotation` / `androidx.collection` / `androidx.lifecycle`，这几个
  只在 Google 的仓库里。缺了它的报错是 `Could not find androidx.annotation:annotation:1.8.0`，
  看起来像版本写错，实际是仓库没配对。另外 `maven.google.com` 在部分网络下连不通，但 Gradle 的
  `google()` 走的是 `dl.google.com/dl/android/maven2`，不受影响。
- 详见 [cimoc-core/README.md](cimoc-core/README.md)。
