#!/usr/bin/env bash
#
# cimoc-core 独立验证脚本（不依赖 Gradle / 不依赖网络）。
#
# 为什么需要它：本模块最初是从 Android 工程里剥出来的，迁移过程中 Gradle 发行包
# 一直下载不下来，而"代码到底能不能编译、图源到底能不能加载"必须有个可复现的答案。
# 于是直接用 javac + kotlinc + JUnit 搭了这条最小验证路径，全程只用 libs/ 下的 jar。
#
# 在 build.gradle 可用之后，这个脚本仍有价值：
#   - CI 里跑得比 Gradle 快得多，适合做快速反馈
#   - 能验证"脱离 Gradle 也能构建"，反向确认没有隐式的构建魔法
#
# 用法:
#   ./verify.sh           # 编译 + 跑测试
#   ./verify.sh compile   # 只编译
#   ./verify.sh test      # 只跑测试（要求先 compile 过）
#
# 环境变量:
#   JDK8_HOME           Kotlin 1.5.10 需要 JDK 8 做代码生成，默认 /d/java/java8
#   KOTLIN_COMPILER_JAR 显式指定 kotlin-compiler-embeddable jar，默认从 Gradle 缓存里找

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

JDK8_HOME="${JDK8_HOME:-/d/java/java8}"
JAVAC="$JDK8_HOME/bin/javac"
JAVA="$JDK8_HOME/bin/java"

MAIN_OUT="out"
TEST_OUT="out-test"

fail() { echo "错误: $*" >&2; exit 1; }

[ -x "$JAVAC" ] || fail "找不到 JDK 8 的 javac: $JAVAC （用 JDK8_HOME 指定）"
[ -x "$JAVA" ]  || fail "找不到 JDK 8 的 java: $JAVA"

# ---------------------------------------------------------------- 类路径
# 注意：这里用 ';' 而不是 ':'。脚本在 Git Bash 里跑，但执行的是 Windows 版 java.exe，
# 它只认 Windows 的路径分隔符。路径本身用正斜杠的相对路径，java.exe 能正确解析。
LIBS_CP=""
for jar in libs/*.jar; do
    LIBS_CP="${LIBS_CP:+$LIBS_CP;}$jar"
done
[ -n "$LIBS_CP" ] || fail "libs/ 下没有 jar，请先补齐依赖"

# ---------------------------------------------------------------- Kotlin 编译器
# 46MB 的编译器 jar 不适合塞进 libs/（它是构建工具，不是运行时依赖），
# 所以从 Gradle 缓存里定位。缓存路径带哈希，只能 glob。
#
# 必须在 modules-2/ 下找：Gradle 还会在 jars-8/ 下留一份**插桩过**的副本，
# 那份的字节码引用了 org.gradle.internal.classpath.Instrumented，脱离 Gradle 直接跑会
# NoClassDefFoundError。files-2.1 里才是原始 artifact。
find_artifact() {
    find "$HOME/.gradle/caches/modules-2" -name "$1" 2>/dev/null | head -1 || true
}

if [ -z "${KOTLIN_COMPILER_JAR:-}" ]; then
    KOTLIN_COMPILER_JAR="$(find_artifact 'kotlin-compiler-embeddable-1.5.10.jar')"
fi
[ -n "${KOTLIN_COMPILER_JAR:-}" ] && [ -f "$KOTLIN_COMPILER_JAR" ] \
    || fail "找不到 kotlin-compiler-embeddable-1.5.10.jar，请用 KOTLIN_COMPILER_JAR 指定"

ANNOTATIONS_JAR="$(find_artifact 'annotations-13.0.jar')"
[ -n "$ANNOTATIONS_JAR" ] || fail "找不到 annotations-13.0.jar（Kotlin 编译器做代码生成时需要）"

# find 在 MSYS 下返回 /c/Users/... 这种 POSIX 路径，Windows 版 java.exe 不认，
# 必须转成 C:\Users\...。libs/ 下的相对路径不用转（正斜杠相对路径 java.exe 能解析）。
winpath() { cygpath -w "$1" 2>/dev/null || echo "$1"; }

KOTLIN_CP="$(winpath "$KOTLIN_COMPILER_JAR");libs/kotlin-stdlib-1.5.10.jar;libs/kotlin-reflect-1.5.10.jar;libs/kotlin-script-runtime-1.5.10.jar;libs/trove4j-1.0.20200330.jar;$(winpath "$ANNOTATIONS_JAR")"

# ---------------------------------------------------------------- 编译主源码
compile_main() {
    echo "==> [1/3] javac: 编译 Java 主源码"
    rm -rf "$MAIN_OUT"
    mkdir -p "$MAIN_OUT"
    # shellcheck disable=SC2046
    find src/main/java -name '*.java' > "$MAIN_OUT/.sources"
    "$JAVAC" -J-Duser.language=en -J-Duser.country=US -encoding UTF-8 -source 8 -target 8 -nowarn \
        -cp "$LIBS_CP" -d "$MAIN_OUT" @"$MAIN_OUT/.sources"
    rm -f "$MAIN_OUT/.sources"
    echo "    Java 类: $(find "$MAIN_OUT" -name '*.class' | wc -l)"

    echo "==> [2/3] kotlinc: 编译 Kotlin 主源码"
    # 必须先 javac 再 kotlinc：Kotlin 侧的 MangaBZ 依赖 Java 侧基类，
    # 而没有任何 Java 代码反向引用 MangaBZ（已确认），所以单向依赖，顺序安全。
    # -J-Duser.language=en: 避免 Kotlin 编译器的中文/乱码报错信息污染终端。
    "$JAVA" -Duser.language=en -Duser.country=US \
        -cp "$KOTLIN_CP" \
        org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
        -no-stdlib -jvm-target 1.8 \
        -classpath "$LIBS_CP;$MAIN_OUT" \
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
    "$JAVAC" -J-Duser.language=en -J-Duser.country=US -encoding UTF-8 -source 8 -target 8 -nowarn \
        -cp "$LIBS_CP;$MAIN_OUT" -d "$TEST_OUT" \
        $(find src/test -name '*.java')
    echo "    测试类: $(find "$TEST_OUT" -name '*.class' | wc -l)"
}

# ---------------------------------------------------------------- 跑测试
run_tests() {
    [ -d "$MAIN_OUT" ] || fail "还没编译过，先跑 ./verify.sh compile"
    [ -d "$TEST_OUT" ] || fail "测试还没编译过，先跑 ./verify.sh compile"
    echo "==> JUnit"
    "$JAVA" -Dfile.encoding=UTF-8 -Duser.language=en -Duser.country=US \
        -cp "$LIBS_CP;$MAIN_OUT;$TEST_OUT" \
        org.junit.runner.JUnitCore com.haleydu.cimoc.SourceSmokeTest
}

case "${1:-all}" in
    compile) compile_main; compile_test ;;
    test)    run_tests ;;
    all)     compile_main; compile_test; run_tests ;;
    *)       fail "未知参数: $1（可用: compile | test | all）" ;;
esac
