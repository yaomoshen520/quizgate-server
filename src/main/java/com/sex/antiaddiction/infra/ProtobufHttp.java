package com.sex.antiaddiction.infra;

import com.google.protobuf.MessageLite;
import com.google.protobuf.Parser;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * protobuf 走原始字节收发，不注册 HttpMessageConverter。
 * <p>
 * 通用的 protobuf converter 要靠反射拿 parser()，那正是 native-image 要额外喂配置的地方；
 * 而 {@code @RequestBody byte[]} 由 spring-web 自带的 ByteArrayHttpMessageConverter 支撑，
 * 每个接口显式 parseFrom 一次，全链路零反射。
 */
public final class ProtobufHttp {

    public static final String PROTOBUF_VALUE = "application/x-protobuf";
    public static final MediaType PROTOBUF = MediaType.parseMediaType(PROTOBUF_VALUE);

    private ProtobufHttp() {
    }

    public static ResponseEntity<byte[]> reply(MessageLite message) {
        return ResponseEntity.ok().contentType(PROTOBUF).body(message.toByteArray());
    }

    /**
     * 空体是合法请求：所有字段都取默认值的 protobuf 消息序列化出来就是 0 字节。
     * 所以控制器的 @RequestBody 一律 required = false，这里把 null 当空数组解析。
     */
    public static <T extends MessageLite> T parse(byte[] body, Parser<T> parser) {
        try {
            return parser.parseFrom(body == null ? new byte[0] : body);
        } catch (Exception e) {
            throw new BadProtoRequest("请求体不是合法的 protobuf: " + e.getMessage());
        }
    }

    /** 绑定码要给人念，去掉容易混淆的 0/O/1/I。 */
    public static String bindCode() {
        char[] alphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();
        var random = ThreadLocalRandom.current();
        var sb = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            sb.append(alphabet[random.nextInt(alphabet.length)]);
        }
        return sb.toString();
    }

    public static class BadProtoRequest extends RuntimeException {
        public BadProtoRequest(String message) {
            super(message);
        }
    }
}
