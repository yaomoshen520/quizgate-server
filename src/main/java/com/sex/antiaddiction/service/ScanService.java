package com.sex.antiaddiction.service;

import com.quizgate.proto.DeleteQuestionReply;
import com.quizgate.proto.DeleteQuestionRequest;
import com.quizgate.proto.ScanBankReply;
import com.quizgate.proto.ScanBankRequest;
import com.quizgate.proto.ScannedQuestion;
import com.quizgate.proto.VerifyQuestionReply;
import com.quizgate.proto.VerifyQuestionRequest;
import com.quizgate.proto.QuestionDraft;
import com.sex.antiaddiction.infra.ProtobufHttp.BadProtoRequest;
import com.sex.antiaddiction.llm.ArithmeticCheck;
import com.sex.antiaddiction.llm.ExtractFailed;
import com.sex.antiaddiction.llm.QuestionExtractor;
import com.sex.antiaddiction.repo.QuestionStore;
import com.sex.antiaddiction.service.ParentService.Actor;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 拍照识题：一张图 → 题目草稿 → 入库 → 家长核对。
 * <p>
 * 这里的门只有两道，而且都在服务端：
 * 一是算术题由 {@link ArithmeticCheck} 重算一遍，算得出来又和标注一致才直接可用；
 * 二是其余一律以"未核对"落库，闸门候选池（{@link QuestionStore#candidates}）只读已核对的那些，
 * 所以家长点不点这一头，决定孩子到底会不会撞上这道题。
 */
@Service
public class ScanService {

    /** 只认这两种：mime 会拼进发给模型的数据 URL，放开就等于让请求方决定那一段长什么样。 */
    private static final Set<String> ALLOWED_MIME = Set.of("image/jpeg", "image/png");

    private final ChildScope scope;
    private final QuestionStore questions;
    private final QuestionExtractor extractor;
    private final long maxImageBytes;

    public ScanService(ChildScope scope, QuestionStore questions, QuestionExtractor extractor,
            @Value("${quizgate.extractor.max-image-bytes:4194304}") long maxImageBytes) {
        this.scope = scope;
        this.questions = questions;
        this.extractor = extractor;
        this.maxImageBytes = maxImageBytes;
    }

    public ScanBankReply scan(Actor actor, ScanBankRequest request) {
        var child = scope.require(actor, request.getChildId());
        var image = request.getImage();
        if (image.isEmpty()) {
            throw new BadProtoRequest("这次请求里没带图片");
        }
        if (image.size() > maxImageBytes) {
            // 说明白是"哪一步该改"：家长端本来就在传之前压过一遍，撑爆的多是没压的那次。
            throw new BadProtoRequest("图片太大，最多 " + (maxImageBytes / 1024 / 1024) + " MB；"
                    + "请在识别前把图压小一点（长边 1600 像素左右就够清楚了）");
        }
        if (!ALLOWED_MIME.contains(request.getImageMime())) {
            throw new BadProtoRequest("只收 image/jpeg 或 image/png，收到的是 " + request.getImageMime());
        }

        List<QuestionDraft> drafts;
        try {
            drafts = extractor.extract(image.toByteArray(), request.getImageMime());
        } catch (ExtractFailed e) {
            // 引擎那边失败（没启用、没口令、超时、返回读不懂）回 200 + 一句人话，不是 5xx：
            // 这不是服务端坏了，是一次正常的"这次没识别出来"，家长页面上要能直接看见原因。
            return ScanBankReply.newBuilder().setEngineMessage(e.getMessage()).build();
        }

        long bankId = questions.childBank(actor.accountId(), child.id(), request.getBankName());
        var reply = ScanBankReply.newBuilder();
        for (int i = 0; i < drafts.size(); i++) {
            var draft = drafts.get(i);
            var error = QuestionDrafts.validate(i, draft);
            if (error != null) {
                reply.addErrors("识别出的" + error);
                continue;
            }
            var verdict = ArithmeticCheck.of(draft);
            long id = questions.insert(bankId, QuestionDrafts.toRow(draft, child.grade(), verdict.confirmed()));
            reply.addQuestions(ScannedQuestion.newBuilder()
                    .setQuestionId(Long.toString(id))
                    .setDraft(draft)
                    .setVerdict(verdict.verdict())
                    .setCheckNote(verdict.note())
                    .setVerified(verdict.confirmed())
                    .build());
        }
        return reply.build();
    }

    /**
     * 家长核对通过或驳回一道题。
     * <p>
     * 请求里只有题号、没有 child_id，所以归属只能从题号反查（{@link #ownable}）：
     * 这一步要是省了，家长就能拿别人的题号把自己的核对状态当玩具改，跨户写入就是这么开的。
     */
    public VerifyQuestionReply verify(Actor actor, VerifyQuestionRequest request) {
        long id = questionId(request.getQuestionId());
        ownable(actor, id);
        if (!questions.setVerified(id, request.getVerified())) {
            throw new BadProtoRequest("这道题已经不在库里了");
        }
        return VerifyQuestionReply.newBuilder()
                .setQuestionId(Long.toString(id))
                .setVerified(request.getVerified())
                .setMessage(request.getVerified()
                        ? "已核对通过，这道题会出现在孩子的抽题池里。"
                        : "已驳回，孩子不会再抽到这道题。答案确实错了就删掉重传或再拍一次。")
                .build();
    }

    public DeleteQuestionReply delete(Actor actor, DeleteQuestionRequest request) {
        long id = questionId(request.getQuestionId());
        ownable(actor, id);
        if (!questions.delete(id)) {
            throw new BadProtoRequest("这道题已经不在库里了");
        }
        // 用量账（question_ask）不动：删题不该把"这台设备被拦过几次"一起抹掉。
        return DeleteQuestionReply.newBuilder()
                .setMessage("已删掉这道题；它以前拦过孩子的次数仍然记在用量里。").build();
    }

    private void ownable(Actor actor, long questionId) {
        var owner = questions.owner(questionId).orElseThrow(() -> new BadProtoRequest("题不存在"));
        if (owner.builtIn()) {
            throw new BadProtoRequest("内置题库的题不让改也不让删");
        }
        // 这道题落在别人家孩子名下时，这里回的就是"child 不存在"——和越界读规则那一路同一句话。
        scope.require(actor, Long.toString(owner.childId()));
    }

    private static long questionId(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (RuntimeException e) {
            throw new BadProtoRequest("question_id 不是数字");
        }
    }
}
