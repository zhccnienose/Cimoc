#!/usr/bin/env bash
#
# 把 cimoc-desktop 打成 Windows 上能直接双击运行的 exe。
#
# 产物是 build/compose/binaries/main/app/Cimoc/ 这个目录：
#     Cimoc.exe    启动器，双击即开（jpackage 生成）
#     app/         本项目与全部依赖的 jar
#     runtime/     jlink 出来的 JRE
# 默认还会压一个 Cimoc-<版本>-windows-x64.zip（--no-zip 关掉），分发时发它。
#
# ### 为什么不是"一个 exe 文件"
#
# jpackage 在 Windows 上做单文件安装包（TargetFormat.Exe / Msi）都要本机装 WiX 3.x，
# 没装直接构建失败（见 CLAUDE.md）。所以这里只做 app-image —— 它本身就是
# 「一个目录 + 里面的 Cimoc.exe」，压缩后发给别人、解压、双击，不需要装任何东西。
#
# ### 体积
#
# 运行时镜像里的 JDK 模块**一个都不裁**：图源注册靠反射扫类路径，裁了之后
# 开发期毫无症状、只在打包后「0 个图源」时才暴露（见 build.gradle 里那段注释）。
# 省体积靠的是 build.gradle 给 jlink 开的 --compress=2：运行时 130MB → 77MB。
# 想再小一点用 --slim-natives，见下。
#
# 用法:
#   ./package-exe.sh                 # 打包 + 压缩成 zip
#   ./package-exe.sh --no-zip        # 只打包
#   ./package-exe.sh --slim-natives  # 再删掉 sqlite-jdbc 里非 Windows 的原生库（约省 10MB）
#   ./package-exe.sh --clean         # 先 clean 再打（默认走增量编译）
#
# 环境变量:
#   JDK_HOME   默认 /d/java/java17。必须是**带 jpackage 的 JDK**（14+ 才有），
#              且必须显式给 Gradle —— 本机 Gradle 探测不到 JDK，不设会在配置阶段
#              报「找不到 JDK 17」（见 CLAUDE.md）。

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

JDK_HOME="${JDK_HOME:-/d/java/java17}"
JAR="$JDK_HOME/bin/jar"

IMAGE_DIR="$ROOT/build/compose/binaries/main/app/Cimoc"
BINARIES_DIR="$ROOT/build/compose/binaries/main"
APP_EXE="$IMAGE_DIR/Cimoc.exe"

DO_ZIP=1
SLIM_NATIVES=0
DO_CLEAN=0

fail() { echo "错误: $*" >&2; exit 1; }

# 用法就是文件开头那段注释，一直打到第一行非注释为止
usage() { awk 'NR == 1 { next } /^#/ { sub(/^# ?/, ""); print; next } { exit }' "$0"; }

for arg in "$@"; do
    case "$arg" in
        --no-zip)       DO_ZIP=0 ;;
        --slim-natives) SLIM_NATIVES=1 ;;
        --clean)        DO_CLEAN=1 ;;
        -h|--help)      usage; exit 0 ;;
        *)              fail "未知参数: $arg（用 -h 看用法）" ;;
    esac
done

# find 在 MSYS 下给的是 /d/work/... 这种 POSIX 路径，Windows 版 java/jar 不认，
# 必须转成 D:\work\...。同 cimoc-core/verify.sh。
winpath() { cygpath -w "$1" 2>/dev/null || echo "$1"; }

[ -x "$JDK_HOME/bin/jpackage" ] || fail "找不到 jpackage: $JDK_HOME/bin/jpackage
       它随 JDK 14+ 一起发行，用 JDK_HOME 指向一个完整的 JDK（不是 JRE）。"
[ -x "$JAR" ] || fail "找不到 jar: $JAR"

# 这一行是必须的，中文 Windows 上不加会被 jpackage 卡死。
#
# Compose 插件调 jpackage 的方式是把所有参数写进一个 @参数文件。JDK 17 的 jpackage
# 读那个文件用的是**平台默认字符集**（中文 Windows 上是 GBK），而文件里
# `--description "Cimoc 桌面端"` 是 UTF-8，解码当场抛
# MalformedInputException: Input length = 1 —— 报错就这一行，看不出跟编码有关。
#
# 没法从命令行修：参数是插件拼的，没留插参数的口子。JAVA_TOOL_OPTIONS 是环境变量，
# 任何 JVM 启动都会读，jpackage 起的那个 JVM 也读，于是默认字符集变成 UTF-8。
# （JDK 18 起默认就是 UTF-8，升上去之后这段可以删。）
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Dfile.encoding=UTF-8"

# ---------------------------------------------------------------- 1. 打包

echo "==> [1/3] Gradle: packageAppImage  (JDK: $JDK_HOME)"
# 上一轮的产物先删掉：jpackage 不会清理里面残留的旧文件，
# 删过依赖之后不删目录，包里会留着已经没用的 jar。
if ! rm -rf "$BINARIES_DIR" 2>/dev/null; then
    fail "删不掉旧产物：$BINARIES_DIR
       Windows 不允许删掉「有进程占着的」目录——最常见的是正在跑的 Cimoc.exe，
       或者某个终端（Git Bash / 资源管理器）的当前目录还在里面。退出来再跑。"
fi
GRADLE_TASKS=()
[ "$DO_CLEAN" = 1 ] && GRADLE_TASKS+=(clean)
JAVA_HOME="$JDK_HOME" ./gradlew "${GRADLE_TASKS[@]}" packageAppImage --console=plain

[ -f "$APP_EXE" ] || fail "打包结束了，但没找到 $APP_EXE
       看看 $BINARIES_DIR 下实际产出了什么（目录结构变了的话，改上面的 IMAGE_DIR）"

# ---------------------------------------------------------------- 2. 精简（可选）

# 不默认开：sqlite-jdbc 的 jar 里塞了六个平台的原生库（Windows / Linux / Mac /
# FreeBSD / musl / Android），一共 20MB 未压缩、打进去约 10MB。
# 这台的产物只可能在 Windows 上跑（compose.desktop 用的是 currentOs），所以
# 其余平台的 .so/.dylib 一定是死重量。删掉它是安全的——jdbc 驱动按
# `native/<系统>/<架构>/` 直接拼路径取库，不会去枚举别的平台。
#
# 之所以还要人手动开：这动的是依赖 jar 的内容，属于"改过的东西"，出问题的表现是
# 运行时才炸（SQLite 库加载不上）。留着 Windows 的全部架构（x86/x86_64/armv7/aarch64），
# 只删别的系统，风险面已经很小，但第一次用完请务必起一次 Cimoc.exe 确认。
slim_sqlite_natives() {
    local jar
    jar="$(ls "$IMAGE_DIR/app/"sqlite-jdbc-*.jar 2>/dev/null | head -1)"
    [ -n "$jar" ] || { echo "    跳过：app/ 下没找到 sqlite-jdbc jar"; return 0; }

    local before after tmp
    before="$(stat -c %s "$jar")"
    tmp="$(mktemp -d)"
    cp "$jar" "$tmp/sqlite-jdbc.jar"
    ( cd "$tmp" && "$JAR" xf sqlite-jdbc.jar )
    rm -rf "$tmp/org/sqlite/native/Linux" \
           "$tmp/org/sqlite/native/Linux-Android" \
           "$tmp/org/sqlite/native/Linux-Musl" \
           "$tmp/org/sqlite/native/Mac" \
           "$tmp/org/sqlite/native/FreeBSD"
    # -C 加 `.` 是验证过的写法：条目名不会带 "./" 前缀，否则
    # SQLiteJDBCLoader 按 "org/sqlite/native/..." 取资源会取不到。
    "$JAR" cfM "$(winpath "$jar")" -C "$(winpath "$tmp")" .
    rm -rf "$tmp"

    # 删完必须还能看到 Windows 那份，否则这个包等于废的。
    # 给 Windows 版 jar.exe 的路径一律过 winpath，MSYS 不会替我们转。
    "$JAR" tf "$(winpath "$jar")" | grep -q 'org/sqlite/native/Windows/x86_64/sqlitejdbc.dll' \
        || fail "精简后 sqlite-jdbc 里找不到 Windows 原生库，包已损坏"

    after="$(stat -c %s "$jar")"
    printf "    sqlite-jdbc: %.1f MB -> %.1f MB\n" \
        "$(echo "$before" | awk '{print $1/1048576}')" \
        "$(echo "$after"  | awk '{print $1/1048576}')"
}

echo "==> [2/3] 精简"
if [ "$SLIM_NATIVES" = 1 ]; then
    slim_sqlite_natives
    echo "    完成。第一次用请起一次 Cimoc.exe 确认 SQLite 还能打开（收藏/下载都靠它）"
else
    echo "    跳过（--slim-natives 可再省约 10MB）"
fi

# ---------------------------------------------------------------- 3. 体积与压缩

echo "==> [3/3] 结果"
size_of() { [ -e "$1" ] && du -sh "$1" 2>/dev/null | cut -f1 || echo "-"; }
printf "    %-22s %s\n" "Cimoc.exe" "$(size_of "$APP_EXE")"
printf "    %-22s %s\n" "app/  (依赖 jar)" "$(size_of "$IMAGE_DIR/app")"
printf "    %-22s %s\n" "runtime/ (JRE)" "$(size_of "$IMAGE_DIR/runtime")"
printf "    %-22s %s\n" "合计" "$(size_of "$IMAGE_DIR")"
echo "    可执行文件: $APP_EXE"

if [ "$DO_ZIP" = 1 ]; then
    VERSION="$(sed -n "s/^ *packageVersion *= *'\([^']*\)'.*/\1/p" build.gradle | head -1)"
    ZIP_PATH="$BINARIES_DIR/Cimoc-${VERSION:-dev}-windows-x64.zip"
    rm -f "$ZIP_PATH"
    # 用 JDK 自带的 jar 来压，不用 zip 也不用 PowerShell 的 Compress-Archive：
    # Git Bash 默认不带 zip；Compress-Archive 写进条目名的分隔符是反斜杠
    # （PowerShell 的已知毛病），跨平台解压会解出一堆名字带 \ 的文件。
    # jar 出来的就是标准 zip，条目名是正斜杠，`-C 父目录 Cimoc` 让包里是
    # Cimoc/...，解压出来是一个文件夹而不是散一地。
    # 父目录是 .../main/app（jpackage 的 --dest），不是 .../main —— Cimoc/ 在 app/ 下面。
    "$JAR" cfM "$(winpath "$ZIP_PATH")" -C "$(winpath "$(dirname "$IMAGE_DIR")")" Cimoc
    "$JAR" tf "$(winpath "$ZIP_PATH")" | grep -q '^Cimoc/Cimoc.exe$' \
        || fail "压出来的 zip 里没有 Cimoc/Cimoc.exe，包是坏的"
    printf "    %-22s %s\n" "$(basename "$ZIP_PATH")" "$(size_of "$ZIP_PATH")"
fi

echo "完成。"
