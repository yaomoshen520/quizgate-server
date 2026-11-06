package com.sex.antiaddiction.web;

import com.quizgate.proto.AnswerRequest;
import com.quizgate.proto.EnrollRequest;
import com.quizgate.proto.GateEventReportRequest;
import com.quizgate.proto.HeartbeatRequest;
import com.quizgate.proto.NextQuestionRequest;
import com.quizgate.proto.UsageReportRequest;
import com.sex.antiaddiction.security.AppPrincipal;
import com.sex.antiaddiction.service.GateService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.sex.antiaddiction.infra.ProtobufHttp.PROTOBUF_VALUE;
import static com.sex.antiaddiction.infra.ProtobufHttp.parse;
import static com.sex.antiaddiction.infra.ProtobufHttp.reply;

/**
 * app A（孩子那台机器）唯一的对外面。
 * 每个入口都只认设备令牌背后的那台设备，请求体里没有任何"我要操作哪个孩子"的字段可供篡改。
 */
@RestController
@RequestMapping(path = "/api/device", consumes = PROTOBUF_VALUE, produces = PROTOBUF_VALUE)
public class DeviceApi {

    private final GateService gate;

    public DeviceApi(GateService gate) {
        this.gate = gate;
    }

    @PostMapping("/enroll")
    public ResponseEntity<byte[]> enroll(@RequestBody(required = false) byte[] body) {
        return reply(gate.enroll(parse(body, EnrollRequest.parser())));
    }

    @PostMapping("/next-question")
    public ResponseEntity<byte[]> nextQuestion(@AuthenticationPrincipal AppPrincipal principal,
            @RequestBody(required = false) byte[] body) {
        return reply(gate.nextQuestion(principal.subjectId(), parse(body, NextQuestionRequest.parser())));
    }

    @PostMapping("/answer")
    public ResponseEntity<byte[]> answer(@AuthenticationPrincipal AppPrincipal principal, @RequestBody(required = false) byte[] body) {
        return reply(gate.answer(principal.subjectId(), parse(body, AnswerRequest.parser())));
    }

    @PostMapping("/usage")
    public ResponseEntity<byte[]> usage(@AuthenticationPrincipal AppPrincipal principal, @RequestBody(required = false) byte[] body) {
        return reply(gate.report(principal.subjectId(), parse(body, UsageReportRequest.parser())));
    }

    @PostMapping("/heartbeat")
    public ResponseEntity<byte[]> heartbeat(@AuthenticationPrincipal AppPrincipal principal,
            @RequestBody(required = false) byte[] body) {
        return reply(gate.heartbeat(principal.subjectId(), parse(body, HeartbeatRequest.parser())));
    }

    /**
     * 拆闸门事件。和 usage 一样是"设备攒一批、送达一次"的形状，去重靠客户端生成的 event_id。
     * 这一条只报设备自己动过的手；失联不在这里，那件事设备报不了（app 没了就没有发送者）。
     */
    @PostMapping("/gate-event")
    public ResponseEntity<byte[]> gateEvent(@AuthenticationPrincipal AppPrincipal principal,
            @RequestBody(required = false) byte[] body) {
        return reply(gate.reportGateEvent(principal.subjectId(), parse(body, GateEventReportRequest.parser())));
    }
}
