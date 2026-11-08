package com.sex.antiaddiction.domain;

import com.quizgate.proto.Subject;
import java.util.List;

/** 仓储层返回的行记录。刻意用 record 而不是 Lombok：编译期生成，运行时零额外依赖。 */
public final class Rows {

    private Rows() {
    }

    /**
     * verified 为 false 表示"这道题的答案还没人担保过"：闸门候选池必须把它排除在外。
     * 拍照识别出来的草稿默认落 false，只有服务端算得出来的算术题、或家长点过核对的才升 true。
     */
    public record Question(
            long id,
            Subject subject,
            int grade,
            String knowledgePoint,
            String stem,
            List<String> options,
            int correctIndex,
            String explanation,
            boolean verified
    ) {
    }

    /**
     * 一道题的归属。builtIn 为 true 表示平台内置题库里的题——家长既不能改也不能删，
     * 所以核对/删除这两个入口要先看这一列再决定放不放行。
     */
    public record QuestionOwner(long questionId, long childId, boolean builtIn) {
    }

    public record Rule(
            long childId,
            int thresholdMinutes,
            int dailyLimitMinutes,
            int graceMinutes,
            boolean enabled,
            long version,
            List<String> watchedPackages,
            com.quizgate.proto.GameModeRule gameMode
    ) {
        public Rule(long childId, int thresholdMinutes, int dailyLimitMinutes, int graceMinutes,
                boolean enabled, long version, List<String> watchedPackages) {
            this(childId, thresholdMinutes, dailyLimitMinutes, graceMinutes, enabled, version, watchedPackages,
                    com.quizgate.proto.GameModeRule.newBuilder().setMaxExtensions(2).build());
        }
    }

    /**
     * lastHeartbeatMs 为 0 表示这台设备登记过但一次心跳都没打过——和设备"不存在"是两回事，
     * 家长端要靠它区分"闸门还没跑起来"和"闸门掉了"。
     * <p>
     * accessibilityConnected 用包装类型而不是 boolean：库里那一列可空，而"从没上报过"和
     * "上报了说是关着的"在家长眼里是两件事，压成 false 会把前者谎报成后者。
     */
    public record Device(
            long id,
            String installId,
            long childId,
            String deviceModel,
            int androidApiLevel,
            long lastHeartbeatMs,
            String bindCode,
            Boolean accessibilityConnected
    ) {
    }

    public record Child(long id, long parentId, String displayName, int grade) {
    }

    public record Account(long id, String username, String passwordHash, String displayName, String role) {
    }

    /** 管理员列表用的账号视图：不带口令散列，多带一个孩子数。 */
    public record AccountWithChildren(long id, String username, String displayName, String role, int childCount) {
    }

    /** 注册码台账。usedByAccountId 为 0 表示还没被消耗（库里存的是 NULL）。 */
    public record Invite(String code, long createdByAccountId, long usedByAccountId, long createdAtMs) {
    }
}
