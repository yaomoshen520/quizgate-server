package com.sex.antiaddiction;

import com.sex.antiaddiction.infra.ProtoReflectionHints;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ImportRuntimeHints;

/** 生成的 protobuf 类需要反射字段元数据，详见 {@link ProtoReflectionHints}。 */
@SpringBootApplication
@ImportRuntimeHints(ProtoReflectionHints.class)
public class AntiAddictionApplication {

    public static void main(String[] args) {
        SpringApplication.run(AntiAddictionApplication.class, args);
    }
}

