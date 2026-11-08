package com.sex.antiaddiction;

import com.quizgate.proto.AnswerReply;
import com.quizgate.proto.AnswerRequest;
import com.quizgate.proto.BindDeviceRequest;
import com.quizgate.proto.BindDeviceReply;
import com.quizgate.proto.ChangePasswordReply;
import com.quizgate.proto.ChangePasswordRequest;
import com.quizgate.proto.ChildSummary;
import com.quizgate.proto.CreateChildReply;
import com.quizgate.proto.CreateChildRequest;
import com.quizgate.proto.CreateInviteReply;
import com.quizgate.proto.CreateInviteRequest;
import com.quizgate.proto.EnrollReply;
import com.quizgate.proto.EnrollRequest;
import com.quizgate.proto.GateEvent;
import com.quizgate.proto.GateEventKind;
import com.quizgate.proto.GateEventReportReply;
import com.quizgate.proto.GateEventReportRequest;
import com.quizgate.proto.GetReportRequest;
import com.quizgate.proto.GetReportReply;
import com.quizgate.proto.GetRuleReply;
import com.quizgate.proto.GetRuleRequest;
import com.quizgate.proto.HeartbeatReply;
import com.quizgate.proto.HeartbeatRequest;
import com.quizgate.proto.ListAccountsRequest;
import com.quizgate.proto.ListAlertsReply;
import com.quizgate.proto.ListAlertsRequest;
import com.quizgate.proto.ListBankReply;
import com.quizgate.proto.ListBankRequest;
import com.quizgate.proto.ListChildrenReply;
import com.quizgate.proto.ListChildrenRequest;
import com.quizgate.proto.ListInvitesReply;
import com.quizgate.proto.ListInvitesRequest;
import com.quizgate.proto.LoginRequest;
import com.quizgate.proto.LoginReply;
import com.quizgate.proto.NextQuestionReply;
import com.quizgate.proto.NextQuestionRequest;
import com.quizgate.proto.QuestionDraft;
import com.quizgate.proto.RegisterReply;
import com.quizgate.proto.RegisterRequest;
import com.quizgate.proto.ResetPasswordReply;
import com.quizgate.proto.ResetPasswordRequest;
import com.quizgate.proto.SaveRuleRequest;
import com.quizgate.proto.RuleSnapshot;
import com.quizgate.proto.Subject;
import com.quizgate.proto.UsageEvent;
import com.quizgate.proto.UsageReportReply;
import com.quizgate.proto.UsageReportRequest;
import com.quizgate.proto.UploadBankRequest;
import com.quizgate.proto.UploadBankReply;
import com.sex.antiaddiction.domain.Rows;
import com.sex.antiaddiction.repo.AccountStore;
import com.sex.antiaddiction.repo.QuestionStore;
import com.sex.antiaddiction.security.BootstrapRunner;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:gateflow;DB_CLOSE_DELAY=-1",
        "quizgate.bootstrap.parent-username=dad",
        "quizgate.bootstrap.parent-password=correct horse battery",
        "quizgate.bootstrap.admin-username=",
        "quizgate.default-threshold-minutes=30"
})
@AutoConfigureMockMvc
class GateFlowTest {

    private static final MediaType PROTOBUF = MediaType.parseMediaType("application/x-protobuf");
    private static final String TOKEN_HEADER = "X-Quizgate-Token";
    /** 测试孩子与内置题库样本使用的年级。 */
    private static final int DEFAULT_GRADE = 2;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    QuestionStore questionStore;

    @Autowired
    AccountStore accounts;

    @Autowired
    PasswordEncoder passwords;

    @Autowired
    BootstrapRunner bootstrap;

    private static int installSeq = 0;

    @Test
    void 取到的题不含正确答案_答错只给解析并换一道同知识点变式题() throws Exception {
        String token = enrollBound();
        // 显式挑一个确实有变式题的知识点：否则随机抽到只剩一道题的知识点时，
        // 服务端走的是"原题重发"那条分支（下面另有测试专门钉它），这条测试就会随机红。
        String knowledgePoint = poolByKnowledgePoint().entrySet().stream()
                .filter(e -> e.getValue().size() >= 2)
                .map(Map.Entry::getKey)
                .sorted()
                .findFirst()
                .orElseThrow(() -> new AssertionError("内置题库里没有带变式题的知识点，样本失效"));
        var question = next(token, DEFAULT_GRADE, knowledgePoint, "").getQuestion();
        assertEquals(knowledgePoint, question.getKnowledgePoint(), "指定了知识点就该拿到这个知识点的题");
        assertTrue(question.getOptionsCount() >= 2, "题目至少要有两个选项");
        int correct = correctIndexOf(question.getQuestionId());
        assertFalse(question.toString().contains("correct"), "下发给客户端的消息里不该有正确项痕迹");

        var wrong = answer(token, question.getQuestionId(), (correct + 1) % question.getOptionsCount());
        assertFalse(wrong.getCorrect());
        assertFalse(wrong.getExplanation().isBlank(), "答错要给解析");

        var variant = next(token, DEFAULT_GRADE, question.getKnowledgePoint(), question.getQuestionId()).getQuestion();
        assertEquals(question.getKnowledgePoint(), variant.getKnowledgePoint(), "变式题必须同知识点");
        assertNotEquals(question.getQuestionId(), variant.getQuestionId(), "有变式题可用时不能把原题再给一遍");

        var right = answer(token, variant.getQuestionId(), correctIndexOf(variant.getQuestionId()));
        assertTrue(right.getCorrect());
        assertTrue(right.getExplanation().isBlank(), "答对不需要解析");
    }

    /**
     * 闸门的产品语义是"这个知识点答对为止"。同知识点题用尽时如果换知识点，
     * 孩子就能用一道别的题把没掌握的那格抵掉——所以这里钉住：宁可原题重发。
     */
    @Test
    void 同知识点只剩一道题时原题重发_不许换成别的知识点() throws Exception {
        String token = enrollBound();
        var singleEntry = poolByKnowledgePoint().entrySet().stream()
                .filter(e -> e.getValue().size() == 1)
                .sorted(Map.Entry.comparingByKey())
                .findFirst()
                .orElseThrow(() -> new AssertionError("内置题库里已经没有单题知识点了，样本失效"));
        var only = singleEntry.getValue().get(0);

        var served = next(token, DEFAULT_GRADE, singleEntry.getKey(), String.valueOf(only.id())).getQuestion();
        assertEquals(singleEntry.getKey(), served.getKnowledgePoint(), "不能为了换题而换知识点");
        assertEquals(String.valueOf(only.id()), served.getQuestionId(), "只剩一道题就该重发这一道");
    }

    @Test
    void 没有下发过的题一律不判分_四个下标全试也试不出答案() throws Exception {
        String token = enrollBound();
        // 直接拿内置题号 1 去撞接口：它从没出现在这台设备的账上。
        for (int index = 0; index < 4; index++) {
            var reply = answer(token, "1", index);
            assertFalse(reply.getCorrect(), "下标 " + index + " 不该被判成正确");
            assertTrue(reply.getExplanation().contains("过期"), "只回过期提示，不回解析");
        }
    }

    @Test
    void 设备令牌与家长令牌互不通用() throws Exception {
        String deviceToken = enroll("install-" + (++installSeq)).getDeviceToken();
        String parentToken = login().getParentToken();

        mvc.perform(post("/api/device/next-question").header(TOKEN_HEADER, parentToken)
                        .contentType(PROTOBUF).content(NextQuestionRequest.newBuilder().build().toByteArray()))
                .andExpect(status().is4xxClientError());

        mvc.perform(post("/api/parent/get-report").header(TOKEN_HEADER, deviceToken)
                        .contentType(PROTOBUF)
                        .content(GetReportRequest.newBuilder().setChildId("1").build().toByteArray()))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void 用量上报按客户端事件号去重() throws Exception {
        String token = enrollBound();
        var event = UsageEvent.newBuilder()
                .setEventId("evt-" + installSeq)
                .setOccurredAtMs(System.currentTimeMillis())
                .setAppPackage("com.video.app")
                .setPlayedMs(1_800_000)
                .setCorrectCount(1)
                .setWrongCount(0)
                .build();
        var request = UsageReportRequest.newBuilder().addEvents(event).build();

        var first = UsageReportReply.parseFrom(report(token, request.toByteArray()));
        assertEquals(1, first.getAccepted());
        assertEquals(0, first.getDuplicated());

        var again = UsageReportReply.parseFrom(report(token, request.toByteArray()));
        assertEquals(0, again.getAccepted(), "同一件事重报不能计两次时长");
        assertEquals(1, again.getDuplicated());
    }

    @Test
    void 家长建的私有题不会进别的孩子候选池() throws Exception {
        var login = login();
        String parentToken = login.getParentToken();
        String childId = login.getChildren(0).getChildId();
        upload(parentToken, childId, "只有我家孩子该见到的题");

        // 走生产代码的候选池查询，测试里另抄一份 SQL 的话，改坏产品代码是测不出来的。
        assertTrue(stemsOf(Long.parseLong(childId)).contains("只有我家孩子该见到的题"),
                "自己孩子的私有题应当进候选池");
        assertFalse(stemsOf(999_999L).contains("只有我家孩子该见到的题"),
                "私有题库串到别人孩子屏幕上就是越权");
    }

    @Test
    void 绑定码认领后时长才记到这个孩子名下() throws Exception {
        var login = login();
        String parentToken = login.getParentToken();
        String childId = login.getChildren(0).getChildId();
        String install = freshInstall();
        var enrolled = enroll(install);
        String deviceToken = enrolled.getDeviceToken();

        var bound = BindDeviceReply.parseFrom(bind(parentToken, childId, enrolled.getBindCode()));
        assertTrue(bound.getBound(), bound.getMessage());

        var event = UsageEvent.newBuilder()
                .setEventId("evt-bind-" + install)
                .setOccurredAtMs(System.currentTimeMillis())
                .setAppPackage("com.video.app")
                .setPlayedMs(600_000)
                .build();
        var ack = UsageReportReply.parseFrom(usage(deviceToken, event));
        assertEquals(1, ack.getAccepted());

        var report = GetReportReply.parseFrom(getReport(parentToken, childId));
        assertEquals(1, report.getStatsCount(), "认领之后家长才看得到这一天");
        assertEquals(600_000, report.getStats(0).getPlayedMs());

        // 认领是排他的：同一个码再给别的 child 用不该成功。
        var again = BindDeviceReply.parseFrom(bind(parentToken, childId, enrolled.getBindCode()));
        assertFalse(again.getBound(), "绑定码用完就要作废");
    }

    @Test
    void 家长改规则后闸门在下次心跳拿到新快照() throws Exception {
        var login = login();
        String parentToken = login.getParentToken();
        String childId = login.getChildren(0).getChildId();
        var enrolled = enroll(freshInstall());
        String token = enrolled.getDeviceToken();
        // 没认领的设备走服务端兜底规则（版本 0），心跳自然不会回快照；这条测的是孩子名下的规则。
        assertTrue(BindDeviceReply.parseFrom(bind(parentToken, childId, enrolled.getBindCode())).getBound());

        var before = HeartbeatReply.parseFrom(heartbeat(token, 0L));
        assertTrue(before.getRuleChanged(), "客户端版本 0 与库里的规则版本必然不同");
        assertEquals(30, before.getRule().getThresholdMinutes());

        var saved = RuleSnapshot.parseFrom(saveRule(parentToken, childId, 45));
        var after = HeartbeatReply.parseFrom(heartbeat(token, saved.getVersion()));
        assertFalse(after.getRuleChanged(), "版本没变就不该再回传快照");
        assertFalse(after.hasRule());

        var stale = HeartbeatReply.parseFrom(heartbeat(token, saved.getVersion() - 1));
        assertTrue(stale.getRuleChanged());
        assertEquals(45, stale.getRule().getThresholdMinutes());
    }

    @Test
    void 密码不对换不到令牌() throws Exception {
        var body = LoginRequest.newBuilder().setUsername("dad").setPassword("猜的").build().toByteArray();
        mvc.perform(post("/api/parent/login").contentType(PROTOBUF).content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 家长令牌改不了别人家孩子的规则() throws Exception {
        var login = login();
        long otherParent = accounts.createAccount("mom", passwords.encode("别的家的口令"), "妈妈", "PARENT");
        long otherChild = accounts.createChild(otherParent, "老二", 2);

        var body = SaveRuleRequest.newBuilder()
                .setChildId(Long.toString(otherChild))
                .setRule(RuleSnapshot.newBuilder().setThresholdMinutes(1).setDailyLimitMinutes(1)
                        .setGraceMinutes(0).setEnabled(true).build())
                .build().toByteArray();
        mvc.perform(post("/api/parent/save-rule").header(TOKEN_HEADER, login.getParentToken())
                        .contentType(PROTOBUF).content(body))
                .andExpect(status().isBadRequest());

        assertTrue(accounts.rule(otherChild).isEmpty(), "越权写入必须一个字都没落到别人家孩子身上");
    }

    /**
     * 重启后第一次上传不能撞主键。内置题钉死了 1~12 号，自增指针必须落在"当前最大的那一行"之后。
     * <p>
     * 这里重跑一遍启动种子，模拟的正是第二次启动：data.sql 每次都执行，指针也每次都被重设。
     * 曾经它写死 restart with 100——上一次启动之后上传的题恰好占着 100 号，重启后一插就炸。
     * 谁再把指针改成写死的数，这条测试就会在第二次上传时红掉。
     */
    @Test
    void 重启后上传不会撞已存在的题号() throws Exception {
        var login = login();
        String parentToken = login.getParentToken();
        String childId = login.getChildren(0).getChildId();

        upload(parentToken, childId, "第一轮上传");
        bootstrap.run(null); // ApplicationArguments 在种子逻辑里根本没被读，传 null 就是"再启动一次"。
        upload(parentToken, childId, "重启之后的第二轮上传");
    }

    /**
     * 服务端崩了必须回 5xx，不能被安全链翻译成鉴权错误。
     * <p>
     * 异常默认会触发容器向 /error 的转发，而 /error 不在任何放行链的路径名单里，兜底链的 denyAll
     * 于是把 500 伪装成 403。客户端照着 403 去重登录，真正的问题却还在库里——上面那个主键冲突
     * 当初就是这样被读成"令牌失效"的。这条测试用一个必然落库失败的值把那条通路钉住。
     * <p>
     * 分层说清楚：403 那一步是在真实 exe 上实测到的，MockMvc 不做容器的 ERROR 转发，所以摘掉兜底
     * 处理器之后这里表现为异常直接抛出。两种失败都证明这个处理器是承重的，不是装饰。
     */
    @Test
    void 服务端异常回500而不是被安全链伪装成403() throws Exception {
        var login = login();
        // stem 是 varchar(512)：600 字的题干能过字段校验，只会在真正落库时被数据库拒掉。
        var draft = QuestionDraft.newBuilder()
                .setSubject(Subject.MATH)
                .setKnowledgePoint("超长题干")
                .setStem("题".repeat(600))
                .addOptions("甲").addOptions("乙")
                .setCorrectIndex(0)
                .setExplanation("故意越界")
                .build();
        var result = mvc.perform(post("/api/parent/upload-bank")
                        .header(TOKEN_HEADER, login.getParentToken())
                        .contentType(PROTOBUF)
                        .content(UploadBankRequest.newBuilder()
                                .setChildId(login.getChildren(0).getChildId()).addDrafts(draft).build().toByteArray()))
                .andReturn();

        int code = result.getResponse().getStatus();
        assertEquals(500, code, "服务端异常必须原样回 500，实得 " + code);
        assertTrue(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)
                .contains("服务端"), "响应体要说人话，且不能泄露堆栈");
    }

    /**
     * 角色分档的真门：admin 档位只认管理员令牌。
     * <p>
     * 前端把 /admin 路由藏起来不算隔离——家长照样能直接发 protobuf 请求打进来，所以这一档必须
     * 在安全链上拒。断言只认"不是 200"，具体 401 还是 403 由容器翻译，不是给客户端的契约。
     */
    @Test
    void 管理员档位只认管理员令牌() throws Exception {
        String parentToken = login().getParentToken();
        String deviceToken = enroll(freshInstall()).getDeviceToken();
        String adminToken = adminLogin().getParentToken();
        var body = ListAccountsRequest.newBuilder().build().toByteArray();

        assertEquals(200, statusOf("/api/admin/list-accounts", adminToken, body), "管理员令牌该进得来");
        for (var token : List.of(parentToken, deviceToken)) {
            int code = statusOf("/api/admin/list-accounts", token, body);
            assertTrue(code == 401 || code == 403, "非管理员令牌被放进 admin 档位，实得 " + code);
        }
    }

    /** 登录回复里的角色是给 UI 分路由用的，服务端同时得真的按角色发不同令牌。 */
    @Test
    void 登录回复带角色_管理员拿的是admin令牌() throws Exception {
        assertEquals("PARENT", login().getRole(), "家长登录要拿到 PARENT，前端据此只挂家长路由");
        var admin = adminLogin();
        assertEquals("ADMIN", admin.getRole());
        assertTrue(admin.getParentToken().startsWith("admin-"),
                "管理员的令牌 kind 必须区别于家长，否则安全链没法单独锁一档：" + admin.getParentToken());
    }

    /**
     * 数据隔离的两面：管理员看全部，家长只看自己那一户。
     * 反过来管理员还要能用家长那一档的接口替别人家孩子改规则——这是"管理员可以配置一切"的正向能力，
     * 不是漏洞，所以两边都得钉住，改坏了任何一边都会红。
     */
    @Test
    void 管理员看全部孩子_家长只看自己_管理员能替别人家配规则() throws Exception {
        long otherParent = accounts.createAccount("grandma", passwords.encode("另一家的口令"), "奶奶", "PARENT");
        long otherChild = accounts.createChild(otherParent, "表妹", 3);

        var asParent = login();
        assertEquals(1, asParent.getChildrenCount(), "家长登录只该看到自己那一户");
        assertEquals("1", asParent.getChildren(0).getChildId());

        var asAdmin = adminLogin();
        assertTrue(asAdmin.getChildrenCount() >= 2,
                "管理员登录要看到全部孩子，实得 " + asAdmin.getChildrenCount());

        var result = mvc.perform(post("/api/parent/save-rule")
                .header(TOKEN_HEADER, asAdmin.getParentToken())
                .contentType(PROTOBUF)
                .content(SaveRuleRequest.newBuilder()
                        .setChildId(Long.toString(otherChild))
                        .setRule(RuleSnapshot.newBuilder().setThresholdMinutes(20).setDailyLimitMinutes(60)
                                .setGraceMinutes(1).setEnabled(true).build())
                        .build().toByteArray())).andReturn();
        assertEquals(200, result.getResponse().getStatus(), "管理员替别的家庭配规则是设计内的能力");
        assertEquals(20, accounts.rule(otherChild).orElseThrow().thresholdMinutes());
    }

    /**
     * 浏览器按 F5 之后令牌还活在 sessionStorage，登录那一次应答里的孩子却跟着页面一起没了，
     * 所以列表必须能重新取一次。这一条同时钉住两件事：
     * 重取的查询规则和登录那一次没分叉（家长不会因此看见别人家的孩子），
     * 以及没登录过的匿名请求进不了这一档。
     */
    @Test
    void 刷新后重取孩子列表_家长只回自己_管理员回全部() throws Exception {
        long grandpa = accounts.createAccount("grandpa-refresh", passwords.encode("另一家的口令"), "爷爷", "PARENT");
        long theirs = accounts.createChild(grandpa, "堂弟", 3);

        var mine = ListChildrenReply.parseFrom(listChildrenBytes(login().getParentToken()));
        assertEquals(1, mine.getChildrenCount(), "家长重取列表只该看到自己那一户");
        assertEquals("1", mine.getChildren(0).getChildId());

        var all = ListChildrenReply.parseFrom(listChildrenBytes(adminLogin().getParentToken()));
        assertTrue(all.getChildrenCount() >= 2, "管理员重取列表要看到全部孩子，实得 " + all.getChildrenCount());
        assertTrue(all.getChildrenList().stream().anyMatch(c -> c.getChildId().equals(Long.toString(theirs))),
                "管理员那一份里必须有别人家的孩子——否则两处查询其实是各写各的");

        int anonymous = statusOf("/api/parent/list-children", null,
                ListChildrenRequest.newBuilder().build().toByteArray());
        assertTrue(anonymous == 401 || anonymous == 403, "匿名不该取到任何人的孩子列表，实得 " + anonymous);
    }

    private byte[] listChildrenBytes(String token) throws Exception {
        return exchangePost("/api/parent/list-children", token,
                ListChildrenRequest.newBuilder().build().toByteArray());
    }

    /**
     * 家长在网页上看到的规则，必须就是闸门拿去拦孩子的那一份。
     * <p>
     * 断言的形状是"两边对齐"而不是"等于 30"：默认值那一路只有和同一次心跳下发的快照逐字段比过，
     * 将来有人给 get-rule 另写一套默认值时它才会红。测试属性里 default-threshold 也是 30，
     * 单看数字比不出这两条路是不是同一个来源。
     */
    @Test
    void 家长读回的规则就是闸门拿到的那一份() throws Exception {
        var login = login();
        String parentToken = login.getParentToken();
        String childId = login.getChildren(0).getChildId();
        var enrolled = enroll(freshInstall());
        assertTrue(BindDeviceReply.parseFrom(bind(parentToken, childId, enrolled.getBindCode())).getBound());

        var gateSide = HeartbeatReply.parseFrom(heartbeat(enrolled.getDeviceToken(), 0L)).getRule();
        var parentSide = GetRuleReply.parseFrom(getRule(parentToken, childId)).getRule();
        assertEquals(gateSide.getThresholdMinutes(), parentSide.getThresholdMinutes(),
                "家长端读的默认值和闸门端不是同一份");
        assertEquals(gateSide.getVersion(), parentSide.getVersion(), "版本号必须同源，否则闸门每次心跳都白拉一次全量");

        var saved = RuleSnapshot.parseFrom(saveRule(parentToken, childId, 45));
        var reread = GetRuleReply.parseFrom(getRule(parentToken, childId)).getRule();
        assertEquals(45, reread.getThresholdMinutes(), "写进去 45、读回来不是 45，规则页就是在骗家长");
        assertEquals(saved.getVersion(), reread.getVersion(), "读回的版本必须就是刚写入的那一版");

        var refreshed = HeartbeatReply.parseFrom(
                heartbeat(enrolled.getDeviceToken(), gateSide.getVersion())).getRule();
        assertEquals(45, refreshed.getThresholdMinutes(), "家长改完，闸门下一次心跳就该按新值拦");

        // 一个连默认规则都没落库的孩子（裸 createChild，不走带默认值的那条建法）：
        // 家长端读到的仍必须是闸门真正在用的那份服务端默认，而不是没行就回 0——
        // 那等于在屏幕上告诉家长"这台机器不设限"，而闸门其实一直在拦。
        long bareParent = accounts.createAccount("dad-bare", passwords.encode("没配规则的口令"), "没配规则的爸爸", "PARENT");
        long bareChild = accounts.createChild(bareParent, "裸孩子", 2);
        var bare = GetRuleReply.parseFrom(getRule(loginAs("dad-bare", "没配规则的口令").getParentToken(),
                Long.toString(bareChild))).getRule();
        assertEquals(30, bare.getThresholdMinutes(), "没配过规则时，家长端要读到服务端真正生效的那份默认值");
        assertTrue(bare.getEnabled(), "默认必须是开着的：没配过不等于不设限");

        long otherParent = accounts.createAccount("mom-getrule", passwords.encode("另一家的口令"), "妈妈", "PARENT");
        long otherChild = accounts.createChild(otherParent, "老二读规则", 2);
        int crossed = statusOf("/api/parent/get-rule", parentToken,
                GetRuleRequest.newBuilder().setChildId(Long.toString(otherChild)).build().toByteArray());
        assertTrue(crossed == 400 || crossed == 401 || crossed == 403,
                "读别人家孩子的规则必须被拒，实得 " + crossed);
        int anonymous = statusOf("/api/parent/get-rule", null,
                GetRuleRequest.newBuilder().setChildId(childId).build().toByteArray());
        assertTrue(anonymous == 401 || anonymous == 403, "匿名不该读到任何人的规则");
    }

    /**
     * 家长传完题必须能看见，而且看见的就是库里那一份。
     * 正确下标和解析都要对上：这两样是家长自己选的，回显错了等于给孩子屏幕上下了一道没确认过的题。
     */
    @Test
    void 家长上传的题能回显_越界读不到别人家的题库() throws Exception {
        var login = login();
        String parentToken = login.getParentToken();
        String childId = login.getChildren(0).getChildId();

        uploadWithAnswer(parentToken, childId, "回显题一", 2);
        uploadWithAnswer(parentToken, childId, "回显题二", 0);
        // 先给另一户也传一道，再回显自家这一户：别人家那道必须一条都不出现。
        // 服务层的归属检查会先挡住越界请求，所以光有"越界被拒"这一条断言，SQL 里漏掉孩子条件照样测不出来。
        long otherParent = accounts.createAccount("mom-listbank", passwords.encode("另一家的口令"), "妈妈", "PARENT");
        long otherChild = accounts.createChild(otherParent, "老二读题库", 2);
        uploadWithAnswer(loginAs("mom-listbank", "另一家的口令").getParentToken(),
                Long.toString(otherChild), "别人家的题", 1);

        var bank = ListBankReply.parseFrom(exchangePost("/api/parent/list-bank", parentToken,
                ListBankRequest.newBuilder().setChildId(childId).build().toByteArray()));
        var first = bank.getQuestionsList().stream().filter(q -> q.getStem().equals("回显题一")).findFirst();
        assertTrue(first.isPresent(), "刚上传的题必须出现在回显里，实得 " + bank.getQuestionsCount() + " 条");
        assertEquals(2, first.get().getCorrectIndex(), "回显的正确下标必须是家长选的那一个");
        assertFalse(first.get().getExplanation().isBlank(), "解析也要回显，家长改题时读的就是它");
        assertEquals(3, first.get().getOptionsCount());
        assertTrue(bank.getQuestionsList().stream().allMatch(q -> !q.getQuestionId().isBlank()),
                "没有题号就改不了也删不了，回显必须是一份可操作的清单");
        assertEquals("丙", first.get().getOptions(2),
                "选项必须按家长录入的顺序回显——正确项是个下标，顺序一乱就指到别的选项上");
        assertTrue(bank.getQuestionsList().stream().noneMatch(q -> q.getStem().equals("别人家的题")),
                "回显里出现了别人家孩子的题，SQL 就没有限定在这个孩子名下");

        int crossed = statusOf("/api/parent/list-bank", parentToken,
                ListBankRequest.newBuilder().setChildId(Long.toString(otherChild)).build().toByteArray());
        assertTrue(crossed == 400 || crossed == 401 || crossed == 403,
                "读别人家孩子的题库必须被拒，实得 " + crossed);
        int anonymous = statusOf("/api/parent/list-bank", null,
                ListBankRequest.newBuilder().setChildId(childId).build().toByteArray());
        assertTrue(anonymous == 401 || anonymous == 403, "匿名不该读到任何人的题库");
    }

    /**
     * 家长要能看到孩子手上是哪台机器。一个孩子的名下文可能先后绑过几台，
     * 列表里那一行必须来自"最近真的在跑的那台"，不是自增主序里的第一台。
     */
    @Test
    void 孩子列表带机型_取的是最近心跳那台设备() throws Exception {
        // 专属的一户：这个孩子名下只有本用例登记的这两台设备。
        // 借共用的小一测排序会被别的用例留下的心跳污染——那台 Tianyi-1 早就打过心跳，新绑的这台没有。
        long dad = accounts.createAccount("dad-device", passwords.encode("看机型的口令"), "看机型的爸爸", "PARENT");
        long boundChild = accounts.createChild(dad, "机型试验田", 2);
        String childId = Long.toString(boundChild);
        String parentToken = loginAs("dad-device", "看机型的口令").getParentToken();

        var earlier = enrollWith(freshInstall(), "Redmi-Note8", 24);
        assertTrue(BindDeviceReply.parseFrom(bind(parentToken, childId, earlier.getBindCode())).getBound());
        var oneDevice = childRow(listChildrenBytes(parentToken), childId);
        assertEquals("Redmi-Note8", oneDevice.getDeviceModel(), "机型要从 enroll 一路透到家长列表");
        assertEquals(24, oneDevice.getAndroidApiLevel(),
                "API 等级过去是收进请求就丢在门口——家长靠它判断这台机器能不能装 Device Owner");
        assertTrue(oneDevice.getDeviceBound());
        assertEquals(0L, oneDevice.getLastHeartbeatMs(), "刚绑上、一次心跳都没打的设备就该读成 0，界面据此显示还没跑起来");

        var newer = enrollWith(freshInstall(), "Pixel-4", 33);
        assertTrue(BindDeviceReply.parseFrom(bind(parentToken, childId, newer.getBindCode())).getBound());
        heartbeat(newer.getDeviceToken(), 0L);
        var picked = childRow(listChildrenBytes(parentToken), childId);
        assertEquals("Pixel-4", picked.getDeviceModel(), "两台设备里该报最近心跳的那台");
        assertTrue(picked.getLastHeartbeatMs() > 0, "打过心跳的设备必须带出心跳时间");

        // 管理员这一档读的是同一份装配代码，机型不能只在家长那一份里才带出来。
        var adminView = ListChildrenReply.parseFrom(listChildrenBytes(adminLogin().getParentToken()));
        var inAdminView = adminView.getChildrenList().stream()
                .filter(c -> c.getChildId().equals(childId)).findFirst()
                .orElseThrow(() -> new AssertionError("管理员视图里没有这个孩子：" + childId));
        assertEquals("Pixel-4", inAdminView.getDeviceModel(), "两个视图必须报同一台设备");
    }

    private byte[] getRule(String parentToken, String childId) throws Exception {
        return exchangePost("/api/parent/get-rule", parentToken,
                GetRuleRequest.newBuilder().setChildId(childId).build().toByteArray());
    }

    private ChildSummary childRow(byte[] listChildrenReply, String childId) throws Exception {
        return ListChildrenReply.parseFrom(listChildrenReply).getChildrenList().stream()
                .filter(c -> c.getChildId().equals(childId)).findFirst()
                .orElseThrow(() -> new AssertionError("列表里没有这个孩子：" + childId));
    }

    /** 带正确下标的上传，专门给回显用；其余走既有的 upload()。 */
    private void uploadWithAnswer(String parentToken, String childId, String stem, int correctIndex)
            throws Exception {
        var draft = QuestionDraft.newBuilder()
                .setSubject(Subject.MATH)
                .setKnowledgePoint("回显知识点")
                .setStem(stem)
                .addOptions("甲").addOptions("乙").addOptions("丙")
                .setCorrectIndex(correctIndex)
                .setExplanation("家长自己写的解析")
                .build();
        var reply = UploadBankReply.parseFrom(exchangePost("/api/parent/upload-bank", parentToken,
                UploadBankRequest.newBuilder().setChildId(childId).addDrafts(draft).build().toByteArray()));
        assertEquals(1, reply.getAccepted(), String.join("；", reply.getErrorsList()));
    }

    private EnrollReply enrollWith(String installId, String model, int apiLevel) throws Exception {
        byte[] body = EnrollRequest.newBuilder()
                .setInstallId(installId)
                .setDeviceModel(model)
                .setAndroidApiLevel(apiLevel)
                .build().toByteArray();
        var reply = EnrollReply.parseFrom(exchangePost("/api/device/enroll", null, body));
        assertFalse(reply.getDeviceToken().isBlank(), "注册必须换回设备令牌");
        return reply;
    }

    /**
     * 注册码是自助注册唯一那道闸，而且一码一号。
     * 失败的那几种情况必须一个账号都没落下来——"码没对上但号建好了"会留下一张没人能登录的僵尸账号。
     */
    @Test
    void 注册码一次性_没码就没有账号() throws Exception {
        String adminToken = adminLogin().getParentToken();
        String code = newInvite(adminToken);

        String token = register(code, "newdad", "新的家长的口令");
        assertTrue(token.startsWith("parent-"), "注册成功直接发家长令牌：" + token);

        // 同一个码第二次使用：拒绝，而且第二次也没偷偷建出账号来。
        assertEquals(400, statusOfRegister(code, "newmom", "另一个家长的口令"), "一码一号，第二次必须被拒");
        assertTrue(accounts.accountByUsername("newmom").isEmpty(), "被拒的注册不能留下账号");

        assertEquals(400, statusOfRegister("NOSUCHCODE", "guesser", "随便猜的口令而已"), "乱码必须被拒");
        assertTrue(accounts.accountByUsername("guesser").isEmpty(), "码不对就不该有账号");

        assertEquals(400, statusOfRegister(newInvite(adminToken), "shortpwd", "abc"), "口令太短必须被拒");
        assertTrue(accounts.accountByUsername("shortpwd").isEmpty(), "口令不合格同样不该落账号");

        // 令牌真能用：拿它去建孩子，证明注册出来的不是一张空壳。
        exchangePost("/api/parent/create-child", token, CreateChildRequest.newBuilder()
                .setDisplayName("注册后的孩子").setGrade(3).build().toByteArray());
    }

    /** 自助注册产出的角色只能是 PARENT：协议里没有 role 可填，提权在协议层就不成立。 */
    @Test
    void 注册出来的账号只能是家长_进不了管理员档位() throws Exception {
        String adminToken = adminLogin().getParentToken();
        register(newInvite(adminToken), "onlyparent", "这个账号的口令");

        var login = LoginReply.parseFrom(exchangePost("/api/parent/login", null,
                LoginRequest.newBuilder().setUsername("onlyparent").setPassword("这个账号的口令")
                        .build().toByteArray()));
        assertEquals("PARENT", login.getRole());
        assertTrue(login.getParentToken().startsWith("parent-"), login.getParentToken());

        int code = statusOf("/api/admin/list-accounts", login.getParentToken(),
                ListAccountsRequest.newBuilder().build().toByteArray());
        assertTrue(code == 401 || code == 403, "注册账号竟能进 admin 档位，实得 " + code);
    }

    /**
     * 改口令之后，旧令牌必须当场失效——但只有"人用令牌"该失效。
     * <p>
     * auth_token.subject_id 对设备令牌指的是 device.id，对家长令牌指的是 parent_account.id，
     * 两张表的 id 空间是重叠的。所以这里故意再造一张同号的设备令牌：吊销写成裸 subject_id 匹配的话，
     * 孩子的设备会被家长的改密一起踢下线，而那在真机上表现为闸门静默失联。
     * <p>
     * 用的是注册出来的临时账号，不动 dad 的口令——别的测试还要拿 dad 登录，改了就是一片随机红。
     */
    @Test
    void 改口令只作废人用令牌_不牵连同号设备令牌() throws Exception {
        register(newInvite(adminLogin().getParentToken()), "rotatordad", "最初的口令好几位");
        var first = LoginReply.parseFrom(exchangePost("/api/parent/login", null,
                LoginRequest.newBuilder().setUsername("rotatordad").setPassword("最初的口令好几位")
                        .build().toByteArray()));
        long accountId = accounts.accountByUsername("rotatordad").orElseThrow().id();
        String deviceToken = accounts.issueToken("device", accountId); // 号码撞上账号 id，就是要撞
        assertTrue(accounts.token(deviceToken).isPresent(), "设备令牌先要存在，否则这条反证是空的");

        var changed = ChangePasswordReply.parseFrom(exchangePost("/api/parent/change-password",
                first.getParentToken(), ChangePasswordRequest.newBuilder()
                        .setOldPassword("最初的口令好几位")
                        .setNewPassword("换掉之后的口令")
                        .build().toByteArray()));
        assertTrue(changed.getParentToken().startsWith("parent-"), "改完要给新令牌，否则自己也被踢了");

        assertTrue(accounts.token(first.getParentToken()).isEmpty(), "旧口令换来的令牌必须失效");
        assertTrue(accounts.token(changed.getParentToken()).isPresent(), "新令牌要能用");
        assertTrue(accounts.token(deviceToken).isPresent(), "同号设备的令牌不该被家长改密牵连");

        // 新口令能登录，旧口令登不进。
        var again = LoginReply.parseFrom(exchangePost("/api/parent/login", null,
                LoginRequest.newBuilder().setUsername("rotatordad").setPassword("换掉之后的口令")
                        .build().toByteArray()));
        assertEquals("PARENT", again.getRole());
        assertEquals(401, statusOf("/api/parent/login", null,
                LoginRequest.newBuilder().setUsername("rotatordad").setPassword("最初的口令好几位")
                        .build().toByteArray()));
    }

    /**
     * 找回密码 = 管理员重置。重置之后那口子的人用令牌全部失效，设备令牌照旧——
     * 家长被盗号时管理员改完口令，不能顺手把孩子闸门也踢下线。
     */
    @Test
    void 管理员重置口令只踢人用令牌() throws Exception {
        String adminToken = adminLogin().getParentToken();
        register(newInvite(adminToken), "forgetful", "忘了的口令好几位");
        long target = accounts.accountByUsername("forgetful").orElseThrow().id();
        String victimToken = accounts.issueToken("parent", target);
        String deviceToken = accounts.issueToken("device", target);

        var reply = ResetPasswordReply.parseFrom(exchangePost("/api/admin/reset-password", adminToken,
                ResetPasswordRequest.newBuilder().setAccountId(Long.toString(target))
                        .setNewPassword("管理员给设的新口令").build().toByteArray()));
        assertTrue(reply.getMessage().contains("forgetful"), reply.getMessage());

        assertTrue(accounts.token(victimToken).isEmpty(), "重置前签发的家长令牌必须失效");
        assertTrue(accounts.token(deviceToken).isPresent(), "设备令牌不属于被踢范围");
        assertEquals(401, statusOf("/api/parent/login", null, LoginRequest.newBuilder()
                .setUsername("forgetful").setPassword("忘了的口令好几位").build().toByteArray()));
        assertEquals(200, statusOf("/api/parent/login", null, LoginRequest.newBuilder()
                .setUsername("forgetful").setPassword("管理员给设的新口令").build().toByteArray()));
    }

    /** 管理员档位的负向面：家长令牌既发不了码，也看不到码台账。 */
    @Test
    void 家长令牌发不了注册码() throws Exception {
        String parentToken = login().getParentToken();
        var empty = CreateInviteRequest.newBuilder().build().toByteArray();
        for (var path : List.of("/api/admin/create-invite", "/api/admin/list-invites")) {
            int code = statusOf(path, parentToken, empty);
            assertTrue(code == 401 || code == 403, path + " 不该对家长开放，实得 " + code);
        }
    }

    /** 注册码台账只记事实：谁发的、有没有被用掉。used_by 空串=还没用。 */
    @Test
    void 注册码台账记得谁用过() throws Exception {
        String adminToken = adminLogin().getParentToken();
        String code = newInvite(adminToken);
        register(code, "ledgerdad", "台账用的家长口令");

        var list = ListInvitesReply.parseFrom(exchangePost("/api/admin/list-invites", adminToken,
                ListInvitesRequest.newBuilder().build().toByteArray()));
        var row = list.getInvitesList().stream().filter(i -> i.getCode().equals(code)).findFirst()
                .orElseThrow(() -> new AssertionError("发出去的码没进台账"));
        assertFalse(row.getUsedByAccountId().isBlank(), "用掉了就得记下是谁，不然审计无从查起");
    }

    /** 新建的孩子必须自带默认规则：闸门在家长配置之前就得有阈值，"没有规则"在产品上等于不设限。 */
    @Test
    void 建孩子自动带上默认规则_闸门不会裸奔() throws Exception {
        // 用注册出来的临时家长，不动 dad 的孩子数——另有测试断言家长名下只有一个孩子。
        String token = register(newInvite(adminLogin().getParentToken()), "twokidsdad", "两个孩子的口令");
        var reply = CreateChildReply.parseFrom(exchangePost("/api/parent/create-child", token,
                CreateChildRequest.newBuilder()
                        .setDisplayName("第二个孩子").setGrade(5).build().toByteArray()));
        long childId = Long.parseLong(reply.getChild().getChildId());
        var rule = accounts.rule(childId).orElseThrow(() -> new AssertionError("新孩子没有规则，闸门会裸奔"));
        assertEquals(30, rule.thresholdMinutes(), "默认阈值跟着 quizgate.default-threshold-minutes");
        assertTrue(rule.enabled());
        assertEquals(5, reply.getChild().getGrade());
    }

    /** 坏输入一律 400 说人话，不能落到库里再让数据库报错。 */
    @Test
    void 建孩子的坏输入被挡在门口() throws Exception {
        String token = register(newInvite(adminLogin().getParentToken()), "baddad", "坏输入的家长口令");
        assertEquals(400, statusOf("/api/parent/create-child", token, CreateChildRequest.newBuilder()
                .setDisplayName("  ").setGrade(2).build().toByteArray()));
        assertEquals(400, statusOf("/api/parent/create-child", token, CreateChildRequest.newBuilder()
                .setDisplayName("超龄").setGrade(99).build().toByteArray()));
    }

    // ── 测试辅助 ──────────────────────────────────────────

    private EnrollReply enroll(String installId) throws Exception {
        return enrollWith(installId, "Tianyi-1", 29);
    }

    private String enrollBound() throws Exception {
        var home = newHousehold("答题");
        var enrolled = enroll(freshInstall());
        assertTrue(BindDeviceReply.parseFrom(bind(home.token(), home.childId(), enrolled.getBindCode())).getBound());
        return enrolled.getDeviceToken();
    }

    @Test
    void 未绑定时不共用判分账本且用量保留到绑定后重传() throws Exception {
        var enrolled = enroll(freshInstall());
        String token = enrolled.getDeviceToken();
        var event = UsageEvent.newBuilder().setEventId("unbound-" + installSeq)
                .setOccurredAtMs(System.currentTimeMillis()).setAppPackage("com.video.app").setPlayedMs(2000).build();
        var body = UsageReportRequest.newBuilder().addEvents(event).build().toByteArray();
        assertEquals(400, statusOf("/api/device/usage", token, body));
        assertEquals(0, jdbc.queryForObject("select count(*) from usage_event where event_id = ?", Integer.class,
                event.getEventId()));
        assertEquals(400, statusOf("/api/device/next-question", token, NextQuestionRequest.getDefaultInstance().toByteArray()));
        assertEquals(400, statusOf("/api/device/answer", token, AnswerRequest.newBuilder()
                .setQuestionId("1").setSelectedIndex(0).build().toByteArray()));
        var home = newHousehold("补报");
        assertTrue(BindDeviceReply.parseFrom(bind(home.token(), home.childId(), enrolled.getBindCode())).getBound());
        assertEquals(1, UsageReportReply.parseFrom(report(token, body)).getAccepted());
        assertEquals(Long.parseLong(home.childId()), jdbc.queryForObject(
                "select child_id from usage_event where event_id = ?", Long.class, event.getEventId()));
        assertEquals(1, UsageReportReply.parseFrom(report(token, body)).getDuplicated());
    }

    private String freshInstall() {
        return "install-" + (++installSeq);
    }

    private LoginReply login() throws Exception {
        return loginAs("dad", "correct horse battery");
    }

    /** 一律走真实登录拿令牌：绕过去手插一条 auth_token，测的就不是同一条通路了。 */
    private LoginReply loginAs(String username, String password) throws Exception {
        byte[] body = LoginRequest.newBuilder().setUsername(username).setPassword(password)
                .build().toByteArray();
        return LoginReply.parseFrom(exchangePost("/api/parent/login", null, body));
    }

    /**
     * 管理员账号在这个测试上下文里由代码建出来（启动参数把 admin-username 留空了）。
     * 走真实的 login 拿令牌，不要绕过密码校验去手插一条 auth_token——那样测的就不是同一条通路了。
     */
    private LoginReply adminLogin() throws Exception {
        if (accounts.accountByUsername("root-admin").isEmpty()) {
            accounts.createAccount("root-admin", passwords.encode("管理员的口令"), "平台管理员", "ADMIN");
        }
        return loginAs("root-admin", "管理员的口令");
    }

    /** 只关心状态码的场景（负向断言），别用 exchangePost——那个辅助方法在非 200 时直接抛。 */
    private int statusOf(String path, String token, byte[] body) throws Exception {
        var request = post(path).contentType(PROTOBUF).content(body);
        if (token != null) {
            request.header(TOKEN_HEADER, token);
        }
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

    private String newInvite(String adminToken) throws Exception {
        return CreateInviteReply.parseFrom(exchangePost("/api/admin/create-invite", adminToken,
                CreateInviteRequest.newBuilder().build().toByteArray())).getCode();
    }

    /** 注册成功回令牌；失败时 exchangePost 会直接抛。负向断言走 statusOfRegister。 */
    private String register(String code, String username, String password) throws Exception {
        return RegisterReply.parseFrom(exchangePost("/api/parent/register", null,
                RegisterRequest.newBuilder().setInviteCode(code).setUsername(username)
                        .setPassword(password).setDisplayName(username).build().toByteArray()))
                .getParentToken();
    }

    private int statusOfRegister(String code, String username, String password) throws Exception {
        return statusOf("/api/parent/register", null, RegisterRequest.newBuilder()
                .setInviteCode(code).setUsername(username).setPassword(password)
                .setDisplayName(username).build().toByteArray());
    }

    private NextQuestionReply next(String token, int grade, String knowledgePoint, String exclude) throws Exception {
        byte[] body = NextQuestionRequest.newBuilder()
                .setGrade(grade)
                .setKnowledgePoint(knowledgePoint)
                .setExcludeQuestionId(exclude)
                .build().toByteArray();
        var reply = NextQuestionReply.parseFrom(exchangePost("/api/device/next-question", token, body));
        assertTrue(reply.hasQuestion(), "内置题库不该空转");
        return reply;
    }

    private AnswerReply answer(String token, String questionId, int index) throws Exception {
        byte[] body = AnswerRequest.newBuilder().setQuestionId(questionId).setSelectedIndex(index)
                .build().toByteArray();
        return AnswerReply.parseFrom(exchangePost("/api/device/answer", token, body));
    }

    private byte[] report(String token, byte[] requestBytes) throws Exception {
        return exchangePost("/api/device/usage", token, requestBytes);
    }

    private byte[] usage(String token, UsageEvent... events) throws Exception {
        var builder = UsageReportRequest.newBuilder();
        for (var event : events) {
            builder.addEvents(event);
        }
        return report(token, builder.build().toByteArray());
    }

    private byte[] heartbeat(String token, long knownVersion) throws Exception {
        return heartbeat(token, knownVersion, true);
    }

    private byte[] heartbeat(String token, long knownVersion, boolean accessibilityConnected) throws Exception {
        return exchangePost("/api/device/heartbeat", token,
                HeartbeatRequest.newBuilder().setAccessibilityConnected(accessibilityConnected)
                        .setKnownRuleVersion(knownVersion).build().toByteArray());
    }

    /**
     * 一户人家：一个家长账号 + 一个孩子档案。
     * 告警这类"按户筛"的用例必须各开一户——共用小一会读到别的用例留下的记录，
     * 那 Times 跑到一半就会红，而且红的原因和它测的东西毫无关系。
     */
    private record Household(String token, String childId) {
    }

    private Household newHousehold(String tag) throws Exception {
        String username = "dad-" + tag + "-" + (++installSeq);
        String password = "只看告警的口令-" + installSeq;
        long accountId = accounts.createAccount(username, passwords.encode(password), tag, "PARENT");
        // 走 createChildWithDefaultRule 而不是裸 createChild：没规则的孩子在产品语义上等于不设限。
        long childId = accounts.createChildWithDefaultRule(accountId, tag + "的孩子", 2, 30);
        return new Household(loginAs(username, password).getParentToken(), Long.toString(childId));
    }

    private GateEvent gateEvent(String eventId, GateEventKind kind, long occurredAtMs, String detail) {
        return GateEvent.newBuilder().setEventId(eventId).setKind(kind)
                .setOccurredAtMs(occurredAtMs).setDetail(detail).build();
    }

    private byte[] gateEvents(String deviceToken, GateEvent... events) throws Exception {
        var builder = GateEventReportRequest.newBuilder();
        for (var event : events) {
            builder.addEvents(event);
        }
        return exchangePost("/api/device/gate-event", deviceToken, builder.build().toByteArray());
    }

    private ListAlertsReply listAlerts(String parentToken) throws Exception {
        return ListAlertsReply.parseFrom(exchangePost("/api/parent/list-alerts", parentToken,
                ListAlertsRequest.newBuilder().build().toByteArray()));
    }

    private byte[] bind(String parentToken, String childId, String code) throws Exception {
        return exchangePost("/api/parent/bind-device", parentToken,
                BindDeviceRequest.newBuilder().setChildId(childId).setBindCode(code).build().toByteArray());
    }

    private byte[] saveRule(String parentToken, String childId, int thresholdMinutes) throws Exception {
        return exchangePost("/api/parent/save-rule", parentToken, SaveRuleRequest.newBuilder()
                .setChildId(childId)
                .setRule(RuleSnapshot.newBuilder().setThresholdMinutes(thresholdMinutes)
                        .setDailyLimitMinutes(120).setGraceMinutes(2).setEnabled(true).build())
                .build().toByteArray());
    }

    private byte[] getReport(String parentToken, String childId) throws Exception {
        return exchangePost("/api/parent/get-report", parentToken, GetReportRequest.newBuilder()
                .setChildId(childId)
                .setSinceMs(0L)
                // 放宽一分钟，别让"上报与查询恰好在同一毫秒"变成随机失败。
                .setUntilMs(System.currentTimeMillis() + 60_000)
                .build().toByteArray());
    }

    private void upload(String parentToken, String childId, String stem) throws Exception {
        var draft = QuestionDraft.newBuilder()
                .setSubject(Subject.MATH)
                .setKnowledgePoint("私有知识点")
                .setStem(stem)
                .addOptions("甲").addOptions("乙")
                .setCorrectIndex(0)
                .setExplanation("自家编的题，解释也不给别人。")
                .build();
        var reply = UploadBankReply.parseFrom(exchangePost("/api/parent/upload-bank", parentToken,
                UploadBankRequest.newBuilder().setChildId(childId).addDrafts(draft).build().toByteArray()));
        assertEquals(1, reply.getAccepted(), String.join("；", reply.getErrorsList()));
    }

    private List<String> stemsOf(long childId) {
        return questionStore.candidates(2, childId, "", 0L).stream()
                .map(com.sex.antiaddiction.domain.Rows.Question::stem)
                .toList();
    }

    /**
     * 兜底链的 denyAll 只写在注释里不算数，要打一遍才知道。
     * 断言刻意只认"不是 200"：401 还是 403 取决于容器怎么翻译匿名被拒，那不是要承诺给客户端的契约。
     */
    @Test
    void 没有挂业务权限的路径不会默认开放() throws Exception {
        for (var path : List.of("/actuator/health", "/api", "/api/admin/list-accounts", "/h2-console",
                "/WEB-INF/web.xml")) {
            var code = mvc.perform(get(path)).andReturn().getResponse().getStatus();
            assertTrue(code == 401 || code == 403, path + " 期望被拒，实得 " + code);
        }
    }

    /**
     * 拆闸门事件：设备报上来两条，家长那边必须读到两条，而且积压重传不能刷出重复。
     */
    @Test
    void 孩子动手拆闸门_家长读得到_重传不刷屏() throws Exception {
        var home = newHousehold("告警");
        var device = enrollWith(freshInstall(), "Tianyi-1", 29);
        assertTrue(BindDeviceReply.parseFrom(bind(home.token(), home.childId(), device.getBindCode())).getBound());

        long now = System.currentTimeMillis();
        var first = GateEventReportReply.parseFrom(gateEvents(device.getDeviceToken(),
                gateEvent("ev-old", GateEventKind.ACCESSIBILITY_OFF, now - 60_000L, "无障碍被关了"),
                gateEvent("ev-new", GateEventKind.NOTIFICATIONS_OFF, now, "通知被关了")));
        assertEquals(2, first.getAccepted());

        var alerts = listAlerts(home.token()).getAlertsList();
        assertEquals(2, alerts.size());
        assertEquals(GateEventKind.NOTIFICATIONS_OFF, alerts.get(0).getKind(), "更晚发生的那条必须排在最前");
        assertEquals(home.childId(), alerts.get(0).getChildId());
        assertEquals("通知被关了", alerts.get(0).getDetail());
        assertEquals("Tianyi-1", alerts.get(0).getDeviceModel(), "家长要知道是哪台机器动的手");

        var again = GateEventReportReply.parseFrom(gateEvents(device.getDeviceToken(),
                gateEvent("ev-old", GateEventKind.ACCESSIBILITY_OFF, now - 60_000L, "无障碍被关了"),
                gateEvent("ev-new", GateEventKind.NOTIFICATIONS_OFF, now, "通知被关了")));
        assertEquals(0, again.getAccepted());
        assertEquals(2, again.getDuplicated());
        assertEquals(2, listAlerts(home.token()).getAlertsList().size(), "整批重传不能多出第二次一样的告警");
    }

    /**
     * 告警是按户的：家长甲读不到家长乙家孩子动过的手。
     * 这条不是走流程看看——它是这张表唯一那道归属门，别的用例都在同一户里打转。
     */
    @Test
    void 告警不跨户_别人的孩子动了手我也读不到() throws Exception {
        var mine = newHousehold("本户");
        var theirs = newHousehold("邻户");
        var myDevice = enrollWith(freshInstall(), "Pixel-4", 33);
        var theirDevice = enrollWith(freshInstall(), "Note-4X", 24);
        assertTrue(BindDeviceReply.parseFrom(bind(mine.token(), mine.childId(), myDevice.getBindCode())).getBound());
        assertTrue(BindDeviceReply.parseFrom(bind(theirs.token(), theirs.childId(), theirDevice.getBindCode())).getBound());

        long now = System.currentTimeMillis();
        gateEvents(myDevice.getDeviceToken(), gateEvent("mine-1", GateEventKind.ACCESSIBILITY_OFF, now, "我家关了无障碍"));
        gateEvents(theirDevice.getDeviceToken(), gateEvent("theirs-1", GateEventKind.GATE_INTERRUPTED, now, "邻户抢屏失败"));

        var mineAlerts = listAlerts(mine.token()).getAlertsList();
        assertEquals(1, mineAlerts.size(), "只该读到自家那一条：" + mineAlerts);
        assertEquals(GateEventKind.ACCESSIBILITY_OFF, mineAlerts.get(0).getKind());
        assertEquals(mine.childId(), mineAlerts.get(0).getChildId());

        var theirAlerts = listAlerts(theirs.token()).getAlertsList();
        assertEquals(1, theirAlerts.size());
        assertEquals(theirs.childId(), theirAlerts.get(0).getChildId());

        int anonymous = statusOf("/api/parent/list-alerts", null,
                ListAlertsRequest.newBuilder().build().toByteArray());
        assertTrue(anonymous == 401 || anonymous == 403, "匿名读告警必须被拒，实得 " + anonymous);

        // 反向见证：邻户那条确实写进了库，只是这位家长读不到。
        // 少了这一句，上面两个 size()==1 也可能只是因为整张表压根没写进去。
        // 数的是这两个编号而不是全表：这个内存库被整个测试类共用，全表计数会跟着用例顺序漂。
        assertEquals(2, jdbc.queryForObject(
                "select count(*) from gate_event where event_id in ('mine-1', 'theirs-1')", Integer.class));
    }

    /**
     * 失联是服务端反推的，不是设备报的，所以两头都要钉住：
     * 一是从没打过心跳不能算失联（那是还没跑起来，不是掉了），二是真超时了要能读出来。
     */
    @Test
    void 失联是反推的_新设备不算失联_超时才算() throws Exception {
        var home = newHousehold("失联");
        String installId = freshInstall();
        var fresh = enrollWith(installId, "Redmi-Note8", 24);
        assertTrue(BindDeviceReply.parseFrom(bind(home.token(), home.childId(), fresh.getBindCode())).getBound());

        // 刚绑上、一次心跳都没打：不该红。每台新设备一绑上就报失联，家长三天之内就学会无视红条。
        assertEquals(0, listAlerts(home.token()).getOfflineChildrenCount(), "还没跑起来的设备不算失联");
        var notStarted = childRow(listChildrenBytes(home.token()), home.childId());
        assertFalse(notStarted.getOffline());
        assertEquals(0L, notStarted.getLastHeartbeatMs());

        heartbeat(fresh.getDeviceToken(), 0L, true);
        assertEquals(0, listAlerts(home.token()).getOfflineChildrenCount(), "刚心跳过的不该判失联");

        // 把心跳时刻往回拨一小时，等价于这台机器一小时没出现过。
        jdbc.update("update device set last_heartbeat_at = ? where install_id = ?",
                System.currentTimeMillis() - 3_600_000L, installId);

        var offline = listAlerts(home.token()).getOfflineChildrenList();
        assertEquals(1, offline.size());
        assertEquals(home.childId(), offline.get(0).getChildId());
        assertTrue(offline.get(0).getLastHeartbeatMs() > 0L, "红条要写清最后一次上报是几点");
        assertEquals(180_000L, offline.get(0).getOfflineAfterMs(),
                "阈值要随应答给出去，界面写「超过几分钟没上报」才有依据");
        assertTrue(childRow(listChildrenBytes(home.token()), home.childId()).getOffline(),
                "概览页那一行和红条必须读同一个判断，否则两屏会互相打脸");
    }

    /**
     * 无障碍状态是三值不是布尔。压成布尔会把「这台设备还没上报过」显示成「孩子把无障碍关了」，
     * 那是凭空指控一个孩子。
     */
    @Test
    void 无障碍状态分三种_没上报过不等于被关() throws Exception {
        var home = newHousehold("无障碍");
        var device = enrollWith(freshInstall(), "Pixel-4", 33);
        assertTrue(BindDeviceReply.parseFrom(bind(home.token(), home.childId(), device.getBindCode())).getBound());

        assertEquals(0, childRow(listChildrenBytes(home.token()), home.childId()).getAccessibilityState(),
                "一次心跳都没打过就是不知道，不是关着");

        heartbeat(device.getDeviceToken(), 0L, true);
        assertEquals(1, childRow(listChildrenBytes(home.token()), home.childId()).getAccessibilityState());

        heartbeat(device.getDeviceToken(), 0L, false);
        assertEquals(2, childRow(listChildrenBytes(home.token()), home.childId()).getAccessibilityState(),
                "家长靠这一格区分「孩子动了手」和「闸门还没装好」");
    }

    /**
     * 积压重传里混进脏数据时，宁可退化也不能整批丢：
     * 空 event_id 如果直接入库，第二条就会撞成「重复」被静默丢掉——丢掉的正好是最新那条告警。
     */
    @Test
    void 脏记录不连累整批_空编号与超长说明都要落库() throws Exception {
        var home = newHousehold("脏数据");
        var device = enrollWith(freshInstall(), "Tianyi-1", 29);
        assertTrue(BindDeviceReply.parseFrom(bind(home.token(), home.childId(), device.getBindCode())).getBound());

        long now = System.currentTimeMillis();
        var reply = GateEventReportReply.parseFrom(gateEvents(device.getDeviceToken(),
                gateEvent("", GateEventKind.OVERLAY_OFF, now, "悬浮窗权限被关了"),
                gateEvent("", GateEventKind.GATE_INTERRUPTED, now, "闸".repeat(300)),
                gateEvent("ok-1", GateEventKind.ACCESSIBILITY_OFF, 0L, "没给发生时刻")));
        assertEquals(3, reply.getAccepted(), "三条都得进去，一条都不能被去重吃掉");

        var alerts = listAlerts(home.token()).getAlertsList();
        assertEquals(3, alerts.size());
        assertTrue(alerts.stream().allMatch(a -> a.getDetail().length() <= 200),
                "超长说明要截断落库，而不是报 500 让整批进不了库");
        assertTrue(alerts.stream().noneMatch(a -> a.getOccurredAtMs() <= 0L),
                "没给发生时刻的要用送达时刻兜底，否则这条永远沉在列表最底下");
    }

    /**
     * 静态链只放行点名那几个路径：这里断言的是"安全链不拦"，不是"页面一定在"。
     * 构建产物没拷进来时 404 也算通过——把前端产物写进后端测试会让两边互相等。
     */
    @Test
    void 构建产物路径不被安全链拦但未知路径照旧拒() throws Exception {
        for (var path : List.of("/", "/index.html", "/assets/app.js", "/favicon.ico", "/favicon.svg")) {
            var code = mvc.perform(get(path)).andReturn().getResponse().getStatus();
            assertTrue(code != 401 && code != 403, path + " 不该被安全链拦，实得 " + code);
        }
        // 同一条链的边界：不在点名清单里的静态形状路径必须仍然被拒，否则 "/**" 和这条链没区别。
        for (var path : List.of("/vendor/app.js", "/data/quizgate.mv.db", "/static/app.js")) {
            var code = mvc.perform(get(path)).andReturn().getResponse().getStatus();
            assertTrue(code == 401 || code == 403, path + " 期望被拒，实得 " + code);
        }
    }

    /**
     * 内置题池样本：走生产代码的同一条查询，测试里不抄 SQL。
     * childId 用 0 排除家庭私有题，仅用于挑选测试知识点，不代表未绑定设备能取题。
     */
    private Map<String, List<Rows.Question>> poolByKnowledgePoint() {
        return questionStore.candidates(DEFAULT_GRADE, 0L, "", 0L).stream()
                .collect(Collectors.groupingBy(Rows.Question::knowledgePoint));
    }

    private int correctIndexOf(String questionId) {
        return jdbc.queryForObject("select correct_index from question where id = ?", Integer.class,
                Long.parseLong(questionId));
    }


    @Test
    void gameInventoryAndClassificationAreBoundToHousehold() throws Exception {
        var home=newHousehold("游戏清单");
        var other=newHousehold("另一个家庭");
        var device=enrollWith(freshInstall(),"Game test",30);
        assertTrue(BindDeviceReply.parseFrom(bind(home.token(),home.childId(),device.getBindCode())).getBound());
        var inventory=com.quizgate.proto.AppInventoryRequest.newBuilder().setConsent(true).setCoverage("test")
            .addApps(com.quizgate.proto.InstalledApp.newBuilder().setPackageName("com.tencent.tmgp.sgame").setLabel("王者荣耀"))
            .addApps(com.quizgate.proto.InstalledApp.newBuilder().setPackageName("example.custom.game").setLabel("自定义游戏"))
            .addApps(com.quizgate.proto.InstalledApp.newBuilder().setPackageName("android").setLabel("Android 系统")).build();
        exchangePost("/api/device/app-inventory",device.getDeviceToken(),inventory.toByteArray());
        var query=com.quizgate.proto.ListAppsRequest.newBuilder().setChildId(home.childId()).build();
        var listed=com.quizgate.proto.ListAppsReply.parseFrom(exchangePost("/api/parent/list-apps",home.token(),query.toByteArray()));
        assertEquals(3,listed.getDevices(0).getAppsCount());
        assertTrue(listed.getDevices(0).getAppsList().stream().filter(a->a.getPackageName().contains("sgame")).findFirst().orElseThrow().getIsGame());
        mvc.perform(post("/api/parent/list-apps").header(TOKEN_HEADER,other.token()).contentType(PROTOBUF).content(query.toByteArray())).andExpect(status().isBadRequest());
        exchangePost("/api/parent/classify-app",home.token(),com.quizgate.proto.ClassifyAppRequest.newBuilder()
            .setChildId(home.childId()).setPackageName("example.custom.game").setClassification(1).build().toByteArray());
        exchangePost("/api/device/app-inventory",device.getDeviceToken(),inventory.toByteArray());
        var fetched=GetRuleReply.parseFrom(exchangePost("/api/parent/get-rule",home.token(),GetRuleRequest.newBuilder().setChildId(home.childId()).build().toByteArray())).getRule();
        assertTrue(fetched.getGameMode().getGamePackagesList().contains("example.custom.game"));
        assertTrue(fetched.getGameMode().getGamePackagesList().contains("com.tencent.tmgp.sgame"));
        var unbound=enrollWith(freshInstall(),"Unbound",30);
        mvc.perform(post("/api/device/app-inventory").header(TOKEN_HEADER,unbound.getDeviceToken()).contentType(PROTOBUF).content(inventory.toByteArray())).andExpect(status().isBadRequest());
    }

    @Test
    void gameRuleRejectsMoreThanFifteenMinutesOrTwoExtensions() throws Exception {
        var home=newHousehold("游戏规则");
        var game=com.quizgate.proto.GameModeRule.newBuilder().setEnabled(true).setWarningSeconds(60).setExtensionMinutes(15).setMaxExtensions(2).build();
        var rule=RuleSnapshot.newBuilder().setEnabled(true).setThresholdMinutes(30).setGameMode(game).build();
        var request=SaveRuleRequest.newBuilder().setChildId(home.childId()).setRule(rule).build();
        var saved=RuleSnapshot.parseFrom(exchangePost("/api/parent/save-rule",home.token(),request.toByteArray()));
        assertEquals(15,saved.getGameMode().getExtensionMinutes());assertEquals(2,saved.getGameMode().getMaxExtensions());
        for(var invalid:List.of(game.toBuilder().setExtensionMinutes(16).build(),game.toBuilder().setMaxExtensions(3).build(),game.toBuilder().setWarningSeconds(0).build())) {
            mvc.perform(post("/api/parent/save-rule").header(TOKEN_HEADER,home.token()).contentType(PROTOBUF)
                .content(request.toBuilder().setRule(rule.toBuilder().setGameMode(invalid)).build().toByteArray())).andExpect(status().isBadRequest());
        }
        // An older parent client omitting the new message must not erase an existing game rule.
        var oldClient=RuleSnapshot.parseFrom(exchangePost("/api/parent/save-rule",home.token(),request.toBuilder().setRule(rule.toBuilder().clearGameMode()).build().toByteArray()));
        assertEquals(game,oldClient.getGameMode());
    }

    private record ApprovalFixture(String parent, String child, String token, long version, String cycle) {}
    private ApprovalFixture approvalFixture() throws Exception {
        var home=newHousehold("额外延期");
        var device=enrollWith(freshInstall(),"Approval phone",30);
        bind(home.token(),home.childId(),device.getBindCode());
        exchangePost("/api/device/app-inventory",device.getDeviceToken(),com.quizgate.proto.AppInventoryRequest.newBuilder()
            .setConsent(true).addApps(com.quizgate.proto.InstalledApp.newBuilder().setPackageName("com.tencent.tmgp.sgame").setLabel("王者荣耀")).build().toByteArray());
        var game=com.quizgate.proto.GameModeRule.newBuilder().setEnabled(true).setWarningSeconds(20).setExtensionMinutes(1).setMaxExtensions(2);
        var rule=RuleSnapshot.newBuilder().setEnabled(true).setThresholdMinutes(1).setGameMode(game);
        var saved=RuleSnapshot.parseFrom(exchangePost("/api/parent/save-rule",home.token(),SaveRuleRequest.newBuilder().setChildId(home.childId()).setRule(rule).build().toByteArray()));
        heartbeat(device.getDeviceToken(),0);
        var fixture=new ApprovalFixture(home.token(),home.childId(),device.getDeviceToken(),saved.getVersion(),java.util.UUID.randomUUID().toString());
        approvalHeartbeat(fixture,2,fixture.version(),fixture.version(),fixture.cycle());
        return fixture;
    }
    private void approvalHeartbeat(ApprovalFixture f,int used,long applied,long known,String cycle) throws Exception {
        exchangePost("/api/device/heartbeat",f.token(),HeartbeatRequest.newBuilder().setAccessibilityConnected(true)
            .setKnownRuleVersion(known).setAppliedRuleVersion(applied).setExtensionsUsed(used).setGameCycleId(cycle)
            .setForegroundPackage("com.tencent.tmgp.sgame").build().toByteArray());
    }
    private com.quizgate.proto.GameExtension applyExtra(ApprovalFixture f) throws Exception {
        return com.quizgate.proto.GameExtension.parseFrom(exchangePost("/api/device/game-extension",f.token(),
            com.quizgate.proto.ApplyGameExtensionRequest.newBuilder().setCycleId(f.cycle()).setPackageName("com.tencent.tmgp.sgame").build().toByteArray()));
    }
    private com.quizgate.proto.GameExtension decideExtra(ApprovalFixture f,String id,boolean approve) throws Exception {
        return com.quizgate.proto.GameExtension.parseFrom(exchangePost("/api/parent/decide-game-extension",f.parent(),
            com.quizgate.proto.DecideGameExtensionRequest.newBuilder().setRequestId(id).setApprove(approve).build().toByteArray()));
    }
    @Test void extraApplicationIsScopedAndRetriesReuseOneRequest() throws Exception {
        var f=approvalFixture();
        var body=com.quizgate.proto.ApplyGameExtensionRequest.newBuilder().setCycleId(f.cycle()).setPackageName("com.tencent.tmgp.sgame").build().toByteArray();
        approvalHeartbeat(f,1,f.version(),f.version(),f.cycle());
        mvc.perform(post("/api/device/game-extension").header(TOKEN_HEADER,f.token()).contentType(PROTOBUF).content(body)).andExpect(status().isBadRequest());
        approvalHeartbeat(f,2,f.version(),f.version(),f.cycle());
        var first=applyExtra(f); var retry=applyExtra(f);
        assertEquals(first.getRequestId(),retry.getRequestId());
        assertEquals(com.quizgate.proto.GameExtensionState.GAME_EXTENSION_PENDING,first.getState());
        var other=newHousehold("别家审批");
        var decide=com.quizgate.proto.DecideGameExtensionRequest.newBuilder().setRequestId(first.getRequestId()).setApprove(true).build().toByteArray();
        mvc.perform(post("/api/parent/decide-game-extension").header(TOKEN_HEADER,other.token()).contentType(PROTOBUF).content(decide)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/parent/list-game-extensions").header(TOKEN_HEADER,other.token()).contentType(PROTOBUF)
            .content(com.quizgate.proto.ListGameExtensionsRequest.newBuilder().setChildId(f.child()).build().toByteArray())).andExpect(status().isBadRequest());
        mvc.perform(post("/api/device/game-extension").contentType(PROTOBUF).content(body)).andExpect(status().isForbidden());
        var unbound=enrollWith(freshInstall(),"Unbound approval",30);
        mvc.perform(post("/api/device/game-extension").header(TOKEN_HEADER,unbound.getDeviceToken()).contentType(PROTOBUF).content(body)).andExpect(status().isBadRequest());
    }
    @Test void approvalAndDeviceAcknowledgementAreIdempotent() throws Exception {
        var f=approvalFixture(); var first=applyExtra(f);
        assertEquals(com.quizgate.proto.GameExtensionState.GAME_EXTENSION_APPROVED,decideExtra(f,first.getRequestId(),true).getState());
        assertEquals(1,decideExtra(f,first.getRequestId(),true).getExtensionMinutes());
        var ack=com.quizgate.proto.GameExtensionStatusRequest.newBuilder().setCycleId(f.cycle()).setAppliedRequestId(first.getRequestId()).build();
        for(int i=0;i<2;i++) {
            var reply=com.quizgate.proto.GameExtension.parseFrom(exchangePost("/api/device/game-extension-status",f.token(),ack.toByteArray()));
            assertEquals(com.quizgate.proto.GameExtensionState.GAME_EXTENSION_APPLIED,reply.getState());
        }
        assertEquals(first.getRequestId(),applyExtra(f).getRequestId());
        assertEquals(com.quizgate.proto.GameExtensionState.GAME_EXTENSION_APPLIED,decideExtra(f,first.getRequestId(),true).getState());
        mvc.perform(post("/api/parent/decide-game-extension").header(TOKEN_HEADER,f.parent()).contentType(PROTOBUF)
            .content(com.quizgate.proto.DecideGameExtensionRequest.newBuilder().setRequestId(first.getRequestId()).setApprove(false).build().toByteArray())).andExpect(status().isBadRequest());
    }
    @Test void rejectedRequestCannotReapplyAndOldRoundCannotBeApproved() throws Exception {
        var rejected=approvalFixture();var one=applyExtra(rejected);
        assertEquals(com.quizgate.proto.GameExtensionState.GAME_EXTENSION_REJECTED,decideExtra(rejected,one.getRequestId(),false).getState());
        assertEquals(com.quizgate.proto.GameExtensionState.GAME_EXTENSION_REJECTED,applyExtra(rejected).getState());
        var ended=approvalFixture();var pending=applyExtra(ended);
        approvalHeartbeat(ended,0,0,ended.version(),"");
        assertEquals(com.quizgate.proto.GameExtensionState.GAME_EXTENSION_CLOSED,decideExtra(ended,pending.getRequestId(),true).getState());
        var statusRequest=com.quizgate.proto.GameExtensionStatusRequest.newBuilder().setCycleId(ended.cycle()).build();
        assertEquals(com.quizgate.proto.GameExtensionState.GAME_EXTENSION_CLOSED,com.quizgate.proto.GameExtension.parseFrom(
            exchangePost("/api/device/game-extension-status",ended.token(),statusRequest.toByteArray())).getState());
    }
    @Test void aGrantCannotFollowDeviceToAnotherChild() throws Exception {
        var f=approvalFixture();var request=applyExtra(f);
        decideExtra(f,request.getRequestId(),true);
        var other=newHousehold("另一孩子");
        jdbc.update("update device set child_id=? where id=?",Long.parseLong(other.childId()),Long.parseLong(request.getDeviceId()));
        var result=com.quizgate.proto.GameExtension.parseFrom(exchangePost("/api/device/game-extension-status",f.token(),
            com.quizgate.proto.GameExtensionStatusRequest.newBuilder().setCycleId(f.cycle()).build().toByteArray()));
        assertEquals(com.quizgate.proto.GameExtensionState.GAME_EXTENSION_CLOSED,result.getState());
        assertEquals(com.quizgate.proto.GameExtensionState.GAME_EXTENSION_CLOSED,applyExtra(f).getState());
    }
    @Test void approvalUsesFrozenIssuedDurationAfterParentEditsRule() throws Exception {
        var f=approvalFixture();
        for(int i=0;i<5;i++) {
            var game=com.quizgate.proto.GameModeRule.newBuilder().setEnabled(true).setWarningSeconds(20).setExtensionMinutes(15).setMaxExtensions(1);
            var saved=RuleSnapshot.parseFrom(exchangePost("/api/parent/save-rule",f.parent(),SaveRuleRequest.newBuilder().setChildId(f.child())
                .setRule(RuleSnapshot.newBuilder().setEnabled(true).setThresholdMinutes(1).setGameMode(game)).build().toByteArray()));
            approvalHeartbeat(f,2,f.version(),0,f.cycle());
            assertTrue(saved.getVersion()>f.version());
        }
        var request=applyExtra(f);
        assertEquals(1,request.getExtensionMinutes());
        assertEquals(f.version(),request.getRuleVersion());
        assertEquals(1,decideExtra(f,request.getRequestId(),true).getExtensionMinutes());
        var latest=com.quizgate.proto.ListGameExtensionsReply.parseFrom(exchangePost("/api/parent/list-game-extensions",f.parent(),
            com.quizgate.proto.ListGameExtensionsRequest.newBuilder().setChildId(f.child()).build().toByteArray()));
        assertEquals(1,latest.getRequestsCount());
    }

    private byte[] exchangePost(String path, String token, byte[] body) throws Exception {
        var request = post(path).contentType(PROTOBUF).content(body);
        if (token != null) {
            request.header(TOKEN_HEADER, token);
        }
        var result = mvc.perform(request).andReturn();
        int status = result.getResponse().getStatus();
        if (status != 200) {
            var resolved = result.getResolvedException();
            throw new AssertionError(path + " 期望 200，实得 " + status
                    + "；服务端异常=" + (resolved == null ? "无" : resolved)
                    + "；响应体=" + result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        }
        return result.getResponse().getContentAsByteArray();
    }
}
