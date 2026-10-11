# Cimoc 桌面端

原 Cimoc 是一个 Android 在线漫画阅读器。本仓库正在把它改造成**桌面端项目**，前后端分离。

- 只做五件事：**收藏**、**检查收藏夹更新**、**多图源搜索**、**下载**、**支持后续添加图源**
- **不作为阅读器使用**——下载产物是 CBZ，交给第三方漫画阅读器打开
- 后端是**库**，没有命令行入口；界面是 Compose Desktop

搜索、收藏、下载、图源、设置五个界面已可用；「更新检查」仍是占位。


## 模块

| 模块 | 说明 |
|---|---|
| `cimoc-core` | 纯 JVM 解析层，42 个图源，可独立构建，零 Android 依赖 |
| `cimoc-backend` | 后端库：收藏、搜索、下载、图源注册、SQLite 持久化 |
| `cimoc-desktop` | Compose Desktop 界面。用 Gradle 复合构建引用后端，改后端不用发布 |
| `app/` | 原 Android 模块的残留资源（图标、图片、签名密钥），无源码 |
| `category.py` | 独立小工具：抓某个图源的分类清单，与后端无关 |

## 运行

需要 **JDK 17**。三个模块是彼此独立的 Gradle 构建，各带自己的 `gradlew`，用 `-p` 从仓库根调用：

```bash
JAVA_HOME=/path/to/jdk17 ./cimoc-desktop/gradlew -p cimoc-desktop run

# 图源冒烟测试：离线，不发网络请求
JAVA_HOME=/path/to/jdk17 ./cimoc-core/gradlew -p cimoc-core test

# 打包（只出 AppImage）
JAVA_HOME=/path/to/jdk17 ./cimoc-desktop/gradlew -p cimoc-desktop packageAppImage
```

不设 `JAVA_HOME` 会在配置阶段直接报「找不到 JDK 17」。

## 数据位置

```
%APPDATA%/Cimoc/
  cimoc.db      收藏、章节缓存、下载任务、设置
  download/     CBZ 成品与临时页
  .cover/       封面缓存
```

环境变量 `CIMOC_HOME` 可整体换一个数据目录，下载目录也能在「设置」页单独指定。非 Windows 下退到 `~/.cimoc`。

## 开发约定

- 源码含中文注释与中文字符串字面量，`gradle.properties` 里已固定 `-Dfile.encoding=UTF-8`。
- 不要跑 `./gradlew wrapper --gradle-version`，会把 wrapper 里的腾讯镜像冲回官方源（实测会卡死）。
- `cimoc-desktop/build.gradle` 的 `mavenCentral()` 之外还有 `google()`，删不掉：Compose 的 desktop 产物依赖 `androidx.annotation` / `androidx.collection` / `androidx.lifecycle`，这几个只在 Google 的仓库里。缺了它的报错是 `Could not find androidx.annotation:annotation:1.8.0`，看着像版本写错，实际是仓库没配对。
- 图源的结构、Android 兼容 shim、离线验证脚本见 [cimoc-core/README.md](cimoc-core/README.md)。

## 许可

[GPLv3](LICENSE.md)
