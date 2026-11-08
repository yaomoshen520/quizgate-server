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
import org.springframework.web.servlet.resource.NoResourceFoundException;

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
     * 静态资源没命中是"这里没这个文件"，不是"服务端崩了"。
     * <p>
     * 它必须写在兜底之前单独接住：NoResourceFoundException 继承 ServletException，
     * 落到下面那条 Exception 兜底就变成 500——浏览器只是少拿到一个 hash 过的 js 文件，
     * 运维却在日志里看到一片服务端故障。状态码用异常自带的，不在这里重新猜。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<String> missingResource(NoResourceFoundException e) {
        return ResponseEntity.status(e.getStatusCode()).contentType(TEXT).body("资源不存在");
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
