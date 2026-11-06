package com.sex.antiaddiction;

import com.quizgate.proto.BindDeviceReply;
import com.quizgate.proto.BindDeviceRequest;
import com.quizgate.proto.DeleteQuestionRequest;
import com.quizgate.proto.ListBankReply;
import com.quizgate.proto.ListBankRequest;
import com.quizgate.proto.LoginReply;
import com.quizgate.proto.LoginRequest;
import com.quizgate.proto.NextQuestionReply;
import com.quizgate.proto.NextQuestionRequest;
import com.quizgate.proto.AnswerReply;
import com.quizgate.proto.AnswerRequest;
import com.quizgate.proto.CheckVerdict;
import com.quizgate.proto.EnrollReply;
import com.quizgate.proto.EnrollRequest;
import com.quizgate.proto.QuestionDraft;
import com.quizgate.proto.ScanBankReply;
import com.quizgate.proto.ScanBankRequest;
import com.quizgate.proto.Subject;
import com.quizgate.proto.VerifyQuestionReply;
import com.quizgate.proto.VerifyQuestionRequest;
import com.sex.antiaddiction.llm.ExtractFailed;
import com.sex.antiaddiction.llm.QuestionExtractor;
import com.sex.antiaddiction.repo.AccountStore;
import com.sex.antiaddiction.repo.QuestionStore;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 拍照识题这条链的门：识别结果先落库成"未核对"，闸门只抽已核对的，家长核对才放行。
 * <p>
 * 识别引擎在这里是假实现。真引擎（通义多模态）默认就是关的，测试也不该去碰它——
 * 一旦单测开始依赖外网，它绿不绿就取决于阿里云今天心情如何，而这条链真正的门是"核对状态"那一步，
 * 用假实现反而能把它想怎么喂就怎么喂（自证通过的、证明不了的、失败的、超大的）。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:scanflow;DB_CLOSE_DELAY=-1",
        "quizgate.bootstrap.parent-username=dad",
        "quizgate.bootstrap.parent-password=correct horse battery",
        "quizgate.bootstrap.admin-username=adminroot",
        "quizgate.bootstrap.admin-password=admin root password",
        "quizgate.extractor.enabled=false"
})
@AutoConfigureMockMvc
class ScanFlowTest {

    private static final MediaType PROTOBUF = MediaType.parseMediaType("application/x-protobuf");
    private static final String TOKEN_HEADER = "X-Quizgate-Token";
    /** JPEG 的开头结尾四个字节：假实现只看有没有内容，不解析图片。 */
    private static final byte[] IMAGE = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9};

    static final List<QuestionDraft> canned = new CopyOnWriteArrayList<>();
    static volatile boolean failEngine;
    static final AtomicInteger calls = new AtomicInteger();
    static volatile byte[] lastImage;
    static volatile String lastMime;

    @TestConfiguration
    static class FakeEngine {
        @Bean
        @Primary
        QuestionExtractor fakeExtractor() {
            return (image, mime) -> {
                calls.incrementAndGet();
                lastImage = image;
                lastMime = mime;
                if (failEngine) {
                    throw new ExtractFailed("假引擎这次没认出题");
                }
                return List.copyOf(canned);
            };
        }
    }

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

    @BeforeEach
    void resetEngine() {
        canned.clear();
        failEngine = false;
        calls.set(0);
        lastImage = null;
        lastMime = null;
    }

    /**
     * 这 9 条测试共用同一个内存库，样本必须各占一份知识点与题号。
     * 早先所有测试都插同一条 "3 + 5 = ？"，于是"越权核对"那条把 dad 家的essay 点成已核对之后，
     * 另一条测试的"未核对不许进池"就莫名绿不了——测的是污染，不是门。
     */
    private static final AtomicInteger caseSeq = new AtomicInteger();

    /** 能被服务端验算的算术题：自证通过。 */
    private static QuestionDraft arithmetic() {
        int seq = caseSeq.incrementAndGet();
        return QuestionDraft.newBuilder()
                .setSubject(Subject.MATH).setGrade(0).setKnowledgePoint("扫描知识点-" + seq)
                .setStem(seq + " + 5 = ？").addOptions(Integer.toString(seq + 4))
                .addOptions(Integer.toString(seq + 5)).addOptions(Integer.toString(seq + 6))
                .setCorrectIndex(1).setExplanation("加数与得数随序号变，所以这道题只属于当前这一条测试。").build();
    }

    /** 服务端证明不了的题：必须停在"未核对"。 */
    private static QuestionDraft essay() {
        int seq = caseSeq.incrementAndGet();
        return QuestionDraft.newBuilder()
                .setSubject(Subject.CHINESE).setGrade(0).setKnowledgePoint("扫描近义词-" + seq)
                .setStem("「高兴」的近义词-" + seq + " 是哪个？").addOptions("快乐").addOptions("难过")
                .setCorrectIndex(0).setExplanation("高兴和快乐都表示心情愉快。").build();
    }

    @Test
    void 识别结果先落库成未核对_家长核对之后闸门才抽得到() throws Exception {
        var login = login("dad", "correct horse battery");
        String parentToken = login.getParentToken();
        String childId = login.getChildren(0).getChildId();
        canned.add(arithmetic());
        canned.add(essay());

        var reply = ScanBankReply.parseFrom(scan(parentToken, childId, IMAGE, "image/jpeg"));
        assertEquals(2, reply.getQuestionsCount(), String.join("；", reply.getErrorsList()));
        assertTrue(calls.get() > 0, "识别接口确实被调了一次");

        // 服务端算得出来的那道直接可用，算不出来的那道停在未核对——两道的差别必须由结论说清楚。
        var first = reply.getQuestions(0);
        var second = reply.getQuestions(1);
        assertEquals(CheckVerdict.CHECK_CONFIRMED, first.getVerdict(), first.getCheckNote());
        assertTrue(first.getVerified(), "自证通过的题不必再等家长点一次");
        assertEquals(CheckVerdict.CHECK_MANUAL, second.getVerdict(), second.getCheckNote());
        assertFalse(second.getVerified(), "证明不了的题不许自动进孩子的抽题池");
        assertFalse(first.getQuestionId().isBlank(), "没有题号家长就没法核对");

        assertTrue(stems(childId).contains(first.getDraft().getStem()));
        assertFalse(stems(childId).contains(second.getDraft().getStem()),
                "未核对的题出现在候选池里，等于家长那道核对白设");

        var verified = VerifyQuestionReply.parseFrom(exchange("/api/parent/verify-question", parentToken,
                VerifyQuestionRequest.newBuilder().setQuestionId(second.getQuestionId()).setVerified(true)
                        .build().toByteArray()));
        assertTrue(verified.getVerified());
        assertTrue(stems(childId).contains(second.getDraft().getStem()), "核对通过就该能抽到");

        exchange("/api/parent/verify-question", parentToken,
                VerifyQuestionRequest.newBuilder().setQuestionId(second.getQuestionId()).setVerified(false)
                        .build().toByteArray());
        assertFalse(stems(childId).contains(second.getDraft().getStem()), "驳回之后必须立刻抽不到");
    }

    /** 回显里要带上核对状态，否则家长面对一堆题分不清哪道还没点头。 */
    @Test
    void 回显带核对状态() throws Exception {
        var login = login("dad", "correct horse battery");
        var draft = essay();
        canned.add(draft);
        var reply = ScanBankReply.parseFrom(scan(login.getParentToken(), login.getChildren(0).getChildId(),
                IMAGE, "image/jpeg"));
        var bank = ListBankReply.parseFrom(exchange("/api/parent/list-bank", login.getParentToken(),
                ListBankRequest.newBuilder().setChildId(login.getChildren(0).getChildId()).build().toByteArray()));
        var row = bank.getQuestionsList().stream()
                .filter(q -> q.getQuestionId().equals(reply.getQuestions(0).getQuestionId())).findFirst()
                .orElseThrow(() -> new AssertionError("刚识别入库的题没出现在回显里"));
        assertFalse(row.getVerified(), "未核对在回显里就得写成未核对");
        assertEquals(draft.getStem(), row.getStem());
        assertEquals(2, row.getOptionsCount());
    }

    @Test
    void 引擎失败时回一句人话而且一道题都没落库() throws Exception {
        var login = login("dad", "correct horse battery");
        String childId = login.getChildren(0).getChildId();
        canned.add(arithmetic());
        int before = stems(childId).size();
        failEngine = true;

        var reply = ScanBankReply.parseFrom(scan(login.getParentToken(), childId, IMAGE, "image/jpeg"));
        assertTrue(reply.getEngineMessage().contains("假引擎"), reply.getEngineMessage());
        assertEquals(0, reply.getQuestionsCount(), "引擎没给结果就不能凭空造出题来");
        assertEquals(before, stems(childId).size(), "失败这一次不该动库");
    }

    /** 坏输入挡在门口，而且一次都不该去打引擎。 */
    @Test
    void 空图_超大图_错mime都被挡在门口() throws Exception {
        var login = login("dad", "correct horse battery");
        String parentToken = login.getParentToken();
        String childId = login.getChildren(0).getChildId();

        assertEquals(400, status("/api/parent/scan-bank", parentToken,
                ScanBankRequest.newBuilder().setChildId(childId).setImageMime("image/jpeg")
                        .build().toByteArray()), "空图必须被拒");
        assertEquals(400, status("/api/parent/scan-bank", parentToken,
                ScanBankRequest.newBuilder().setChildId(childId)
                        .setImage(com.google.protobuf.ByteString.copyFromUtf8("jpeg"))
                        .setImageMime("image/svg+xml").build().toByteArray()), "只收 jpeg/png");
        // 4 MB + 1 字节：正好压过 quizgate.extractor.max-image-bytes 那条线。
        assertEquals(400, status("/api/parent/scan-bank", parentToken,
                ScanBankRequest.newBuilder().setChildId(childId)
                        .setImage(com.google.protobuf.ByteString.copyFrom(new byte[4 * 1024 * 1024 + 1]))
                        .setImageMime("image/jpeg").build().toByteArray()), "大图要当场拒掉");
        assertEquals(0, calls.get(), "被字段校验挡住的请求不该花钱去打识别引擎");
    }

    @Test
    void 越权核对与删除一律被拒_内置题谁都动不了() throws Exception {
        var dad = login("dad", "correct horse battery");
        canned.add(essay());
        String mine = ScanBankReply.parseFrom(scan(dad.getParentToken(), dad.getChildren(0).getChildId(),
                IMAGE, "image/jpeg")).getQuestions(0).getQuestionId();

        long otherParent = accounts.createAccount("otherdad", passwords.encode("另一家的口令"), "别人", "PARENT");
        long otherChild = accounts.createChild(otherParent, "别人家的孩子", 2);
        String otherToken = login("otherdad", "另一家的口令").getParentToken();

        for (var path : List.of("/api/parent/verify-question", "/api/parent/delete-question")) {
            byte[] body = path.endsWith("verify-question")
                    ? VerifyQuestionRequest.newBuilder().setQuestionId(mine).setVerified(true).build().toByteArray()
                    : DeleteQuestionRequest.newBuilder().setQuestionId(mine).build().toByteArray();
            assertEquals(400, status(path, otherToken, body), path + " 不该让别的家长碰别人家的题");
            assertTrue(status(path, null, body) == 401 || status(path, null, body) == 403,
                    path + " 不该让匿名请求进来");
        }
        assertFalse(VerifyQuestionReply.parseFrom(exchange("/api/parent/verify-question", dad.getParentToken(),
                VerifyQuestionRequest.newBuilder().setQuestionId(mine).setVerified(true).build().toByteArray()))
                .getVerified() == false, "自己家的题当然改得动");

        // 内置题：家长既不能驳回也不能删——删掉了闸门就没题可出了。
        for (var path : List.of("/api/parent/verify-question", "/api/parent/delete-question")) {
            byte[] body = path.endsWith("verify-question")
                    ? VerifyQuestionRequest.newBuilder().setQuestionId("1").setVerified(false).build().toByteArray()
                    : DeleteQuestionRequest.newBuilder().setQuestionId("1").build().toByteArray();
            assertEquals(400, status(path, dad.getParentToken(), body), path + " 不该动内置题");
        }
        /*
         * 管理员这一路才是 builtIn 那道检查的唯一证人。家长碰内置题时被 ChildScope 先挡下了
         * （内置题库不挂在任何孩子名下，scope_child_id=0 查不到孩子，回"child 不存在"），
         * 所以把 builtIn 判断删掉，上面那一圈家长断言照样全绿——变异实测过，确实杀不掉。
         * 管理员可以跨户，ChildScope 对他不设障，唯一还拦着的就是这道检查：
         * 内置题一旦被驳回，candidates() 就再也抽不到题，闸门等于自己把自己关掉。
         * 断言取的是响应体文案，因为"内置题不让动"和"孩子不存在"在 400 这一层无法靠状态码区分。
         */
        var admin = login("adminroot", "admin root password");
        assertTrue(rejectMessage("/api/parent/verify-question", admin.getParentToken(),
                        VerifyQuestionRequest.newBuilder().setQuestionId("1").setVerified(false).build().toByteArray())
                        .contains("内置"), "管理员驳回内置题时要说清是内置题不让动，不是查无此孩子");
        assertTrue(rejectMessage("/api/parent/delete-question", admin.getParentToken(),
                        DeleteQuestionRequest.newBuilder().setQuestionId("1").build().toByteArray())
                        .contains("内置"), "管理员删内置题同上");
        assertTrue(jdbc.queryForObject("select verified from question where id = 1", Boolean.class),
                "越界请求必须一个字都没落到内置题上");
        assertEquals(1, jdbc.queryForObject("select count(*) from question where id = 1", Integer.class));
    }

    /** 管理员能替别的家庭核对：这是"管理员可以配置一切"的正向能力，和上面那条拒家长配对才说明门在数据归属上。 */
    @Test
    void 管理员能替别人家的题点头() throws Exception {
        var other = registerFamily("grandmad", "奶奶家的口令", "grandma-child");
        canned.add(essay());
        String mine = ScanBankReply.parseFrom(scan(other.getParentToken(), other.getChildren(0).getChildId(),
                IMAGE, "image/jpeg")).getQuestions(0).getQuestionId();

        var admin = login("adminroot", "admin root password");
        var reply = VerifyQuestionReply.parseFrom(exchange("/api/parent/verify-question", admin.getParentToken(),
                VerifyQuestionRequest.newBuilder().setQuestionId(mine).setVerified(true).build().toByteArray()));
        assertTrue(reply.getVerified());
    }

    /**
     * 隐私这一条要能机械地失败：库里不许存在任何一格能装图片字节。
     * <p>
     * "原图不入库"光写在注释里不算——只要有人给 question 表加一列 blob，或者把 base64 串塞进 explanation，
     * 家长的手机相册内容就从服务端漏出去了。这里用列名白名单 + 类型黑名单两头钉住。
     */
    @Test
    void 原图不入库_库里没有任何一格能装二进制() throws Exception {
        var login = login("dad", "correct horse battery");
        var draft = arithmetic();
        canned.add(draft);
        scan(login.getParentToken(), login.getChildren(0).getChildId(), IMAGE, "image/jpeg");
        assertArrayEquals(IMAGE, lastImage, "图片字节要原样交到引擎手上——但只到这一步为止");
        assertEquals("image/jpeg", lastMime);

        /*
         * 白名单，不是黑名单。原本这里列的是"能装二进制的类型名"，结果给 question 加一列 varbinary
         * 之后测试照样绿——H2 报出来的名字和黑名单里的写法不一样，一条写错的名单等于没有名单。
         * 反过来只认"这个库里就该有的那几种文本/数字类型"，任何没见过的类型名一律算越界，
         * 包括以后有人换数据库、加一个新类型的情形。
         */
        var textOnly = java.util.Set.of("CHARACTER VARYING", "VARCHAR", "BIGINT", "INTEGER", "INT",
                "SMALLINT", "BOOLEAN", "TIMESTAMP", "TIMESTAMP WITH TIME ZONE", "DECFLOAT", "NUMERIC", "DECIMAL");
        var offenders = jdbc.query("select table_name, column_name, upper(data_type) as dt"
                        + " from information_schema.columns where upper(table_schema) = 'PUBLIC'",
                (rs, n) -> rs.getString("table_name").toLowerCase() + "."
                        + rs.getString("column_name").toLowerCase() + " : " + rs.getString("dt")).stream()
                .filter(col -> !textOnly.contains(col.substring(col.indexOf(" : ") + 3)))
                .toList();
        assertTrue(offenders.isEmpty(), "库里出现了装得下图片字节的列，原图就有地方存了：" + offenders);

        var questionColumns = jdbc.queryForList("""
                select lower(column_name) from information_schema.columns
                where lower(table_name) = 'question'""", String.class);
        assertTrue(questionColumns.containsAll(List.of("stem", "correct_index", "verified")),
                "核对状态得真在 question 表上：" + questionColumns);
        assertTrue(questionColumns.stream().noneMatch(name -> name.contains("image") || name.contains("photo")
                        || name.contains("base64") || name.contains("blob")),
                "question 表里冒出了图片列：" + questionColumns);
        // 入库的只有文本：题干和解析都读得回来，而且读回来就是识别出来的那段字。
        assertEquals(draft.getStem(), jdbc.queryForObject(
                "select stem from question where stem = ?", String.class, draft.getStem()));
        assertEquals(draft.getExplanation(), jdbc.queryForObject(
                "select explanation from question where stem = ?", String.class, draft.getStem()));
    }

    /**
     * 孩子答完、提交判定之前家长把题删了：这一次不能变成 500，也不能把孩子卡死。
     * <p>
     * 落库实况：pendingAsk 是 join question 取 correct_index 的，题一行删掉这条 join 就空了，
     * 于是走的是"没下发过"那一句过期提示。语义上正合适——闸门会重新出一道，孩子不会被卡住。
     * 断言只认"200 + 判错 + 说人话"，具体哪一句由删题落在哪一步决定，不是给客户端的契约。
     */
    @Test
    void 题被家长删掉之后这一答说人话不报500() throws Exception {
        var login = login("dad", "correct horse battery");
        String parentToken = login.getParentToken();
        String childId = login.getChildren(0).getChildId();
        var draft = arithmetic();
        canned.add(draft);
        String questionId = ScanBankReply.parseFrom(scan(parentToken, childId, IMAGE, "image/jpeg"))
                .getQuestions(0).getQuestionId();

        var install = "scan-flow-" + System.nanoTime();
        var enrolled = EnrollReply.parseFrom(exchange("/api/device/enroll", null, EnrollRequest.newBuilder()
                .setInstallId(install).setDeviceModel("Tianyi-1").setAndroidApiLevel(29).build().toByteArray()));
        assertTrue(BindDeviceReply.parseFrom(exchange("/api/parent/bind-device", parentToken,
                BindDeviceRequest.newBuilder().setChildId(childId).setBindCode(enrolled.getBindCode())
                        .build().toByteArray())).getBound());
        String deviceToken = enrolled.getDeviceToken();

        var served = NextQuestionReply.parseFrom(exchange("/api/device/next-question", deviceToken,
                NextQuestionRequest.newBuilder().setGrade(2).setKnowledgePoint(draft.getKnowledgePoint())
                        .build().toByteArray()));
        assertEquals(questionId, served.getQuestion().getQuestionId(), "指定知识点就该抽到刚扫描入库那道");

        exchange("/api/parent/delete-question", parentToken,
                DeleteQuestionRequest.newBuilder().setQuestionId(questionId).build().toByteArray());
        assertEquals(0, jdbc.queryForObject("select count(*) from question_option where question_id = ?",
                Integer.class, Long.parseLong(questionId)), "删题要连选项行一起删，别留孤儿行");

        var answer = AnswerReply.parseFrom(exchange("/api/device/answer", deviceToken,
                AnswerRequest.newBuilder().setQuestionId(questionId).setSelectedIndex(0)
                        .build().toByteArray()));
        assertFalse(answer.getCorrect());
        String note = answer.getExplanation();
        assertTrue(note.contains("重新") || note.contains("下一道"),
                "删题落在哪一步都得不是一句系统错误，并且要告诉孩子重取：" + note);

        // 真正要守的底线：这道题没了，闸门还能继续出题，孩子不会被卡在"这题已经不在了"上。
        var again = NextQuestionReply.parseFrom(exchange("/api/device/next-question", deviceToken,
                NextQuestionRequest.newBuilder().setGrade(2).build().toByteArray()));
        assertFalse(again.getQuestion().getStem().isBlank(), "删掉一道题之后仍然要能取到下一道");
    }

    @Test
    void 识别出的坏草稿逐条报错而不毁掉整批() throws Exception {
        var login = login("dad", "correct horse battery");
        String childId = login.getChildren(0).getChildId();
        canned.add(arithmetic());
        canned.add(QuestionDraft.newBuilder()
                .setSubject(Subject.MATH).setGrade(0).setKnowledgePoint("坏草稿")
                .setStem("只有一个选项的题").addOptions("8").setCorrectIndex(0)
                .setExplanation("不该收下").build());

        var reply = ScanBankReply.parseFrom(scan(login.getParentToken(), childId, IMAGE, "image/jpeg"));
        assertEquals(1, reply.getQuestionsCount(), "好的那道要收下");
        assertEquals(1, reply.getErrorsCount(), "报错要逐条回给家长：" + reply.getErrorsList());
        assertTrue(reply.getErrors(0).contains("至少两个选项"), reply.getErrors(0));
        assertFalse(stems(childId).contains("只有一个选项的题"), "报错的草稿不许落库");
    }

    // ── 辅助 ────────────────────────────────────────────

    private byte[] scan(String parentToken, String childId, byte[] image, String mime) throws Exception {
        return exchange("/api/parent/scan-bank", parentToken, ScanBankRequest.newBuilder()
                .setChildId(childId).setImage(com.google.protobuf.ByteString.copyFrom(image))
                .setImageMime(mime).build().toByteArray());
    }

    /** 走生产代码的候选池查询：这个池子里有没有题，就是家长那道核对有没有生效。 */
    private List<String> stems(String childId) {
        return questionStore.candidates(2, Long.parseLong(childId), "", 0L).stream()
                .map(com.sex.antiaddiction.domain.Rows.Question::stem)
                .toList();
    }

    private LoginReply registerFamily(String username, String password, String childName) throws Exception {
        String adminToken = login("adminroot", "admin root password").getParentToken();
        String code = com.quizgate.proto.CreateInviteReply.parseFrom(exchange("/api/admin/create-invite",
                adminToken, com.quizgate.proto.CreateInviteRequest.newBuilder().build().toByteArray())).getCode();
        var token = com.quizgate.proto.RegisterReply.parseFrom(exchange("/api/parent/register", null,
                com.quizgate.proto.RegisterRequest.newBuilder().setInviteCode(code).setUsername(username)
                        .setPassword(password).setDisplayName(username).build().toByteArray()))
                .getParentToken();
        exchange("/api/parent/create-child", token, com.quizgate.proto.CreateChildRequest.newBuilder()
                .setDisplayName(childName).setGrade(2).build().toByteArray());
        return login(username, password);
    }

    private LoginReply login(String username, String password) throws Exception {
        return LoginReply.parseFrom(exchange("/api/parent/login", null, LoginRequest.newBuilder()
                .setUsername(username).setPassword(password).build().toByteArray()));
    }

    private int status(String path, String token, byte[] body) throws Exception {
        var request = post(path).contentType(PROTOBUF).content(body);
        if (token != null) {
            request.header(TOKEN_HEADER, token);
        }
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

    /** 拒掉之外还要看它凭什么拒：两道门都回 400，只有文案能把它们分开。 */
    private String rejectMessage(String path, String token, byte[] body) throws Exception {
        var result = mvc.perform(post(path).contentType(PROTOBUF).content(body)
                .header(TOKEN_HEADER, token)).andReturn();
        int code = result.getResponse().getStatus();
        String text = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(400, code, path + " 期望被拒，实得 " + code + "；响应体=" + text);
        return text;
    }

    private byte[] exchange(String path, String token, byte[] body) throws Exception {
        var request = post(path).contentType(PROTOBUF).content(body);
        if (token != null) {
            request.header(TOKEN_HEADER, token);
        }
        var result = mvc.perform(request).andReturn();
        int status = result.getResponse().getStatus();
        if (status != 200) {
            throw new AssertionError(path + " 期望 200，实得 " + status
                    + "；响应体=" + result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        }
        return result.getResponse().getContentAsByteArray();
    }
}
