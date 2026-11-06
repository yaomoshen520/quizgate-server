package com.sex.antiaddiction.repo;

import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class UsageStore {

    private final JdbcTemplate jdbc;

    public UsageStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * event_id 是客户端生成的幂等键。
     * <p>
     * 撞主键在 JDBC 里是抛 DuplicateKeyException，不是"影响 0 行"，
     * 所以去重必须写在语句里，而不是指望 update 返回 0：一条 insert...select...where not exists
     * 在 H2 和 PostgreSQL 上都是标准写法，重复上报安静地返回 false。
     */
    public boolean insertEvent(String eventId, long childId, long deviceId, long occurredAtMs, String appPackage,
            long playedMs, int correctCount, int wrongCount, boolean interceptionFailed) {
        return jdbc.update("insert into usage_event (event_id, child_id, device_id, occurred_at_ms, app_package,"
                        + " played_ms, correct_count, wrong_count, interception_failed)"
                        + " select ?, ?, ?, ?, ?, ?, ?, ?, ?"
                        + " where not exists (select 1 from usage_event where event_id = ?)",
                eventId, childId, deviceId, occurredAtMs, appPackage, playedMs, correctCount, wrongCount,
                interceptionFailed, eventId) == 1;
    }

    public record Ask(long id, int correctIndex) {
    }

    /** 出题即落账。答案不落账，判定时现查 question 表。 */
    public void openAsk(long childId, long questionId, long askedAtMs) {
        jdbc.update("insert into question_ask (child_id, question_id, asked_at_ms) values (?, ?, ?)",
                childId, questionId, askedAtMs);
    }

    /**
     * 这个孩子手上还没答掉的那道题。判分只认这张条子——
     * 否则接口会被拿成"任选下标、逐个试出正确答案"的预言机。
     */
    public Optional<Ask> pendingAsk(long childId, long questionId) {
        return jdbc.query("select a.id, q.correct_index from question_ask a"
                        + " join question q on q.id = a.question_id"
                        + " where a.child_id = ? and a.question_id = ? and a.correct is null"
                        + " order by a.id desc fetch first 1 rows only",
                (rs, n) -> new Ask(rs.getLong("id"), rs.getInt("correct_index")),
                childId, questionId).stream().findFirst();
    }

    public void resolveAsk(long askId, int selectedIndex, boolean correct) {
        jdbc.update("update question_ask set selected_index = ?, correct = ? where id = ?",
                selectedIndex, correct, askId);
    }

    /**
     * 按天聚合，键是 yyyy-MM-dd，值是 {playedMs, correct, wrong, interceptions, failures}。
     * <p>
     * 日期切分刻意放在 Java 里做：SQL 的日期函数是各家方言差得最多的一块。
     * 时区取调用方传入的时区，家长看到的应当是"家里的那一天"。
     */
    public Map<String, long[]> daily(long childId, long sinceMs, long untilMs, ZoneId zone) {
        Map<String, long[]> byDate = new LinkedHashMap<>();
        jdbc.query("select occurred_at_ms, played_ms, correct_count, wrong_count, interception_failed"
                        + " from usage_event where child_id = ? and occurred_at_ms >= ? and occurred_at_ms < ?",
                rs -> {
                    var totals = byDate.computeIfAbsent(day(rs.getLong("occurred_at_ms"), zone), k -> new long[5]);
                    totals[0] += rs.getLong("played_ms");
                    totals[1] += rs.getInt("correct_count");
                    totals[2] += rs.getInt("wrong_count");
                    if (rs.getBoolean("interception_failed")) {
                        totals[4]++;
                    }
                },
                childId, sinceMs, untilMs);
        jdbc.query("select asked_at_ms from question_ask where child_id = ? and asked_at_ms >= ?"
                        + " and asked_at_ms < ?",
                rs -> {
                    byDate.computeIfAbsent(day(rs.getLong("asked_at_ms"), zone), k -> new long[5])[3]++;
                },
                childId, sinceMs, untilMs);
        return byDate;
    }

    private static String day(long epochMs, ZoneId zone) {
        return Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate().toString();
    }
}
