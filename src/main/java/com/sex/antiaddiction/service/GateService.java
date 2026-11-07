package com.sex.antiaddiction.service;

import com.quizgate.proto.AnswerReply;
import com.quizgate.proto.AnswerRequest;
import com.quizgate.proto.EnrollReply;
import com.quizgate.proto.EnrollRequest;
import com.quizgate.proto.GateEvent;
import com.quizgate.proto.GateEventReportReply;
import com.quizgate.proto.GateEventReportRequest;
import com.quizgate.proto.HeartbeatReply;
import com.quizgate.proto.HeartbeatRequest;
import com.quizgate.proto.NextQuestionReply;
import com.quizgate.proto.NextQuestionRequest;
import com.quizgate.proto.Question;
import com.quizgate.proto.RuleSnapshot;
import com.quizgate.proto.UsageEvent;
import com.quizgate.proto.UsageReportReply;
import com.quizgate.proto.UsageReportRequest;
import com.sex.antiaddiction.domain.Rows;
import com.sex.antiaddiction.domain.Rows.Device;
import com.sex.antiaddiction.domain.Rows.Rule;
import com.sex.antiaddiction.infra.ProtobufHttp;
import com.sex.antiaddiction.repo.AccountStore;
import com.sex.antiaddiction.repo.GateEventStore;
import com.sex.antiaddiction.repo.QuestionStore;
import com.sex.antiaddiction.repo.UsageStore;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 设备侧（app A）的全部业务。
 * <p>
 * 两条纪律贯穿其中：
 * 一是答案永不下发，判分在这台机器上完成，客户端只知道对不对；
 * 二是取不到题不等于放行——响应里 question 缺席时，客户端必须回落到本地内置题库继续拦。
 */
@Service
public class GateService {

    private final AccountStore accounts;
    private final QuestionStore questions;
    private final UsageStore usage;
    private final GateEventStore events;
    private final int defaultGrade;
    private final int defaultThresholdMinutes;
    private final int defaultDailyLimitMinutes;
    private final int defaultGraceMinutes;

    /** gate_event.detail 那一列的宽度。超了截断，别让一句话的尾巴换成整条告警进不了库。 */
    private static final int MAX_DETAIL_CHARS = 200;

    public GateService(AccountStore accounts, QuestionStore questions, UsageStore usage, GateEventStore events,
            @Value("${quizgate.default-grade:2}") int defaultGrade,
            @Value("${quizgate.default-threshold-minutes:30}") int defaultThresholdMinutes,
            @Value("${quizgate.default-daily-limit-minutes:120}") int defaultDailyLimitMinutes,
            @Value("${quizgate.default-grace-minutes:2}") int defaultGraceMinutes) {
        this.accounts = accounts;
        this.questions = questions;
        this.usage = usage;
        this.events = events;
        this.defaultGrade = defaultGrade;
        this.defaultThresholdMinutes = defaultThresholdMinutes;
        this.defaultDailyLimitMinutes = defaultDailyLimitMinutes;
        this.defaultGraceMinutes = defaultGraceMinutes;
    }

    public EnrollReply enroll(EnrollRequest request) {
        if (request.getInstallId().isBlank()) {
            throw new ProtobufHttp.BadProtoRequest("install_id 不能为空");
        }
        var existing = accounts.deviceByInstallId(request.getInstallId());
        Device device = existing.orElseGet(() -> {
            long id = accounts.createDevice(request.getInstallId(), request.getDeviceModel(),
                    request.getAndroidApiLevel(), ProtobufHttp.bindCode());
            return accounts.device(id).orElseThrow();
        });
        String token = accounts.issueToken("device", device.id());
        // 已绑定的设备重装不重新发码，避免旧绑定码在家长端悬空。
        String bindCode = device.childId() == 0L ? device.bindCode() : "";
        return EnrollReply.newBuilder()
                .setDeviceToken(token)
                .setBindCode(bindCode)
                .setServerTimeMs(System.currentTimeMillis())
                .build();
    }

    public NextQuestionReply nextQuestion(long deviceId, NextQuestionRequest request) {
        var device = requireBoundDevice(deviceId);
        long childId = device.childId();
        int grade = request.getGrade() > 0 ? request.getGrade() : gradeOf(childId);
        String knowledgePoint = request.getKnowledgePoint();
        long exclude = parseId(request.getExcludeQuestionId());

        var pick = choose(questions.candidates(grade, childId, knowledgePoint, exclude));
        if (pick.isEmpty()) {
            // 只剩这一道题时原题重发，而不是换个知识点：闸门要的是"这个知识点答对为止"，
            // 换知识点等于让孩子用一道别的题把没掌握的那格抵掉。
            pick = choose(questions.candidates(grade, childId, knowledgePoint, 0L));
        }
        if (pick.isEmpty() && !knowledgePoint.isBlank()) {
            // 这个知识点一道题都没有（多半是设备端存了个已经删掉的知识点串），退回同年级任题，闸门不能空转。
            pick = choose(questions.candidates(grade, childId, "", 0L));
        }
        var reply = NextQuestionReply.newBuilder().setServerTimeMs(System.currentTimeMillis())
                .setRule(ruleProto(ruleOf(childId)));
        pick.ifPresent(q -> {
            usage.openAsk(childId, q.id(), System.currentTimeMillis());
            reply.setQuestion(toProto(q));
        });
        return reply.build();
    }

    public AnswerReply answer(long deviceId, AnswerRequest request) {
        var device = requireBoundDevice(deviceId);
        long childId = device.childId();
        long questionId = parseId(request.getQuestionId());
        var ask = usage.pendingAsk(childId, questionId);
        if (ask.isEmpty()) {
            // 没下发过的题一律不给判定结果，连"这题存在吗"都不透露。
            return AnswerReply.newBuilder().setCorrect(false).setExplanation("这道题已经过期，重新取一道。").build();
        }
        boolean correct = request.getSelectedIndex() == ask.get().correctIndex();
        usage.resolveAsk(ask.get().id(), request.getSelectedIndex(), correct);
        if (correct) {
            return AnswerReply.newBuilder().setCorrect(true).build();
        }
        // 题可能在孩子答完、提交判定之前被家长删掉：那时这道题的解析就真的没了。
        // orElseThrow 会把这一次正常竞争变成 500，孩子端表现为"提交失败"，闸门反而更不可信。
        var question = questions.byId(questionId);
        return AnswerReply.newBuilder().setCorrect(false)
                .setExplanation(question.map(q -> q.explanation())
                        .orElse("这道题已经被家长删掉了，先做下一道。"))
                .build();
    }

    public UsageReportReply report(long deviceId, UsageReportRequest request) {
        var device = requireBoundDevice(deviceId);
        int accepted = 0;
        int duplicated = 0;
        for (UsageEvent event : request.getEventsList()) {
            boolean inserted = usage.insertEvent(event.getEventId(), device.childId(), device.id(),
                    event.getOccurredAtMs(), event.getAppPackage(), event.getPlayedMs(),
                    event.getCorrectCount(), event.getWrongCount(), event.getInterceptionFailed());
            if (inserted) {
                accepted++;
            } else {
                duplicated++;
            }
        }
        return UsageReportReply.newBuilder().setAccepted(accepted).setDuplicated(duplicated)
                .setServerTimeMs(System.currentTimeMillis()).build();
    }

    public HeartbeatReply heartbeat(long deviceId, HeartbeatRequest request) {
        var device = requireDevice(deviceId);
        long now = System.currentTimeMillis();
        accounts.heartbeat(device.id(), now, request.getAccessibilityConnected());
        var rule = ruleOf(device.childId());
        var builder = HeartbeatReply.newBuilder().setServerTimeMs(now);
        if (rule.version() != request.getKnownRuleVersion()) {
            builder.setRuleChanged(true).setRule(ruleProto(rule));
        }
        return builder.build();
    }

    /**
     * 孩子动手拆闸门的那本账。设备只在"它还活着"的时候报得出这些，所以这一张表天然不含卸载。
     * <p>
     * 一条坏记录不会让整批失败：这是积压重传，家长看到的应该是"发生过什么"，
     * 而不是因为设备少填了一个字段就什么都看不见。
     */
    public GateEventReportReply reportGateEvent(long deviceId, GateEventReportRequest request) {
        var device = requireDevice(deviceId);
        long now = System.currentTimeMillis();
        int accepted = 0;
        int duplicated = 0;
        for (GateEvent event : request.getEventsList()) {
            // 空 event_id 不能图省事直接入库：那是一条主键为 '' 的行，第二次上报就"重复"了，
            // 于是真实的第二条告警被静默丢掉。宁可退化成"这条不去重"，也不能退化成"这条不入库"。
            String eventId = event.getEventId().isBlank()
                    ? "gen-" + UUID.randomUUID() : event.getEventId();
            // 设备没给发生时刻（或给了 0）就用送达时刻兜一下：列表按时间排，
            // 1970 年那条会永远沉在底，家长等于没看到。
            long occurred = event.getOccurredAtMs() > 0L ? event.getOccurredAtMs() : now;
            // detail 是设备写的人话，长度上限就是那一列的宽度；超了截断而不是报错，
            // 一句话被切尾巴总好比整条告警进不了库。
            String detail = event.getDetail().length() > MAX_DETAIL_CHARS
                    ? event.getDetail().substring(0, MAX_DETAIL_CHARS) : event.getDetail();
            if (events.insert(eventId, device.childId(), device.id(), event.getKind(), occurred, now, detail)) {
                accepted++;
            } else {
                duplicated++;
            }
        }
        return GateEventReportReply.newBuilder().setAccepted(accepted).setDuplicated(duplicated)
                .setServerTimeMs(now).build();
    }

    /** 家长改了规则要在下次心跳生效；没配就走服务端默认，家长端上线前闸门也得能跑。 */
    public Rule ruleOf(long childId) {
        return accounts.rule(childId).orElseGet(() -> new Rule(childId, defaultThresholdMinutes,
                defaultDailyLimitMinutes, defaultGraceMinutes, true, 0L, List.of()));
    }

    private Device requireDevice(long deviceId) {
        return accounts.device(deviceId).orElseThrow(() -> new ProtobufHttp.BadProtoRequest("设备令牌无效"));
    }

    private Device requireBoundDevice(long deviceId) {
        var device = requireDevice(deviceId);
        if (device.childId() == 0L) {
            // 不能先确认用量再记到 child_id=0：客户端会删掉队列，绑定后永远追不回来。
            // 取题/判分也必须有独立孩子归属，未绑定设备不能共用 child_id=0 的判分台账。
            throw new ProtobufHttp.BadProtoRequest("设备尚未绑定孩子，请家长先输入绑定码；用量稍后重试");
        }
        return device;
    }

    private int gradeOf(long childId) {
        return childId == 0L ? defaultGrade : accounts.child(childId).map(c -> c.grade()).orElse(defaultGrade);
    }

    private static Optional<Rows.Question> choose(List<Rows.Question> pool) {
        if (pool.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(pool.get(ThreadLocalRandom.current().nextInt(pool.size())));
    }

    private static Question toProto(Rows.Question q) {
        var builder = Question.newBuilder()
                .setQuestionId(Long.toString(q.id()))
                .setSubject(q.subject())
                .setGrade(q.grade())
                .setKnowledgePoint(q.knowledgePoint())
                .setStem(q.stem());
        // 正确下标和解析都不进这条消息。
        builder.addAllOptions(q.options());
        return builder.build();
    }

    public static RuleSnapshot ruleProto(Rule rule) {
        return RuleSnapshot.newBuilder()
                .setThresholdMinutes(rule.thresholdMinutes())
                .setDailyLimitMinutes(rule.dailyLimitMinutes())
                .setGraceMinutes(rule.graceMinutes())
                .setEnabled(rule.enabled())
                .setVersion(rule.version())
                .addAllWatchedPackages(rule.watchedPackages())
                .build();
    }

    private static long parseId(String value) {
        try {
            return value == null || value.isBlank() ? 0L : Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
