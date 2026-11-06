package com.sex.antiaddiction.repo;

import com.quizgate.proto.Subject;
import com.sex.antiaddiction.domain.Rows;
import com.sex.antiaddiction.domain.Rows.Question;
import com.sex.antiaddiction.infra.SqlKeys;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class QuestionStore {

    private static final String SELECT_JOINED = """
            select q.id, q.subject, q.grade, q.knowledge_point, q.stem,
                   q.correct_index, q.explanation, q.verified, o.idx, o.text
            from question q
            join question_bank b on b.id = q.bank_id
            left join question_option o on o.question_id = q.id
            """;

    private final JdbcTemplate jdbc;

    public QuestionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 候选池只含平台内置题库和这个孩子自己的私有题库——家长上传的题不能串到别的孩子屏幕上。
     * <p>
     * 而且只含"已核对"的题：拍照识别出来的草稿在家长点头之前不能出现在孩子屏幕上。
     * 这一条是闸门侧唯一的入口，所以过滤写在这里、不写在服务层——服务层将来多一个调用方就漏一次。
     *
     * @param knowledgePoint 非空表示"再来一道同知识点变式题"
     * @param excludeId      刚答错那道，不能再给
     */
    public List<Question> candidates(int grade, long childId, String knowledgePoint, long excludeId) {
        var args = new ArrayList<>();
        var sql = new StringBuilder(SELECT_JOINED + "where q.grade = ?"
                + " and q.verified = true"
                + " and (b.owner_parent_id is null or b.scope_child_id = ?)");
        args.add(grade);
        args.add(childId);
        if (knowledgePoint != null && !knowledgePoint.isBlank()) {
            sql.append(" and q.knowledge_point = ?");
            args.add(knowledgePoint);
        }
        if (excludeId != 0L) {
            sql.append(" and q.id <> ?");
            args.add(excludeId);
        }
        // 按 q.id 排即可：同一道题的选项行必须连续，group() 靠这个把选项归位。
        sql.append(" order by q.id, o.idx");
        return group(jdbc.query(sql.toString(), QuestionStore::toRawRow, args.toArray()));
    }

    /**
     * 按 id 取整道题，不过滤 verified。
     * <p>
     * 这里刻意和 candidates() 相反：答错之后要回解析，题是孩子已经在屏幕上答过的，
     * 家长中途把它驳回不能让那次判定失败。真正拦住"没核对的题进屏幕"的是 candidates()。
     */
    public Optional<Question> byId(long id) {
        var rows = group(jdbc.query(SELECT_JOINED + "where q.id = ?", QuestionStore::toRawRow, id));
        return rows.stream().findFirst();
    }

    /**
     * 家长回显：这个孩子私有题库里的全部题，含未核对的那些——核对清单靠的就是它们。
     * 和 candidates() 的区别有两个：不带 verified 过滤，也不限年级；
     * 读的人是出题的家长本人，孩子端走的永远是 NextQuestionReply 那份不含 correct_index 的消息。
     */
    public List<Question> listForChild(long childId) {
        return group(jdbc.query(SELECT_JOINED + "where b.scope_child_id = ? order by q.id, o.idx",
                QuestionStore::toRawRow, childId));
    }

    /**
     * 这道题落在谁的名下。核对/删除没有 child_id 参数，归属只能从题号反查，
     * 所以这一句就是那两个入口唯一的越权屏障——查不到就当作不存在。
     */
    public Optional<Rows.QuestionOwner> owner(long questionId) {
        var rows = jdbc.query("""
                select q.id, b.scope_child_id, b.owner_parent_id
                from question q join question_bank b on b.id = q.bank_id
                where q.id = ?""",
                (rs, n) -> new Rows.QuestionOwner(rs.getLong("id"),
                        rs.getObject("scope_child_id") == null ? 0L : rs.getLong("scope_child_id"),
                        rs.getObject("owner_parent_id") == null),
                questionId);
        return rows.stream().findFirst();
    }

    private static RawRow toRawRow(ResultSet rs, int n) throws SQLException {
        return new RawRow(
                rs.getLong("id"),
                rs.getString("subject"),
                rs.getInt("grade"),
                rs.getString("knowledge_point"),
                rs.getString("stem"),
                rs.getInt("correct_index"),
                rs.getString("explanation"),
                rs.getBoolean("verified"),
                rs.getObject("idx") == null ? -1 : rs.getInt("idx"),
                rs.getObject("text") == null ? null : rs.getString("text"));
    }

    /** 家长上传题目；返回自增主键，选项行要用它做外键。 */
    public long insert(long bankId, Question question) {
        long id = SqlKeys.insertAndGetId(jdbc,
                "insert into question (bank_id, subject, grade, knowledge_point, stem, correct_index, explanation,"
                        + " verified) values (?, ?, ?, ?, ?, ?, ?, ?)",
                bankId, question.subject().name(), question.grade(), question.knowledgePoint(), question.stem(),
                question.correctIndex(), question.explanation(), question.verified());
        for (int i = 0; i < question.options().size(); i++) {
            jdbc.update("insert into question_option (question_id, idx, text) values (?, ?, ?)",
                    id, i, question.options().get(i));
        }
        return id;
    }

    /** 家长核对通过/驳回。返回 false 表示这道题不存在（或已被删掉）。 */
    public boolean setVerified(long questionId, boolean verified) {
        return jdbc.update("update question set verified = ? where id = ?", verified, questionId) == 1;
    }

    /**
     * 删掉一道题。选项行要先删——schema 里没建外键约束，留着它们就是给后来的读逻辑留一地孤儿行。
     * 不动 question_ask：那是服务端第二本账，删了题也不该把"这台设备被拦过几次"的用量一起抹掉。
     */
    public boolean delete(long questionId) {
        jdbc.update("delete from question_option where question_id = ?", questionId);
        return jdbc.update("delete from question where id = ?", questionId) == 1;
    }

    /** 孩子的私有题库，没有就建一个。 */
    public long childBank(long parentId, long childId, String name) {
        String bankName = name == null || name.isBlank() ? "私有题库" : name;
        var found = jdbc.queryForList("select id from question_bank where scope_child_id = ? and name = ?",
                Long.class, childId, bankName);
        if (!found.isEmpty()) {
            return found.get(0);
        }
        return SqlKeys.insertAndGetId(jdbc,
                "insert into question_bank (owner_parent_id, scope_child_id, name) values (?, ?, ?)",
                parentId, childId, bankName);
    }

    private List<Question> group(List<RawRow> rows) {
        Map<Long, RawRow> byId = new LinkedHashMap<>();
        var options = new LinkedHashMap<Long, List<String>>();
        for (var row : rows) {
            byId.putIfAbsent(row.id(), row);
            if (row.idx() >= 0) {
                options.computeIfAbsent(row.id(), k -> new ArrayList<>()).add(row.text());
            }
        }
        var result = new ArrayList<Question>();
        for (var row : byId.values()) {
            result.add(new Question(row.id(), Subject.valueOf(row.subject()), row.grade(), row.knowledgePoint(),
                    row.stem(), options.getOrDefault(row.id(), List.of()), row.correctIndex(), row.explanation(),
                    row.verified()));
        }
        return result;
    }

    private record RawRow(
            long id,
            String subject,
            int grade,
            String knowledgePoint,
            String stem,
            int correctIndex,
            String explanation,
            boolean verified,
            int idx,
            String text
    ) {
    }
}
