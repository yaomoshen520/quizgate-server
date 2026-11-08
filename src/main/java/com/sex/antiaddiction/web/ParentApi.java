package com.sex.antiaddiction.web;

import com.quizgate.proto.BindDeviceRequest;
import com.quizgate.proto.ChangePasswordRequest;
import com.quizgate.proto.CreateChildRequest;
import com.quizgate.proto.DeleteChildRequest;
import com.quizgate.proto.DeleteQuestionRequest;
import com.quizgate.proto.GetReportRequest;
import com.quizgate.proto.GetRuleRequest;
import com.quizgate.proto.ListAlertsRequest;
import com.quizgate.proto.ListBankRequest;
import com.quizgate.proto.ListChildrenRequest;
import com.quizgate.proto.ListChildDevicesRequest;
import com.quizgate.proto.LoginRequest;
import com.quizgate.proto.RegisterRequest;
import com.quizgate.proto.SaveRuleRequest;
import com.quizgate.proto.ScanBankRequest;
import com.quizgate.proto.UnbindDeviceRequest;
import com.quizgate.proto.UpdateChildRequest;
import com.quizgate.proto.UploadBankRequest;
import com.quizgate.proto.VerifyQuestionRequest;
import com.sex.antiaddiction.repo.AccountStore;
import com.sex.antiaddiction.security.AppPrincipal;
import com.sex.antiaddiction.service.ParentService;
import com.sex.antiaddiction.service.ParentService.Actor;
import com.sex.antiaddiction.service.ScanService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.sex.antiaddiction.infra.ProtobufHttp.PROTOBUF_VALUE;
import static com.sex.antiaddiction.infra.ProtobufHttp.parse;
import static com.sex.antiaddiction.infra.ProtobufHttp.reply;

/** app B（家长）的入口。除 login 之外一律要家长令牌。 */
@RestController
@RequestMapping(path = "/api/parent", consumes = PROTOBUF_VALUE, produces = PROTOBUF_VALUE)
public class ParentApi {

    private final ParentService parents;
    private final ScanService scanService;
    private final AccountStore accounts;

    public ParentApi(ParentService parents, ScanService scanService, AccountStore accounts) {
        this.parents = parents;
        this.scanService = scanService;
        this.accounts = accounts;
    }

    @PostMapping("/login")
    public ResponseEntity<byte[]> login(@RequestBody(required = false) byte[] body) {
        return reply(parents.login(parse(body, LoginRequest.parser())));
    }

    /** 匿名入口，但必须带注册码；角色由服务端写死成 PARENT。 */
    @PostMapping("/register")
    public ResponseEntity<byte[]> register(@RequestBody(required = false) byte[] body) {
        return reply(parents.register(parse(body, RegisterRequest.parser())));
    }

    @PostMapping("/change-password")
    public ResponseEntity<byte[]> changePassword(@AuthenticationPrincipal AppPrincipal principal,
            @RequestBody(required = false) byte[] body) {
        return reply(parents.changePassword(Actor.of(principal, accounts),
                parse(body, ChangePasswordRequest.parser())));
    }

    @PostMapping("/create-child")
    public ResponseEntity<byte[]> createChild(@AuthenticationPrincipal AppPrincipal principal,
            @RequestBody(required = false) byte[] body) {
        return reply(parents.createChild(Actor.of(principal, accounts),
                parse(body, CreateChildRequest.parser())));
    }

    @PostMapping("/update-child")
    public ResponseEntity<byte[]> updateChild(@AuthenticationPrincipal AppPrincipal principal,
            @RequestBody(required = false) byte[] body) {
        return reply(parents.updateChild(Actor.of(principal, accounts),
                parse(body, UpdateChildRequest.parser())));
    }

    /** 删档和改名一样按 child_id 归属性判：越界编号在这里和"不存在"是同一句话。 */
    @PostMapping("/delete-child")
    public ResponseEntity<byte[]> deleteChild(@AuthenticationPrincipal AppPrincipal principal,
            @RequestBody(required = false) byte[] body) {
        return reply(parents.deleteChild(Actor.of(principal, accounts),
                parse(body, DeleteChildRequest.parser())));
    }

    /** 浏览器刷新后重取列表：令牌活在 sessionStorage，孩子列表不能只存在于登录那一次应答里。 */
    @PostMapping("/list-children")
    public ResponseEntity<byte[]> listChildren(@AuthenticationPrincipal AppPrincipal principal,
            @RequestBody(required = false) byte[] body) {
        parse(body, ListChildrenRequest.parser());
        return reply(parents.listChildren(Actor.of(principal, accounts)));
    }

    @PostMapping("/bind-device")
    public ResponseEntity<byte[]> bind(@AuthenticationPrincipal AppPrincipal principal, @RequestBody(required = false) byte[] body) {
        return reply(parents.bind(Actor.of(principal, accounts), parse(body, BindDeviceRequest.parser())));
    }

    /** 删除档案的前置条件读的就是这一列，所以它必须和 list-children 里"最近心跳那台"分开。 */
    @PostMapping("/list-devices")
    public ResponseEntity<byte[]> listDevices(@AuthenticationPrincipal AppPrincipal principal,
            @RequestBody(required = false) byte[] body) {
        return reply(parents.devices(Actor.of(principal, accounts),
                parse(body, ListChildDevicesRequest.parser())));
    }

    /** 解绑是认领的反向动作，同样只按"这台设备现在挂在谁名下"判归属，请求里不带 child_id。 */
    @PostMapping("/unbind-device")
    public ResponseEntity<byte[]> unbindDevice(@AuthenticationPrincipal AppPrincipal principal,
            @RequestBody(required = false) byte[] body) {
        return reply(parents.unbind(Actor.of(principal, accounts),
                parse(body, UnbindDeviceRequest.parser())));
    }

    /**
     * 顶部红条 + 告警列表。单独一个接口而不是塞进 list-children：
     * 这一屏要同时给"实证的事件"和"推断的失联"两类东西，形状和 ChildSummary 不是一回事，
     * 硬塞进去会让概览页每次刷新都拖着整串历史告警跑。
     */
    @PostMapping("/list-alerts")
    public ResponseEntity<byte[]> listAlerts(@AuthenticationPrincipal AppPrincipal principal,
            @RequestBody(required = false) byte[] body) {
        parse(body, ListAlertsRequest.parser());
        return reply(parents.alerts(Actor.of(principal, accounts)));
    }

    @PostMapping("/upload-bank")
    public ResponseEntity<byte[]> upload(@AuthenticationPrincipal AppPrincipal principal, @RequestBody(required = false) byte[] body) {
        return reply(parents.upload(Actor.of(principal, accounts), parse(body, UploadBankRequest.parser())));
    }

    @PostMapping("/save-rule")
    public ResponseEntity<byte[]> saveRule(@AuthenticationPrincipal AppPrincipal principal, @RequestBody(required = false) byte[] body) {
        return reply(parents.saveRule(Actor.of(principal, accounts), parse(body, SaveRuleRequest.parser())));
    }

    /** 规则页打开时先读回真值：显示家长没设过的默认值可以，显示一个和库里不一致的数不行。 */
    @PostMapping("/get-rule")
    public ResponseEntity<byte[]> getRule(@AuthenticationPrincipal AppPrincipal principal, @RequestBody(required = false) byte[] body) {
        return reply(parents.getRule(Actor.of(principal, accounts), parse(body, GetRuleRequest.parser())));
    }

    /** 上传完必须能看见，否则家长永远不知道"孩子屏幕上那道题到底是我传的那道吗"。 */
    @PostMapping("/list-bank")
    public ResponseEntity<byte[]> listBank(@AuthenticationPrincipal AppPrincipal principal, @RequestBody(required = false) byte[] body) {
        return reply(parents.listBank(Actor.of(principal, accounts), parse(body, ListBankRequest.parser())));
    }

    @PostMapping("/get-report")
    public ResponseEntity<byte[]> report(@AuthenticationPrincipal AppPrincipal principal, @RequestBody(required = false) byte[] body) {
        return reply(parents.report(Actor.of(principal, accounts), parse(body, GetReportRequest.parser())));
    }

    /**
     * 拍一张卷子：图交给识别引擎，识别出来的题落库成"未核对"，算术题能自证的直接可用。
     * 这是全项目唯一收 bytes 的接口，大小上限在服务层判。
     */
    @PostMapping("/scan-bank")
    public ResponseEntity<byte[]> scan(@AuthenticationPrincipal AppPrincipal principal, @RequestBody(required = false) byte[] body) {
        return reply(scanService.scan(Actor.of(principal, accounts), parse(body, ScanBankRequest.parser())));
    }

    /** 家长核对通过/驳回。孩子抽不抽得到这道题，就决定在这一下。 */
    @PostMapping("/verify-question")
    public ResponseEntity<byte[]> verify(@AuthenticationPrincipal AppPrincipal principal, @RequestBody(required = false) byte[] body) {
        return reply(scanService.verify(Actor.of(principal, accounts), parse(body, VerifyQuestionRequest.parser())));
    }

    @PostMapping("/delete-question")
    public ResponseEntity<byte[]> deleteQuestion(@AuthenticationPrincipal AppPrincipal principal, @RequestBody(required = false) byte[] body) {
        return reply(scanService.delete(Actor.of(principal, accounts), parse(body, DeleteQuestionRequest.parser())));
    }
}
