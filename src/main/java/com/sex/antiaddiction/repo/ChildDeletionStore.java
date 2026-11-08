package com.sex.antiaddiction.repo;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 删掉一个孩子档案的全部连坐范围，集中在这一个文件里。
 * <p>
 * 刻意不按表族拆到各仓储类：这是一次不可撤销的删除，评审的人要能在一屏里数清它动了哪些表。
 * 库里没有外键（schema.sql 全裸），所以每一张按 child_id 存的表都得自己被人删一遍——
 * 漏一张不会报错，只会留下谁也读不到、谁也认不回来的孤行。
 * <p>
 * 口径是"档案没了，这个孩子名下的数据就没了"：
 * 用量流水、出题台账、闸门事件都按 child_id 查，档案一删家长侧再也读不到它们，
 * 留在库里只是替一个不存在的人保管行为记录。（删题目时不动 question_ask 那条规矩在这里不适用：
 * 那时题没了、账还有意义；这里是记账的主体本身被删。）
 * <p>
 * 正常路径走不到"删设备"这一支：名下还挂着设备时 ParentService 直接拒绝删档，要家长先逐台解绑。
 * 这里仍然把 device 行连同令牌一起清掉，是因为"查有没有设备"和"真删"之间有一次竞争——
 * 那一秒被人认领进来一台，宁可连着它一起清干净，也不能留下一行指向已消失孩子的孤行。
 * 解绑本身为什么必须重发绑定码（认领时旧码被清成 null，设备只有 enroll 那一次能拿到码），
 * 答案写在 AccountStore.detachDevice 上。
 */
@Repository
public class ChildDeletionStore {

    /** 删掉的行数按类回报，界面上要能把这几笔账念给家长听。 */
    public record Removed(int devices, int usageEvents, int gateEvents, int questions) {
    }

    private final JdbcTemplate jdbc;

    public ChildDeletionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Removed remove(long childId) {
        // 设备的 id 先取出来：下面几张表都按 device_id 存，而 device 行本身要到最后一步才删。
        List<Long> deviceIds = jdbc.queryForList("select id from device where child_id = ?", Long.class, childId);
        int questions = deleteQuestions(childId);
        int usageEvents = jdbc.update("delete from usage_event where child_id = ?", childId);
        jdbc.update("delete from question_ask where child_id = ?", childId);
        int gateEvents = jdbc.update("delete from gate_event where child_id = ?", childId);
        jdbc.update("delete from rule_watched_package where child_id = ?", childId);
        jdbc.update("delete from rule where child_id = ?", childId);
        jdbc.update("delete from game_rule where child_id = ?", childId);
        jdbc.update("delete from game_classification where child_id = ?", childId);
        int devices = deleteDevices(deviceIds);
        jdbc.update("delete from device where child_id = ?", childId);
        jdbc.update("delete from child_profile where id = ?", childId);
        return new Removed(devices, usageEvents, gateEvents, questions);
    }

    private int deleteDevices(List<Long> deviceIds) {
        for (var deviceId : deviceIds) {
            jdbc.update("delete from device_app where device_id = ?", deviceId);
            jdbc.update("delete from device_app_snapshot where device_id = ?", deviceId);
            jdbc.update("delete from issued_game_policy where device_id = ?", deviceId);
            jdbc.update("delete from game_extension_request where device_id = ?", deviceId);
            // kind 条件不能省：subject_id 这一列对设备令牌存的是 device.id，对家长令牌存的是账号 id，
            // 两个 id 空间是重叠的，少这一句就把同号家长的登录状态一起删了。
            jdbc.update("delete from auth_token where kind = 'device' and subject_id = ?", deviceId);
        }
        return deviceIds.size();
    }

    /** 私有题库连着题目和选项一起走：家长手打的和拍照识别的都算这个孩子的。 */
    private int deleteQuestions(long childId) {
        jdbc.update("delete from question_option where question_id in"
                        + " (select q.id from question q join question_bank b on b.id = q.bank_id"
                        + " where b.scope_child_id = ?)", childId);
        int questions = jdbc.update("delete from question where bank_id in"
                + " (select id from question_bank where scope_child_id = ?)", childId);
        jdbc.update("delete from question_bank where scope_child_id = ?", childId);
        return questions;
    }
}
