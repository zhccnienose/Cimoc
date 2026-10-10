package android.util;

import java.util.Objects;

/**
 * 桌面端兼容 shim，替代 Android 平台的 {@code android.util.Pair}。
 *
 * <p>迁移说明：图源解析层有 635 处 {@code Pair.create(...)} 调用。提供同包同名的
 * shim 可以让这些文件保持零改动，避免大规模重写带来的风险。
 *
 * <p>本类只在桌面 JVM 上编译，<b>不可</b>打进 Android 产物（会与平台类冲突）。
 */
public class Pair<F, S> {

    public final F first;
    public final S second;

    public Pair(F first, S second) {
        this.first = first;
        this.second = second;
    }

    public static <A, B> Pair<A, B> create(A a, B b) {
        return new Pair<>(a, b);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Pair)) {
            return false;
        }
        Pair<?, ?> other = (Pair<?, ?>) o;
        return Objects.equals(first, other.first) && Objects.equals(second, other.second);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(first) ^ Objects.hashCode(second);
    }

    @Override
    public String toString() {
        return "Pair{" + first + ", " + second + "}";
    }
}
