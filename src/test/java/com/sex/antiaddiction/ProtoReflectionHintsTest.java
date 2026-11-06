package com.sex.antiaddiction;

import com.quizgate.proto.LoginRequest;
import com.sex.antiaddiction.infra.ProtoReflectionHints;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.TypeReference;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * native 镜像里 protobuf lite 是靠反射读生成类字段工作的，而这个 hints 注册器是唯一把那份元数据喂给镜像的地方。
 * 它扫空了不会报错，只会在原生二进制第一次收到请求时才炸——所以这里用 .proto 原文当独立事实源，
 * 而不是复用注册器自己的扫描结果（那样它扫不到东西测试也照样绿）。
 */
class ProtoReflectionHintsTest {

    private static final Pattern TOP_LEVEL = Pattern.compile("^(message|enum)\\s+(\\w+)\\s*\\{");

    @Test
    void proto文件里每个消息与枚举都拿到了字段反射注册() {
        var hints = new RuntimeHints();
        new ProtoReflectionHints().registerHints(hints, getClass().getClassLoader());

        var names = declaredInProto();
        // 事实源本身也要有判别力：读到 0 条说明路径或编码变了，后面的断言会全部空过。
        assertTrue(names.size() >= 20, "从 .proto 只解析出 " + names.size() + " 个声明，解析方式已经失效");

        for (var name : names) {
            var hint = hints.reflection().getTypeHint(TypeReference.of("com.quizgate.proto." + name));
            assertNotNull(hint, name + " 没有注册反射元数据，native 下解析请求会报 Known fields are []");
            assertTrue(hint.getMemberCategories().contains(MemberCategory.DECLARED_FIELDS),
                    name + " 缺 DECLARED_FIELDS，native 下拿不到字段");
        }
    }

    @Test
    void 注册器认的包名与生成代码实际所在包一致() {
        // 包名写错时扫描会静默返回空集，所以拿一个真实生成类的位置反向核对。
        var hints = new RuntimeHints();
        new ProtoReflectionHints().registerHints(hints, ProtoReflectionHintsTest.class.getClassLoader());
        assertNotNull(hints.reflection().getTypeHint(TypeReference.of(LoginRequest.class.getName())),
                "ProtoReflectionHints 里的包名和 java_package 不一致");
    }

    /** .proto 在项目根，是唯一事实源：服务端和安卓共用它，测试不该再抄一份类名清单。 */
    private static java.util.List<String> declaredInProto() {
        var path = Path.of("proto", "quizgate.proto");
        try (var lines = Files.lines(path)) {
            return lines.map(TOP_LEVEL::matcher)
                    .filter(java.util.regex.Matcher::find)
                    .map(m -> m.group(2))
                    .toList();
        } catch (Exception e) {
            throw new AssertionError("读不到 " + path.toAbsolutePath() + "，测试的工作目录不是模块根", e);
        }
    }
}
