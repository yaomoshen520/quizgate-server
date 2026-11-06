package com.sex.antiaddiction.web;

import com.sex.antiaddiction.infra.ProtobufHttp.BadProtoRequest;
import com.sex.antiaddiction.service.ParentService.AuthFailed;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final MediaType TEXT = new MediaType("text", "plain", StandardCharsets.UTF_8);

    @ExceptionHandler(BadProtoRequest.class)
    public ResponseEntity<String> badRequest(BadProtoRequest e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).contentType(TEXT).body(e.getMessage());
    }

    @ExceptionHandler(AuthFailed.class)
    public ResponseEntity<String> unauthorized(AuthFailed e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).contentType(TEXT).body(e.getMessage());
    }

    /**
     * 兜底：任何没被上面接住的异常都在这里变成真实的 500，而不是继续往外抛。
     * <p>
     * 必须写这一条，否则 5xx 会被安全链伪装成 403：异常触发容器向 /error 转发，而 /error 不在
     * 任何放行链的路径名单里，最终撞上兜底的 denyAll。客户端看到的是"你没权限"，实际是服务器崩了——
     * 一次主键冲突就是这么被读成鉴权故障的。堆栈只进日志，响应体不给外部任何内部细节。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> unexpected(Exception e) {
        log.error("未预期的接口异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).contentType(TEXT)
                .body("服务端处理失败，请查看服务端日志");
    }
}
