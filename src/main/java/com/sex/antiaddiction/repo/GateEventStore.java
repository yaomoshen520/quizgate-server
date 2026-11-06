package com.sex.antiaddiction.repo;

import com.quizgate.proto.GateEventKind;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 孩子动手拆闸门的那本账。
 * <p>
 * 这一张表只装"设备亲口说的事"。失联不在这里，也不可能在这里——app 被卸载之后没有代码在跑，
 * 报不了自己的死，所以失联是服务端从 device.last_heartbeat_at 反推出来的，两件事在代码里就不该长在一起。
 */
@Repository
public class GateEventStore {

    /** 告警列表一次最多回这么多条。再多对家长没意义，而且会把应答撑大。 */
    private static final int MAX_ALERTS = 200;

    private final JdbcTemplate jdbc;

    public GateEventStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * event_id 是客户端生成的幂等键，去重写在语句里而不是先查再改：
     * 断网攒下的那几条会整批重传，先查再改在并发下会双双通过（和 usage_event 同一个坑、同一个解法）。
     */
    public boolean insert(String eventId, long childId, long deviceId, GateEventKind kind,
            long occurredAtMs, long reportedAtMs, String detail) {
        return jdbc.update("insert into gate_event (event_id, child_id, device_id, kind, occurred_at_ms,"
                        + " reported_at_ms, detail) select ?, ?, ?, ?, ?, ?, ?"
                        + " where not exists (select 1 from gate_event where event_id = ?)",
                eventId, childId, deviceId, kind.name(), occurredAtMs, reportedAtMs, detail, eventId) == 1;
    }

    /**
     * 这些孩子名下的告警，从新到旧。
     * <p>
     * child_ids 是调用方按归属筛好的（家长只看自家），这里不再问一次"你谁"——
     * 归属判断只有 ChildScope 那一份，仓储层重复判断迟早和它漂移。
     */
    public List<Alert> recent(List<Long> childIds) {
        if (childIds.isEmpty()) {
            return List.of();
        }
        var placeholders = new ArrayList<String>(childIds.size());
        for (int i = 0; i < childIds.size(); i++) {
            placeholders.add("?");
        }
        var args = childIds.toArray();
        return jdbc.query("select g.child_id, c.display_name, g.kind, g.occurred_at_ms, g.detail,"
                        + " d.device_model"
                        + " from gate_event g"
                        + " join child_profile c on c.id = g.child_id"
                        + " left join device d on d.id = g.device_id"
                        + " where g.child_id in (" + String.join(", ", placeholders) + ")"
                        + " order by g.occurred_at_ms desc, g.reported_at_ms desc, g.event_id"
                        + " fetch first " + MAX_ALERTS + " rows only",
                GateEventStore::toAlert, args);
    }

    private static Alert toAlert(ResultSet rs, int n) throws SQLException {
        return new Alert(Long.toString(rs.getLong("child_id")), rs.getString("display_name"),
                kindOf(rs.getString("kind")), rs.getLong("occurred_at_ms"), rs.getString("detail"),
                Optional.ofNullable(rs.getString("device_model")).orElse(""));
    }

    /**
     * 读不认识的 kind 退回 UNSPECIFIED，而不是抛异常。
     * 设备端加一个 kind 不需要服务端跟着发版：那种情况下家长至少要看得到"出过一件事"，
     * 而不是整页 500——告警页挂了等于告诉家长"别信这个后台"。
     */
    private static GateEventKind kindOf(String name) {
        try {
            return GateEventKind.valueOf(name);
        } catch (IllegalArgumentException e) {
            return GateEventKind.GATE_EVENT_UNSPECIFIED;
        }
    }

    public record Alert(String childId, String childName, GateEventKind kind, long occurredAtMs,
            String detail, String deviceModel) {
    }
}
