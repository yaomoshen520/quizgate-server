package com.sex.antiaddiction.infra;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * protobuf lite 运行时按字段名反射取值：生成代码交给 schema 的是 "username_" 这样的字符串，
 * 运行时再用 getDeclaredField 找回去。JVM 下这一步永远成功，native 镜像里没注册字段就拿到空集合，
 * 报「Field username_ for com.quizgate.proto.LoginRequest not found. Known fields are []」——
 * 只有原生二进制会炸，JVM 测试全绿拦不住它。
 * <p>
 * 所以在构建期把生成包里的每个类连字段一起注册掉。按 .class 文件名扫而不是按类型扫描：
 * 这些类是构建产物，只保证存在于构建期的 classpath 上。
 */
public final class ProtoReflectionHints implements RuntimeHintsRegistrar {

    /** 与 proto/quizgate.proto 里的 java_package 一致。 */
    private static final String GENERATED_PACKAGE = "com.quizgate.proto";

    private static final MemberCategory[] NEEDED = {
            MemberCategory.DECLARED_FIELDS,
            MemberCategory.PUBLIC_FIELDS,
            MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
    };

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        var path = GENERATED_PACKAGE.replace('.', '/') + "/*.class";
        var loader = classLoader == null ? getClass().getClassLoader() : classLoader;
        Resource[] found;
        try {
            found = new PathMatchingResourcePatternResolver(loader).getResources("classpath*:" + path);
        } catch (Exception e) {
            throw new IllegalStateException("找不到 protobuf 生成类，检查 proto 模块是否参与构建", e);
        }
        for (var resource : found) {
            var name = resource.getFilename();
            if (name == null) {
                continue;
            }
            // Foo$Bar.class -> Foo$Bar；下划线开头的是编译器生成的合成类，不用注册。
            var className = name.substring(0, name.length() - ".class".length());
            if (className.startsWith("_")) {
                continue;
            }
            hints.reflection().registerType(TypeReference.of(GENERATED_PACKAGE + "." + className), NEEDED);
        }
    }
}
