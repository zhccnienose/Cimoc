# Cimoc 桌面端

原 Cimoc 是一个 Android 在线漫画阅读器。本仓库正在把它改造成**桌面端项目**，前后端分离：

- 只做五件事：**收藏**、**检查收藏夹更新**、**多图源搜索**、**下载**、**支持后续添加图源**
- **不作为阅读器使用**——下载产物是 CBZ，交给第三方漫画阅读器打开
- **只做后端库**，不做命令行入口；前端是后续工作

原 Android 的 `app/` 模块已整体移除（提交 `287acca`），仓库里只保留它的资源文件。

---

## 仓库结构

```
Cimoc/
├── cimoc-core/       纯 JVM 解析层（42 个图源），可独立构建，零 Android 依赖
├── cimoc-backend/    本次新增：后端库（Kotlin + 协程）
├── app/              原 Android 模块的残留资源（图标、图片等），无源码
├── category.py       独立小工具：抓某个图源的分类清单，与后端无关
├── screenshot/
├── LICENSE.md
└── hellovr10.jks     原 Android 签名密钥（见「安全事项」）
```

两个模块各自有 `settings.gradle` 和 `gradlew`，可**独立构建**。`cimoc-backend` 通过 Gradle
复合构建（`includeBuild('../cimoc-core')`）引用核心层——把 `com.haleydu.cimoc:cimoc-core:0.1.0`
这个坐标替换成本地构建产物，不需要把核心层降级成子模块，核心层「零 Android 依赖」这条约束
因此不会被 `android.jar` 悄悄污染。

---

## 进度总览

| 部分 | 状态 |
|---|---|
| `cimoc-core` 抽取为纯 JVM 模块 | ✅ 完成 |
| `cimoc-core` 工具链升级到 Gradle 8.7 + JDK 17 + Kotlin 1.9.24 | ✅ 完成 |
| 移除 Android `app/` 源码与构建体系 | ✅ 完成 |
| `cimoc-backend` 后端库（五功能） | 🟡 代码写完、编译通过，**真实网络链路只验证了一半** |
| Compose Desktop 前端 | ⬜ 未开始 |

### 相关提交

```
287acca  清理：移除 Android 应用源码与构建体系，仅保留资源
47e1bdf  cimoc-core: 工具链升级到 Gradle 8.7 + JDK 17 + Kotlin 1.9.24
8286aff  cimoc-core: 迁移完成，Gradle 6.7.1 + JDK 8 + Kotlin 1.5.10 验证通过
```

`cimoc-backend/` 目前**尚未提交**（工作区 untracked）。

---

## cimoc-core

纯 JVM 解析层，不含任何 Android 依赖。76 个 `.java` + 1 个 `.kt`，`source/` 下 47 个文件
（42 个可用图源 + 5 个已停用的空占位类）。

工具链上一版是 Gradle 6.7.1 + JDK 8 + Kotlin 1.5.10——整个构建被锁在 JDK 8 上，是因为
Kotlin 1.5.10 的代码生成阶段在 JDK 17 上会抛后端异常（与代码本身无关，是编译器版本问题）。
升到 Kotlin 1.9.24 后该限制解除。

保留 `src/test/java/.../SourceSmokeTest.java`：离线逐个加载、构造图源并拼出搜索 URL，
不依赖网络。`./gradlew build` 会跑它。

详见 [cimoc-core/README.md](cimoc-core/README.md)。

> ⚠️ **本模块有一处未提交的改动**：`source/Chuiyao.java` 的 `TYPE` 从 `9` 改成 `103`。
> 原因见下文「图源注册」。

---

## cimoc-backend

新增的后端库，23 个 Kotlin 文件、约 3100 行。对外只暴露 `CimocBackend` 一个门面，
其余按职责分层，前端进程内直接调用，进度通过 `Flow` 上报。

```
com.haleydu.cimoc.backend
├── CimocBackend.kt        门面：装配 DB / HTTP / 图源注册表，调 CimocCore.install()
├── ParserIds.kt           图源合成 ID 方案（原版遗留的假外键，见下）
├── http/
│   ├── HttpClientFactory.kt  OkHttpClient 构造，trust-all 与 http.strict_tls 开关
│   └── HttpEngine.kt         响应体读取（charset 探测 + 重试）、字节流下载
├── source/
│   ├── SourceRegistry.kt  反射扫描 source 包，取代原版硬编码 switch
│   └── ParserFactory.kt   按 type 构造解析器、读写图源开关
├── engine/
│   ├── SearchEngine.kt    多图源并发搜索，逐条交错产出
│   ├── ComicEngine.kt     详情 + 章节列表
│   └── UpdateChecker.kt   收藏夹更新检查
├── download/
│   ├── DownloadManager.kt 任务队列、并发上限、暂停/取消、Flow 进度
│   ├── DownloadTask.kt    任务状态机
│   ├── DownloadLayout.kt  目录布局与文件命名
│   ├── CbzWriter.kt       写 CBZ（zip 容器）
│   ├── FileNames.kt       Windows 文件名清洗与去重
│   └── JmttImage.kt       JMTT 图源的分带图片还原
└── storage/
    ├── Database.kt        连接、建表、PRAGMA user_version 迁移
    ├── AppSettings.kt     KV 表，同时实现 CimocCore.Settings
    ├── Library.kt         高层仓库：收藏、章节缓存、更新标记
    └── dao/               ComicDao / ChapterDao / DownloadTaskDao / SourceConfigDao
```

### 构建

```bash
cd cimoc-backend
./gradlew build          # 需要 JDK 17
```

Gradle wrapper 指向腾讯镜像。**不要**跑 `./gradlew wrapper --gradle-version`——那会把
`distributionUrl` 冲回官方源，实测会卡死。

### 数据模型

原 schema 没有任何索引、没有 `(source, cid)` 唯一约束，`Chapter.sourceComic` 还是用
`Long.parseLong(source + "000" + id)` 拼出来的**假外键**。因为已确定不兼容旧数据，
这里全部换成真外键 + 真约束：

- `setting` — 通用 KV，取代 SharedPreferences
- `source_config` — 只存「用户改过哪个图源的开关」，没记录就用代码里的默认值
- `comic` — `UNIQUE(source, cid)`，原版缺的就是这条
- `chapter` — `UNIQUE(comic_id, path)` 取代假外键，`ON DELETE CASCADE`
- `download_task` — `UNIQUE(chapter_id)`，同一章只有一个任务

**刻意不建的表**：`image_url`（原版是预留缓存，CBZ 落盘后图片清单只是任务的临时状态）、
`tag`/`tag_ref`（标签不在功能清单里）、`history`（不做阅读器，没有阅读历史）。

### 图源注册

原版靠两处**手写清单**注册图源：`SourceManager.getParser` 的 43 分支 switch，以及
`UpdateHelper.initSource` 的 44 行 `list.add`。这里改成反射扫描 `com.haleydu.cimoc.source`
包，判定条件是「有静态 `getDefaultSource()` 且返回非 null 的 `Source` 的 `MangaParser` 子类」，
干净地筛出 **40 个图源**。`source/` 下的 47 个类里，5 个是已注释掉的空占位类，
`Locality`（本地漫画扫描）和 `Null`（空对象）没有 `getDefaultSource()` 因此也不入表——
后两者不在本次功能清单里，所以 47 - 5 - 2 = 40。（`cimoc-core` 自己的 README 把前两者也算作
「42 个可用图源」，是同一个集合的不同数法。）

扫描必须同时支持两种类路径根，这是最容易在「开发期一切正常、打包后图源全空」时才暴露的坑：

- `file:` — 遍历目录
- `jar:` — 用 `JarURLConnection` 列举条目

而且要用 `getResources()`（**复数**）：`cimoc-core` 把 Java 和 Kotlin 编译到两个不同的输出
目录，`MangaBZ.kt` 落在 Kotlin 那个目录里，用单数 `getResource()` 会静默漏掉它。

> **一处对 `cimoc-core` 的改动**：`Chuiyao` 和 `YYLS` 都声明了 `TYPE = 9`。Android 版靠
> 「switch 里不写 `Chuiyao` 这个 case、注册清单里也不加它」把冲突破解掉了——代价是
> **`Chuiyao` 在 Android 版里从来没被注册过，等于不存在**。改成反射自动注册后，这个撞号会
> 立刻变成两个图源抢同一个 type，所以把 `Chuiyao` 换成没被占用的 `103`，让它真正可用。
> 改动是 `Chuiyao.java` 的一行（加注释），**尚未提交**。

### 下载

CBZ = 每章一个 zip，命名 `<下载根>/<图源标题>/<漫画标题>/<三位序号> <章节标题>.cbz`，
待下载的临时页放在 `<下载根>/.partial/<章节行号>/`，重启后跳过已下好的页实现续传。

CBZ 的内容约定决定第三方阅读器能不能正确翻页，所以这几条是硬要求：

- **条目名零填充**（`001.jpg`、`002.jpg`…）。阅读器普遍按条目名字典序排序，不填充的话
  `10.jpg` 会排在 `2.jpg` 前面，第 2 页和第 10 页颠倒
- **图片直接放根目录**，不套子目录（部分阅读器不递归查找）
- 先写 `.part` 再原子改名，中途崩溃留下的是明显的半成品而不是看起来正常、实际缺页的 CBZ

单页失败默认重试 4 次（原版 20 次过于激进），带线性退避。

### 与原版的差异（有意为之）

| 项目 | 原版 | 这里 |
|---|---|---|
| 落盘 | 每页一个 jpg + index.json | 每章一个 CBZ |
| 续传 | 扫目录重建 | `.partial/` 临时目录 |
| 单页重试 | 20 次，无退避 | 默认 4 次，带退避 |
| 进度上报 | RxBus + 通知栏 | `SharedFlow` |
| 解析器实例 | `SparseArray` 缓存 | **不缓存**，每次新建 |

解析器不缓存是刻意的：8 个图源（`ChuiXue`、`MangaBZ`、`Manhuatai`、`YYLS`、`IKanman`、
`JMTT`、`WebtoonDongManManHua`、`HHAAZZ`）把单次请求的状态存在**实例字段**里，
缓存实例会让并发下载互相串数据。

### 顺带修掉的原版 bug

- `Manga.getSearchResult` 对 null 作者调 `toLowerCase()` 直接 NPE，异常被外层吞掉，
  表现为「这个源莫名其妙一个结果都没有」
- `Manga.getImageUrls` 对同一个 `response.body().string()` **读了两次**（OkHttp 的 body
  只能读一次），第二次拿到空串——也就是说 `parseImages(html)` 那个回退分支**从来没生效过**
- `Request.Builder.headers(null)` 会直接 NPE，图源没有额外请求头时就崩
- `Tencent.getSearchRequest` 想拼 `word=%s` 却调成了 `.concat(keyword)`，URL 里留着一个
  字面量 `%s`（`word=%s海贼王`）。改成 `StringUtils.format` + `URLEncoder.encode(keyword, "UTF-8")`
  ——这也让方法签名上那个一直没用的 `throws UnsupportedEncodingException` 名副其实了
  （同项目的 `PuFei` 就是这么写的，属于照抄自家写法）

### 关于 `CimocCore.install()`

40 个图源类仍然通过 `CimocCore.httpClient()` / `settings()` 这两个**静态**方法取依赖
（`MangaParser` 的构造函数只收 `Source`，没有携带依赖的位置）。改成实例注入要动 40 个文件，
不在本次范围。所以由 `CimocBackend.open()` 在启动时调一次 `install`，把进程内的 HTTP 客户端
与配置装进去。

由此产生一条**必须遵守的次序**：`install` 必须在任何解析器被构造之前完成。`open()` 内部
保证了这一点。副作用是**单进程单配置**。

---

## 验证状态

### 已验证 ✅

- `cimoc-core` / `cimoc-backend` 两个模块 `./gradlew build` 均通过
- **图源注册**：jar 与分目录（`file:`）两种类路径根下都扫出同样的 40 个图源，
  36 个默认启用（`IKanman`/`CCTuku`/`MHLove`/`JMTT` 默认关闭）
- **持久层全链路**：收藏幂等、合成 ID 往返、章节重排保留行号、下载任务生命周期、
  中断任务打回暂停、更新标记、孤儿删除、外键级联
- **文件名清洗**：非法字符、保留设备名（含 `CON.cbz`）、结尾点/空格、长度截断、
  代理对不被截半、重名追加序号
- **JMTT 图片还原**：高度被 10 整除与除不尽（余数并入首条）两种情况，对照独立实现的
  逐像素参照物比对
- **CBZ 打包**：条目名零填充、字典序正确、无 `.part` 残留
- **搜索链路**：40 个图源并发跑完不崩，失败被逐个隔离上报，不影响其他源

### 未验证 / 存在缺口 ⚠️

- **下载出 CBZ 的真实网络端到端没有跑通**。这是「不作为阅读器使用」的核心验收点，
  但因为下面「图源健康度」的问题，没找到能用的图源，所以只验证了离线部分
  （打包逻辑、命名、目录布局），**没有验证「从真实站点下一章并产出可被第三方阅读器打开的
  CBZ」**
- **没有自动化测试**（按要求不编写），也没有命令行入口，所以**没有回归防线**。
  前端接上后如果某个源解析出的结果不对，只能人工点检
- 图片解码只覆盖了 JDK 原生格式。WebP/AVIF 需要额外解码器，当前若图源返回这两种格式，
  文件会原样保留（不报错，但第三方阅读器能否打开取决于该阅读器）

### 图源健康度：**这是当前最大的问题** 🔴

做了两轮检查：**Java 全链路诊断**（用后端的解析器真跑一次搜索）和
**curl 直连探测**（绕开后端，用 Chrome UA 逐个请求图源的搜索地址，15 秒预算）。
两者结论一致：**当前没有一个图源能产出可用结果。**

> 两轮诊断是在 **43 个图源**上跑的。随后依据结果删掉了 3 个确认已死的源
> （`MangaNel` / `Mangakakalot` / `MH50`，见下方 ①），所以下面的表格是**扣除它们之后**
> 的当前状态：40 个源。

#### 一、Java 全链路诊断（40 个源，每个源 25 秒预算）

```
OK 1 / 40（且那一个也是 0 条结果），FAIL 39
```

按**失败位置**归类：

| 失败位置 | 数量 | 含义 |
|---|---|---|
| 连接层（`HttpException`） | 32 | 请求根本没到达站点 |
| 拿到 HTML，但迭代器为空 | 4 | 站点活着，页面结构变了 |
| 拿到 HTML，但解析抛异常 | 1 | Dmzj：正则没匹配上，`unicodeToCn(null)` 直接 NPE |
| 拿到空响应体 | 1 | DM5（0 字节） |
| URL 构造失败 | 1 | HHAAZZ：没配置 baseurl（设计如此） |

#### 二、curl 直连探测（40 个源，绕过 cimoc-core）

| 结果 | 数量 | 图源（type） |
|---|---|---|
| HTTP 200 | 6 | Dmzj(1)、Webtoon(6)、MH57(8)、WebtoonDongManManHua(11)、QiMiaoMH(56)、MH517(70) |
| HTTP 301（重定向） | 2 | DM5(5)、Animx2(55) |
| HTTP 302 | 1 | Tencent(51) |
| HTTP 403 | 1 | PuFei(50) |
| HTTP 404 | 3 | QiManWu(53)、Hhxxee(59)、SixMH(101) |
| 连接超时 | 13 | IKanman(0)、Dmzjv2(10)、BaiNian(13)、CCMH(23)、BuKa(52)、MiGu(58)、EHentai(60)、ChuiXue(69)、Ohmanhua(71)、JMTT(72)、MangaBZ(82)、DmzjFix(100)、HotManga(102) |
| DNS 解析失败 | 4 | CCTuku(3)、U17(4)、YYLS(9)、Chuiyao(103) |
| 连接失败 | 4 | TuHao(24)、GuFeng(25)、CopyMH(26)、ManHuaDB(46) |
| TLS 握手失败 | 4 | MH160(28)、Manhuatai(49)、Cartoonmad(54)、YKMH(91) |
| 连接被 reset | 1 | MHLove(27) |
| 未发请求 | 1 | HHAAZZ(2)：baseurl 未配置，设计如此 |

跑了两遍，**每一行的 HTTP 状态码、解析到的 IP、是否可达在全部源上完全一致**，
只有耗时和「连接失败 ↔ 超时」这条边界会动（CopyMH 从 `curl=7` 变成 `curl=28`，
两者都是不可达）。所以链路是稳定的，这些结果不是网络抖动。

#### 三、交叉判定：谁死了、谁只是改版了

把两轮结果合起来看，能明确分出三类：

**① 站点已死（域名停放 / 转卖）—— 已从代码中删除**

| 图源 | 证据 | 处置 |
|---|---|---|
| MangaNel(43) / Mangakakalot(44) | 301 跳转到 `spinzywheel.com` | **已删除** |
| MH50(80) | `ManhuaDai.com is for sale \| HugeDomains` | **已删除** |
| Dmzj(1) | 返回 `Redirecting...` 停放页 | 暂留（另有 `Dmzjv2` / `DmzjFix` 走另一域名） |
| QiMiaoMH(56) | 同一个停放页（curl 加不加 UA 都是它） | 暂留 |

**② 站点可达但被拒 / 页面没了 —— 不是解析问题**

`PuFei(50)` 403、`Animx2(55)` 403、`QiManWu(53)` / `Hhxxee(59)` / `SixMH(101)` 404。

**③ 站点活着、接口或页面改版 —— 这类才是真能修的**

| 图源 | 情况 |
|---|---|
| Tencent(51) | 页面 200，但漫画列表由 **JS 渲染**——HTML 里的 `comic-item` 其实是 EJS 模板占位符（`<%=localList[i].title%>`），jsoup 按整词匹配类名命中 0 条。**修它要改走腾讯的 JSON 接口，不是改一行选择器** |
| MH57(8) | 返回的是首页而非搜索结果页，搜索地址已变 |
| 咚漫(11) | 200，页面正常，选择器过期 |
| 漫画台(49) | Java 侧拿到 23158 字符正常页面（curl 侧 TLS 失败，但 Java 走得通），选择器过期 |
| Webtoon(6) | curl 200，但 Java 侧 `HttpException` —— 原因未查明，值得单独看一眼 |

其余 27 个源在本机**连不上**（超时 / DNS / TLS / reset）。这既可能是站点下线，
也可能是本机网络到不了，**在没有第二套网络环境复测之前无法区分**——对使用者后果一样，
但排查时不能混为一谈。

#### 结论

- **后端链路本身是通的**：请求正常发出、响应正常拿到、40 个源的异常逐个隔离、互不影响
- **但当前没有一个可用的图源**。「死了」的 3 个已删、「被拒/404」的 5 个可以直接划掉；
  真正**值得先修的只有「活着但改版」的这几个**，且 Tencent 需要改走 JSON 接口、成本不低
- 剩下 27 个需要换个网络环境复测，才能判断是死是活
- **补齐可用图源是让项目真正可用的前提**——这也是把「后续添加图源」列入核心功能的现实原因

---

## 未完成

按优先级：

1. **排查并修复图源**（见上）。先动那 4 个站点还活着的（MH57 / 咚漫 / 腾讯动漫 / 漫画台），
   再换网络环境复测那 32 个连接失败的，最后清掉已死的 Dmzj / QiMiaoMH
2. **Compose Desktop 前端**——`CimocBackend` 是唯一入口，`open()` 一次拿到各子系统，
   收集 `Flow` 驱动界面
3. **提交 `Chuiyao.java` 的 type 改动**，或按你的判断决定是否保留
4. **`cimoc-backend/` 与 `.vscode/` 尚未纳入版本控制**
5. **补日志绑定**——`org.xerial:sqlite-jdbc` 拉进了 `slf4j-api` 但没有绑定，启动时会打印
   `SLF4J: Defaulting to no-operation (NOP) logger implementation`。加一个 `logback-classic`
   即可，同时也补上了原计划里的日志依赖
6. **补 `DownloadLayout` / `DownloadManager` 的端到端验证**（依赖第 1 项）

---

## 已知技术债

- **`okhttp 3.12.3` 已停止维护且有已知 CVE**，是从原工程继承的（`cimoc-core` 的 `api` 依赖）。
  本次不动，但值得排进日程
- **`fastjson 1.2.83` 在 JDK 17 上有反射风险**（`ManHuaDB` 用它），目前实测没触发，属运气
- **`CimocCore.install()` 这个静态注入点是过渡设计**，收口方向是改成构造注入（要动 40 个文件）
- **没有图源的真实网络回归测试**，前端接上后的问题只能人工定位
- **`cimoc-core` 里遗留的两处异常处理问题**（本次未动）：
  - `UicodeBackslashU.unicodeToCn(String)` 没有判空，`StringUtils.match` 匹配失败时直接 NPE
    （Dmzj 就是这么挂的）
  - `Manga.getResponseBody` 对「连不上」和「HTTP 非 2xx」抛的是同一个 `NetworkErrorException`，
    排查时分不清是网络问题还是站点返回了错误码

---

## 安全事项

> 这两条是**有意保留**的原版行为，不是疏忽，但代价必须说清楚。

1. **TLS 默认 trust-all**。`HttpClientFactory` 默认装配 trust-all 的 `X509TrustManager` +
   绕过主机名校验，与原 Android 版行为一致，用来保证图源可用性。

   **代价**：默认状态下客户端不验证服务器证书，**下载内容可被中间人篡改且无法察觉**。
   这与原版一致，但原版是手机 App，桌面端的使用场景（公共 Wi-Fi、公司网络）不一定相同。

   唯一的安全出口是配置项 `http.strict_tls=true`，置 true 后换成 JDK 默认的
   `SSLSocketFactory` 与主机名校验。这个开关是**真的接上去的**（在 `CimocBackend.open()`
   里读取并作用于客户端构造），不是摆设。注意它只在**启动时读一次**，改这个开关需要重开后端。

2. **仓库里有签名密钥**：`hellovr10.jks` 与 `app/.key.jks` 是被有意保留下来的原 Android
   签名文件。**删掉文件并不能把它们从 git 历史里移除**。如果这两个密钥还需要继续使用，
   真正的补救办法是轮换密钥 + 重写历史。

3. 下载的文件与压缩包一律视为不可信数据；`CbzWriter` 只做打包，不做解包。

---

## 开发约定

- 两个模块都需要 **JDK 17**。如果默认 `java` 不是 17，在 `cimoc-backend/gradle.properties` 里
  取消注释 `org.gradle.java.home` 并改成你的路径。

  **不要**写进用户级的 `~/.gradle/gradle.properties`——那会影响机器上所有 Gradle 项目，
  包括本仓库里的 Android 工程（AGP 4.2.2 需要 JDK 8/11，会被强制 JDK 17 弄坏）。

- 不要跑 `./gradlew wrapper --gradle-version`，会覆盖 wrapper 里的腾讯镜像。
- 源码含中文注释与中文字符串字面量，`gradle.properties` 里已固定 `-Dfile.encoding=UTF-8`。
