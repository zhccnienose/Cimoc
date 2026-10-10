package androidx.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 桌面端兼容 shim，替代 AndroidX 的 {@code androidx.annotation.IntDef}。
 *
 * <p>这是一个纯编译期注解。解析层的 {@code parser/Category.java} 用它标注
 * 分类维度的取值集合。也可以改为依赖真正的 {@code androidx.annotation:annotation}
 * artifact（它是纯 Java jar，桌面端可用），这里选择自行提供以避免多一个依赖。
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.ANNOTATION_TYPE, ElementType.FIELD, ElementType.LOCAL_VARIABLE,
        ElementType.METHOD, ElementType.PARAMETER})
public @interface IntDef {
    int[] value() default {};

    boolean flag() default false;
}
