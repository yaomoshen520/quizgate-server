package com.sex.antiaddiction.service;

import com.quizgate.proto.BankQuestion;
import com.quizgate.proto.BindDeviceReply;
import com.quizgate.proto.BindDeviceRequest;
import com.quizgate.proto.ChangePasswordReply;
import com.quizgate.proto.ChangePasswordRequest;
import com.quizgate.proto.ChildSummary;
import com.quizgate.proto.CreateChildReply;
import com.quizgate.proto.CreateChildRequest;
import com.quizgate.proto.DailyStat;
import com.quizgate.proto.GateAlertItem;
import com.quizgate.proto.GetReportReply;
import com.quizgate.proto.GetReportRequest;
import com.quizgate.proto.GetRuleReply;
import com.quizgate.proto.GetRuleRequest;
import com.quizgate.proto.ListBankReply;
import com.quizgate.proto.ListBankRequest;
import com.quizgate.proto.ListChildrenReply;
import com.quizgate.proto.ListChildrenRequest;
import com.quizgate.proto.ListAlertsReply;
import com.quizgate.proto.OfflineChildItem;
import com.quizgate.proto.LoginReply;
import com.quizgate.proto.LoginRequest;
import com.quizgate.proto.RegisterReply;
import com.quizgate.proto.RegisterRequest;
import com.quizgate.proto.RuleSnapshot;
import com.quizgate.proto.SaveRuleRequest;
import com.quizgate.proto.UploadBankReply;
import com.quizgate.proto.UploadBankRequest;
import com.sex.antiaddiction.domain.Rows;
import com.sex.antiaddiction.domain.Rows.Child;
import com.sex.antiaddiction.domain.Rows.Device;
import com.sex.antiaddiction.infra.ProtobufHttp.BadProtoRequest;
import com.sex.antiaddiction.repo.AccountStore;
import com.sex.antiaddiction.repo.GateEventStore;
import com.sex.antiaddiction.repo.QuestionStore;
import com.sex.antiaddiction.repo.UsageStore;
import com.sex.antiaddiction.security.AppPrincipal;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 家长侧（app B）的全部业务。每个入口先过一道归属检查：
 * 家长只能碰自己孩子的数据，越界一律按"这个 child 不存在"回，不透露别人家的 id。
 */
@Service
public class ParentService {

    /**
     * 调用者身份：账号 id + 角色。ADMIN 可以跨孩子操作，给 Web 后台复用同一条路径。
     * <p>
     * 角色一律从库里读，不信令牌 kind 里带的信息：令牌只管"能不能进这一档"，
     * 谁能跨孩子看数据是账号表里那一列说了算。
     */
    public record Actor(long accountId, String role) {

        boolean isAdmin() {
            return "ADMIN".equals(role);
        }

        /** 家长侧和管理员侧共用这一条身份解析，两边各抄一份的话迟早有一处会信错来源。 */
        public static Actor of(AppPrincipal principal, AccountStore accounts) {
            String role = accounts.account(principal.subjectId())
                    .map(account -> account.role())
                    .orElse("");
            return new Actor(principal.subjectId(), role);
        }
    }

    private final AccountStore accounts;
    private final QuestionStore questions;
    private final UsageStore usage;
    private final GateService gates;
    private final GateEventStore events;
    private final PasswordEncoder passwords;
    private final ChildScope scope;
    private final ZoneId zone;
    private final int defaultThresholdMinutes;
    private final long offlineAfterMs;

    public ParentService(AccountStore accounts, QuestionStore questions, UsageStore usage, GateService gates,
            GateEventStore events, PasswordEncoder passwords, ChildScope scope,
            @Value("${quizgate.default-threshold-minutes:30}") int defaultThresholdMinutes,
            @Value("${quizgate.offline-after-ms:180000}") long offlineAfterMs) {
        this.accounts = accounts;
        this.questions = questions;
        this.usage = usage;
        this.gates = gates;
        this.events = events;
        this.passwords = passwords;
        this.scope = scope;
        this.zone = ZoneId.systemDefault();
        this.defaultThresholdMinutes = defaultThresholdMinutes;
        this.offlineAfterMs = offlineAfterMs;
    }

    public LoginReply login(LoginRequest request) {
        var account = accounts.accountByUsername(request.getUsername());
        if (account.isEmpty() || !passwords.matches(request.getPassword(), account.get().passwordHash())) {
            throw new AuthFailed("账号或密码不对");
        }
        var actor = new Actor(account.get().id(), account.get().role());
        // 令牌 kind 直接跟着账号角色走：管理员拿的就是 admin 令牌。以前两档人共用 parent 令牌，
        // 区分只发生在服务层，安全链上根本没法单独把 /api/admin/** 锁给管理员。
        String kind = actor.isAdmin() ? "admin" : "parent";
        var builder = LoginReply.newBuilder()
                .setParentToken(accounts.issueToken(kind, actor.accountId()))
                .setRole(account.get().role());
        for (var child : visibleChildren(actor)) {
            builder.addChildren(summary(child));
        }
        return builder.build();
    }

    /**
     * 浏览器刷新后重取孩子列表。查询规则和登录那一次必须是同一条：两处各写一份的话，
     * 家长按一次 F5 就能看到别人的孩子，或者自己的孩子凭空消失。
     */
    public ListChildrenReply listChildren(Actor actor) {
        var builder = ListChildrenReply.newBuilder();
        for (var child : visibleChildren(actor)) {
            builder.addChildren(summary(child));
        }
        return builder.build();
    }

    private List<Child> visibleChildren(Actor actor) {
        return actor.isAdmin() ? accounts.allChildren() : accounts.childrenOf(actor.accountId());
    }

    public BindDeviceReply bind(Actor actor, BindDeviceRequest request) {
        var child = requireChild(actor, request.getChildId());
        var code = request.getBindCode().trim().toUpperCase();
        var device = accounts.claimByBindCode(code).orElse(null);
        if (device == null) {
            return BindDeviceReply.newBuilder().setBound(false).setMessage("绑定码无效或已被使用").build();
        }
        if (device.childId() != 0L) {
            return BindDeviceReply.newBuilder().setBound(false).setMessage("这台设备已经被别的账号认领").build();
        }
        accounts.attachChild(device.id(), child.id());
        return BindDeviceReply.newBuilder().setBound(true).setDeviceId(Long.toString(device.id()))
                .setMessage("已绑定").build();
    }

    public UploadBankReply upload(Actor actor, UploadBankRequest request) {
        var child = requireChild(actor, request.getChildId());
        long bankId = questions.childBank(actor.accountId(), child.id(), request.getBankName());
        var acceptedBuilder = UploadBankReply.newBuilder();
        int accepted = 0;
        for (int i = 0; i < request.getDraftsCount(); i++) {
            var draft = request.getDrafts(i);
            var error = QuestionDrafts.validate(i, draft);
            if (error != null) {
                acceptedBuilder.addErrors(error);
                continue;
            }
            // 家长手打的题在录入那一刻就已经是出题人确认过的，所以 verified=true；
            // 只有拍照识别那条路会写 false。
            questions.insert(bankId, QuestionDrafts.toRow(draft, child.grade(), true));
            accepted++;
        }
        return acceptedBuilder.setAccepted(accepted).build();
    }

    @Transactional
    public RuleSnapshot saveRule(Actor actor, SaveRuleRequest request) {
        var child = requireChild(actor, request.getChildId());
        var incoming = request.getRule();
        var game = incoming.hasGameMode() ? incoming.getGameMode() : gates.ruleOf(child.id()).gameMode();
        if (game.getMaxExtensions() < 0 || game.getMaxExtensions() > 2
                || game.getExtensionMinutes() < 0 || game.getExtensionMinutes() > 15 || game.getWarningSeconds() < 0
                || (game.getEnabled() && (game.getExtensionMinutes() < 1 || game.getWarningSeconds() < 1
                    || game.getWarningSeconds() > positive(incoming.getThresholdMinutes(), defaultThresholdMinutes) * 60))) {
            throw new BadProtoRequest("游戏模式须设置提醒时长，单次延期 1—15 分钟，最多延期 0—2 次");
        }
        // 版本用服务端时钟，家长端和闸门端都靠它判断"规则变了没有"。
        var rule = new Rows.Rule(child.id(),
                positive(incoming.getThresholdMinutes(), defaultThresholdMinutes),
                positive(incoming.getDailyLimitMinutes(), 120),
                Math.max(0, incoming.getGraceMinutes()),
                incoming.getEnabled(),
                Math.max(System.currentTimeMillis(), gates.ruleOf(child.id()).version() + 1),
                incoming.getWatchedPackagesList(), game.toBuilder().clearGamePackages().build());
        accounts.saveRule(rule);
        return GateService.ruleProto(accounts.rule(child.id()).orElseThrow());
    }

    /**
     * 家长端读回规则。还没配过的孩子必须回"服务端真正会下发给闸门的那份默认值"——
     * 不是回 0，也不是回家长端表单里硬编码的初始数。否则"界面显示 30 分钟、闸门按别的数在拦"
     * 这种谎就成立了。默认值的唯一来源在 GateService.ruleOf，这里只转发不另算一份。
     */
    public GetRuleReply getRule(Actor actor, GetRuleRequest request) {
        var child = requireChild(actor, request.getChildId());
        return GetRuleReply.newBuilder().setRule(GateService.ruleProto(gates.ruleOf(child.id()))).build();
    }

    /** 家长回显自己上传过的题。读者是出题人本人，所以答案和解析都在这条消息里。 */
    public ListBankReply listBank(Actor actor, ListBankRequest request) {
        var child = requireChild(actor, request.getChildId());
        var builder = ListBankReply.newBuilder();
        for (var question : questions.listForChild(child.id())) {
            builder.addQuestions(BankQuestion.newBuilder()
                    .setQuestionId(Long.toString(question.id()))
                    .setSubject(question.subject())
                    .setGrade(question.grade())
                    .setKnowledgePoint(question.knowledgePoint())
                    .setStem(question.stem())
                    .addAllOptions(question.options())
                    .setCorrectIndex(question.correctIndex())
                    .setExplanation(question.explanation())
                    .setVerified(question.verified())
                    .build());
        }
        return builder.build();
    }

    public GetReportReply report(Actor actor, GetReportRequest request) {
        var child = requireChild(actor, request.getChildId());
        long since = request.getSinceMs();
        long until = request.getUntilMs() > 0 ? request.getUntilMs() : System.currentTimeMillis();
        var builder = GetReportReply.newBuilder();
        usage.daily(child.id(), since, until, zone).forEach((date, t) -> builder.addStats(DailyStat.newBuilder()
                .setDate(date)
                .setPlayedMs(t[0])
                .setCorrectCount((int) t[1])
                .setWrongCount((int) t[2])
                .setInterceptions((int) t[3])
                .setInterceptionFailures((int) t[4])
                .build()));
        return builder.build();
    }

    /**
     * 自助注册。角色在这里写死成 PARENT，请求体里根本没有 role 可填——提权要能在协议层就不成立，
     * 而不是靠前端不显示那个输入框。
     * <p>
     * 事务要上：建号和消耗注册码是两件事，任何一半失败都不能留下"码作废了但号没建成"或反之。
     */
    @Transactional
    public RegisterReply register(RegisterRequest request) {
        String username = request.getUsername().trim();
        if (username.isEmpty() || username.length() > 64) {
            throw new BadProtoRequest("账号名要写成 1~64 个字符");
        }
        requireUsablePassword(request.getPassword());
        // "已存在"这条说得明明白白，是家庭场景下的取舍：家长被重名卡住远比被人枚举账号名常见。
        // 真要防枚举，得连 login 那条一起改，只改一半没有意义。
        if (accounts.accountByUsername(username).isPresent()) {
            throw new BadProtoRequest("这个账号名已经有人用了");
        }
        String displayName = request.getDisplayName().isBlank() ? username : request.getDisplayName().trim();
        long accountId = accounts.createAccount(username, passwords.encode(request.getPassword()),
                displayName, "PARENT");
        if (accounts.consumeInvite(request.getInviteCode().trim().toUpperCase(), accountId) != 1) {
            throw new BadProtoRequest("注册码无效或已经被人用过");
        }
        return RegisterReply.newBuilder()
                .setParentToken(accounts.issueToken("parent", accountId)).build();
    }

    /** 自己改口令。改完必须换令牌：旧口令换来的令牌还活着，这次改密就等于没发生。 */
    public ChangePasswordReply changePassword(Actor actor, ChangePasswordRequest request) {
        var account = accounts.account(actor.accountId())
                .orElseThrow(() -> new BadProtoRequest("账号不存在"));
        if (!passwords.matches(request.getOldPassword(), account.passwordHash())) {
            throw new BadProtoRequest("原口令不对");
        }
        requireUsablePassword(request.getNewPassword());
        accounts.updatePassword(account.id(), passwords.encode(request.getNewPassword()));
        accounts.revokeHumanTokens(account.id());
        String kind = actor.isAdmin() ? "admin" : "parent";
        return ChangePasswordReply.newBuilder()
                .setParentToken(accounts.issueToken(kind, account.id())).build();
    }

    /** 家长给自己添一个孩子。孩子一定挂在调用者名下，请求里没有 parent_id 可填。 */
    public CreateChildReply createChild(Actor actor, CreateChildRequest request) {
        String name = request.getDisplayName().trim();
        if (name.isEmpty() || name.length() > 64) {
            throw new BadProtoRequest("孩子昵称要写成 1~64 个字符");
        }
        int grade = request.getGrade();
        if (grade < 1 || grade > 9) {
            throw new BadProtoRequest("年级只认 1~9");
        }
        long childId = accounts.createChildWithDefaultRule(actor.accountId(), name, grade, defaultThresholdMinutes);
        return CreateChildReply.newBuilder()
                .setChild(summary(accounts.child(childId).orElseThrow(() -> new BadProtoRequest("建完孩子读不回来"))))
                .build();
    }

    private static void requireUsablePassword(String password) {
        // 按字节限长而不是按字符：BCrypt 只看前 72 个字节，多出来的部分被静默截断，
        // 两条不同的长口令可能撞成同一条。中文昵称式口令最容易踩到这个坑（一字三字节）。
        int bytes = password.getBytes(StandardCharsets.UTF_8).length;
        if (bytes < 8) {
            throw new BadProtoRequest("口令太短，至少 8 个字符");
        }
        if (bytes > 72) {
            throw new BadProtoRequest("口令太长，最多 72 字节");
        }
    }

    private ChildSummary summary(Child child) {
        // 一次查询带回绑定状态、机型、API 等级和心跳：这三样都来自 device 表的同一行，
        // 拆成三次单列查询不只多两次往返，还会读出"count 说有设备、max 却是 0"这种自相矛盾的行。
        var device = accounts.deviceOfChild(child.id());
        return ChildSummary.newBuilder()
                .setChildId(Long.toString(child.id()))
                .setDisplayName(child.displayName())
                .setGrade(child.grade())
                .setDeviceBound(device.isPresent())
                .setLastHeartbeatMs(device.map(d -> d.lastHeartbeatMs()).orElse(0L))
                .setDeviceModel(device.map(d -> d.deviceModel()).orElse(""))
                .setAndroidApiLevel(device.map(d -> d.androidApiLevel()).orElse(0))
                .setAccessibilityState(device.map(ParentService::accessibilityState).orElse(0))
                .setOffline(device.map(this::isOffline).orElse(false))
                .build();
    }

    /**
     * 家长后台顶部那一屏：设备亲口说过的动手记录，加上服务端反推出来的失联孩子。
     * <p>
     * 两份数据必须来自同一次归属筛选，所以这里只调一次 visibleChildren：
     * 分两条路径各算一遍的话，会出现红条说这个孩子失联、告警列表却把他家的记录漏掉——
     * 而这种不一致家长只会理解成"这后台不可信"。
     */
    public ListAlertsReply alerts(Actor actor) {
        var visible = visibleChildren(actor);
        var builder = ListAlertsReply.newBuilder();
        for (var child : visible) {
            var device = accounts.deviceOfChild(child.id());
            if (device.isPresent() && isOffline(device.get())) {
                builder.addOfflineChildren(OfflineChildItem.newBuilder()
                        .setChildId(Long.toString(child.id()))
                        .setChildName(child.displayName())
                        .setLastHeartbeatMs(device.get().lastHeartbeatMs())
                        .setDeviceModel(device.get().deviceModel())
                        .setOfflineAfterMs(offlineAfterMs));
            }
        }
        var childIds = visible.stream().map(Child::id).toList();
        for (var alert : events.recent(childIds)) {
            builder.addAlerts(GateAlertItem.newBuilder()
                    .setChildId(alert.childId())
                    .setChildName(alert.childName())
                    .setKind(alert.kind())
                    .setOccurredAtMs(alert.occurredAtMs())
                    .setDetail(alert.detail())
                    .setDeviceModel(alert.deviceModel()));
        }
        return builder.build();
    }

    /**
     * 三值而不是布尔：0=这台设备一次心跳都没打过（所以根本不知道），1=连着，2=被关了。
     * 压成布尔会把"还没跑起来"显示成"孩子关了无障碍"，那是凭空指控。
     */
    private static int accessibilityState(Device device) {
        Boolean connected = device.accessibilityConnected();
        if (connected == null) {
            return 0;
        }
        return connected ? 1 : 2;
    }

    /**
     * 失联是反推，不是实证，所以这一句只回答"他多久没出现了"，绝不回答"为什么没出现"。
     * <p>
     * last_heartbeat_at 为 0 的设备刻意不算失联：那是刚登记、还没跑起来，
     * 算进去的话每一台新设备一绑上就红，家长三天之内就学会无视红条了。
     */
    private boolean isOffline(Device device) {
        long last = device.lastHeartbeatMs();
        return last > 0L && System.currentTimeMillis() - last > offlineAfterMs;
    }

    /** 归属判断只在 ChildScope 那一份里写；核对/删除那两个入口问的是同一句话。 */
    private Child requireChild(Actor actor, String childId) {
        return scope.require(actor, childId);
    }

    private static int positive(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    public static class AuthFailed extends RuntimeException {
        public AuthFailed(String message) {
            super(message);
        }
    }
}
