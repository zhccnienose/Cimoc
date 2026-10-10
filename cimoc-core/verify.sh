#!/usr/bin/env bash
#
# cimoc-core 独立验证脚本（不依赖 Gradle、不依赖网络）。
#
# 这条路径最初是为了绕开"Gradle 发行包下载不下来"而搭的，现在 Gradle 已经能正常跑
# （发行包走腾讯镜像），但它仍有保留价值：不经过 Gradle 就能确认"源码本身是自洽的"，
# 一旦构建出问题，可以快速区分是代码问题还是构建配置问题。
#
# Kotlin 工具链的 jar 不放进 libs/，而是按 KOTLIN_VERSION 从 Gradle 缓存里解析。
# 这样升级 Kotlin 只需要改一个变量，不会像之前那样出现"libs 里还是 1.5.10、
# build.gradle 已经是 1.9.24"的版本漂移。前提是至少成功跑过一次 Gradle 构建。
#
# 用法:
#   ./verify.sh           # 编译 + 跑测试
#   ./verify.sh compile   # 只编译
#   ./verify.sh test      # 只跑测试（要求先 compile 过）
#
# 环境变量:
#   JDK_HOME           默认 /d/java/java17
#   KOTLIN_VERSION     默认 1.9.24，需与 build.gradle 里的插件版本一致
#   KOTLIN_COMPILER_JAR 显式指定 kotlin-compiler-embeddable jar，默认自动查找

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

JDK_HOME="${JDK_HOME:-/d/java/java17}"
KOTLIN_VERSION="${KOTLIN_VERSION:-1.9.24}"
JAVAC="$JDK_HOME/bin/javac"
JAVA="$JDK_HOME/bin/java"

MAIN_OUT="out"
TEST_OUT="out-test"

fail() { echo "错误: $*" >&2; exit 1; }

[ -x "$JAVAC" ] || fail "找不到 javac: $JAVAC （用 JDK_HOME 指定）"
[ -x "$JAVA" ]  || fail "找不到 java: $JAVA"

# ---------------------------------------------------------------- 路径工具
# find 在 MSYS 下返回 /c/Users/... 这种 POSIX 路径，Windows 版 java.exe 不认，
# 必须转成 C:\Users\...。libs/ 下的相对路径不用转。
winpath() { cygpath -w "$1" 2>/dev/null || echo "$1"; }

# 必须在 modules-2/ 下找：Gradle 还会在 jars-8/ 下留一份**插桩过**的副本，
# 那份的字节码引用了 org.gradle.internal.classpath.Instrumented，脱离 Gradle 直接跑会
# NoClassDefFoundError。files-2.1 里才是原始 artifact。
find_artifact() {
    find "$HOME/.gradle/caches/modules-2" -name "$1" 2>/dev/null | head -1 || true
}

# ---------------------------------------------------------------- Kotlin 工具链
resolve_kotlin() {
    local name="$1" required="$2"
    local path
    path="$(find_artifact "$name")"
    if [ -z "$path" ]; then
        [ "$required" = "required" ] && fail "找不到 $name。请先成功跑一次 Gradle 构建以填充缓存，或改小 KOTLIN_VERSION。"
        return 0
    fi
    winpath "$path"
}

KOTLIN_COMPILER_JAR="${KOTLIN_COMPILER_JAR:-$(resolve_kotlin "kotlin-compiler-embeddable-$KOTLIN_VERSION.jar" required)}"
KOTLIN_STDLIB_JAR="$(resolve_kotlin "kotlin-stdlib-$KOTLIN_VERSION.jar" required)"
KOTLIN_SCRIPT_JAR="$(resolve_kotlin "kotlin-script-runtime-$KOTLIN_VERSION.jar" optional)"
KOTLIN_REFLECT_JAR="$(resolve_kotlin "kotlin-reflect-$KOTLIN_VERSION.jar" optional)"
ANNOTATIONS_JAR="$(resolve_kotlin 'annotations-13.0.jar' required)"

[ -f "$KOTLIN_COMPILER_JAR" ] || fail "kotlin-compiler-embeddable jar 不存在: $KOTLIN_COMPILER_JAR"

# 编译器自身的类路径
KOTLIN_TOOL_CP="$KOTLIN_COMPILER_JAR;$KOTLIN_STDLIB_JAR"
[ -n "$KOTLIN_SCRIPT_JAR" ]  && KOTLIN_TOOL_CP="$KOTLIN_TOOL_CP;$KOTLIN_SCRIPT_JAR"
[ -n "$KOTLIN_REFLECT_JAR" ] && KOTLIN_TOOL_CP="$KOTLIN_TOOL_CP;$KOTLIN_REFLECT_JAR"
KOTLIN_TOOL_CP="$KOTLIN_TOOL_CP;libs/trove4j-1.0.20200330.jar;$ANNOTATIONS_JAR"

# ---------------------------------------------------------------- 类路径
# 注意：这里用 ';' 而不是 ':'。脚本在 Git Bash 里跑，但执行的是 Windows 版 java.exe，
# 它只认 Windows 的路径分隔符。libs/ 下的相对路径用正斜杠，java.exe 能正确解析。
LIBS_CP=""
for jar in libs/*.jar; do
    LIBS_CP="${LIBS_CP:+$LIBS_CP;}$jar"
done
[ -n "$LIBS_CP" ] || fail "libs/ 下没有 jar，请先补齐依赖"

# 运行期需要 Kotlin 标准库（MangaBZ.kt 编译产物依赖它）
RUNTIME_CP="$LIBS_CP;$KOTLIN_STDLIB_JAR"

# ---------------------------------------------------------------- 编译主源码
compile_main() {
    echo "==> [1/3] javac: 编译 Java 主源码  (JDK: $JDK_HOME)"
    rm -rf "$MAIN_OUT"
    mkdir -p "$MAIN_OUT"
    find src/main/java -name '*.java' > "$MAIN_OUT/.sources"
    "$JAVAC" -J-Duser.language=en -J-Duser.country=US -encoding UTF-8 -nowarn \
        --release 17 -cp "$RUNTIME_CP" -d "$MAIN_OUT" @"$MAIN_OUT/.sources"
    rm -f "$MAIN_OUT/.sources"
    echo "    Java 类: $(find "$MAIN_OUT" -name '*.class' | wc -l)"

    echo "==> [2/3] kotlinc: 编译 Kotlin 主源码  (Kotlin: $KOTLIN_VERSION)"
    # 必须先 javac 再 kotlinc：Kotlin 侧的 MangaBZ 依赖 Java 侧基类，
    # 而没有任何 Java 代码反向引用 MangaBZ（已确认），所以是单向依赖，顺序安全。
    # -Duser.language=en 避免编译器的报错信息在中文 locale 下乱码。
    "$JAVA" -Duser.language=en -Duser.country=US \
        -cp "$KOTLIN_TOOL_CP" \
        org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
        -no-stdlib -jvm-target 17 \
        -classpath "$RUNTIME_CP;$MAIN_OUT" \
        -d "$MAIN_OUT" \
        src/main/java/com/haleydu/cimoc/source/MangaBZ.kt
    echo "    Kotlin 类: $(find "$MAIN_OUT" -name 'MangaBZ*.class' | wc -l)"
    echo "    合计类: $(find "$MAIN_OUT" -name '*.class' | wc -l)"
}

# ---------------------------------------------------------------- 编译测试
compile_test() {
    echo "==> [3/3] javac: 编译测试源码"
    rm -rf "$TEST_OUT"
    mkdir -p "$TEST_OUT"
    "$JAVAC" -J-Duser.language=en -J-Duser.country=US -encoding UTF-8 -nowarn \
        --release 17 -cp "$RUNTIME_CP;$MAIN_OUT" -d "$TEST_OUT" \
        $(find src/test -name '*.java')
    echo "    测试类: $(find "$TEST_OUT" -name '*.class' | wc -l)"
}

# ---------------------------------------------------------------- 跑测试
run_tests() {
    [ -d "$MAIN_OUT" ] || fail "还没编译过，先跑 ./verify.sh compile"
    [ -d "$TEST_OUT" ] || fail "测试还没编译过，先跑 ./verify.sh compile"
    echo "==> JUnit"
    "$JAVA" -Dfile.encoding=UTF-8 -Duser.language=en -Duser.country=US \
        -cp "$RUNTIME_CP;$MAIN_OUT;$TEST_OUT" \
        org.junit.runner.JUnitCore com.haleydu.cimoc.SourceSmokeTest
}

case "${1:-all}" in
    compile) compile_main; compile_test ;;
    test)    run_tests ;;
    all)     compile_main; compile_test; run_tests ;;
    *)       fail "未知参数: $1（可用: compile | test | all）" ;;
esac
