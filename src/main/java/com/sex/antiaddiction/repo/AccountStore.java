package com.sex.antiaddiction.repo;

import com.sex.antiaddiction.domain.Rows.Account;
import com.sex.antiaddiction.domain.Rows.AccountWithChildren;
import com.sex.antiaddiction.domain.Rows.Child;
import com.sex.antiaddiction.domain.Rows.Device;
import com.sex.antiaddiction.domain.Rows.Invite;
import com.sex.antiaddiction.domain.Rows.Rule;
import com.sex.antiaddiction.infra.SqlKeys;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AccountStore {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String SELECT_DEVICE =
            "select id, install_id, child_id, device_model, android_api_level, last_heartbeat_at, bind_code,"
                    + " accessibility_connected"
                    + " from device";
    /** 新孩子的默认每日上限与宽限分钟；阈值分钟来自配置，因为它是产品承诺的那个数。 */
    private static final int DEFAULT_DAILY_LIMIT_MINUTES = 120;
    private static final int DEFAULT_GRACE_MINUTES = 2;
    /** 去掉 0/O/1/I 这些容易看错的字符——注册码是要人手敲进表单的。 */
    private static final char[] CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();

    private final JdbcTemplate jdbc;

    public AccountStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Account> accountByUsername(String username) {
        return jdbc.query("select id, username, password_hash, display_name, role from parent_account"
                + " where username = ?", AccountStore::toAccount, username).stream().findFirst();
    }

    public Optional<Account> account(long id) {
        return jdbc.query("select id, username, password_hash, display_name, role from parent_account"
                + " where id = ?", AccountStore::toAccount, id).stream().findFirst();
    }

    public long createAccount(String username, String passwordHash, String displayName, String role) {
        return insertKey("insert into parent_account (username, password_hash, display_name, role)"
                + " values (?, ?, ?, ?)", username, passwordHash, displayName, role);
    }

    public List<Child> childrenOf(long parentId) {
        return jdbc.query("select id, parent_id, display_name, grade from child_profile where parent_id = ?",
                AccountStore::toChild, parentId);
    }

    /** 管理员登录时看全部孩子；家长只看自己的。 */
    public List<Child> allChildren() {
        return jdbc.query("select id, parent_id, display_name, grade from child_profile order by id",
                AccountStore::toChild);
    }

    /** 全量账号清单。孩子数走关联子查询，一条 SQL 拿完，不在循环里逐个子查。 */
    public List<AccountWithChildren> allAccounts() {
        return jdbc.query("select a.id, a.username, a.display_name, a.role,"
                        + " (select count(*) from child_profile c where c.parent_id = a.id) as child_count"
                        + " from parent_account a order by a.id",
                (rs, i) -> new AccountWithChildren(rs.getLong("id"), rs.getString("username"),
                        rs.getString("display_name"), rs.getString("role"), rs.getInt("child_count")));
    }

    public Optional<Child> child(long id) {
        return jdbc.query("select id, parent_id, display_name, grade from child_profile where id = ?",
                AccountStore::toChild, id).stream().findFirst();
    }

    public long createChild(long parentId, String displayName, int grade) {
        return insertKey("insert into child_profile (parent_id, display_name, grade) values (?, ?, ?)",
                parentId, displayName, grade);
    }

    /**
     * 建孩子必须连着落一份默认规则，启动种子和自助注册都走这一条路。
     * 闸门在家长第一次配置之前也得是有阈值的——"没有规则"在产品语义上等于"不设限"，那是 fail-open。
     * 两处各写一份默认值的话，改一处漏一处就是这种事故的形状。
     */
    public long createChildWithDefaultRule(long parentId, String displayName, int grade, int thresholdMinutes) {
        long childId = createChild(parentId, displayName, grade);
        saveRule(new Rule(childId, thresholdMinutes, DEFAULT_DAILY_LIMIT_MINUTES, DEFAULT_GRACE_MINUTES,
                true, System.currentTimeMillis(), List.of()));
        return childId;
    }

    public Optional<Device> deviceByInstallId(String installId) {
        return jdbc.query(SELECT_DEVICE + " where install_id = ?", AccountStore::toDevice, installId)
                .stream().findFirst();
    }

    public Optional<Device> device(long id) {
        return jdbc.query(SELECT_DEVICE + " where id = ?", AccountStore::toDevice, id).stream().findFirst();
    }

    public long createDevice(String installId, String deviceModel, int androidApiLevel, String bindCode) {
        return insertKey("insert into device (install_id, child_id, device_model, android_api_level, bind_code)"
                + " values (?, null, ?, ?, ?)", installId, deviceModel, androidApiLevel, bindCode);
    }

    /** 绑定码只有握着它的家长能认领，一次性，认领后清空。 */
    public Optional<Device> claimByBindCode(String bindCode) {
        return jdbc.query(SELECT_DEVICE + " where bind_code = ?", AccountStore::toDevice, bindCode)
                .stream().findFirst();
    }

    public void attachChild(long deviceId, long childId) {
        jdbc.update("update device set child_id = ?, bind_code = null where id = ?", childId, deviceId);
    }

    public void heartbeat(long deviceId, long nowMs, boolean accessibilityConnected) {
        jdbc.update("update device set last_heartbeat_at = ?, accessibility_connected = ? where id = ?",
                nowMs, accessibilityConnected, deviceId);
    }

    /**
     * 一个孩子先后绑过几台设备时，家长端要看的是"最近真的在跑的那台"：从没心跳过的排到最后。
     * 一次查询带回机型、API 等级和心跳时间，比原先按 count/max 各打一遍少两次往返，
     * 也不会出现"count 说有设备、max 却读到 0"这种两查询之间的错位。
     */
    public Optional<Device> deviceOfChild(long childId) {
        return jdbc.query(SELECT_DEVICE + " where child_id = ?"
                        + " order by coalesce(last_heartbeat_at, 0) desc, id desc limit 1",
                AccountStore::toDevice, childId).stream().findFirst();
    }

    public Optional<Rule> rule(long childId) {
        var head = jdbc.query("select child_id, threshold_minutes, daily_limit_minutes, grace_minutes,"
                        + " enabled, version from rule where child_id = ?",
                (rs, n) -> new Rule(rs.getLong("child_id"), rs.getInt("threshold_minutes"),
                        rs.getInt("daily_limit_minutes"), rs.getInt("grace_minutes"),
                        rs.getBoolean("enabled"), rs.getLong("version"), List.of()), childId);
        if (head.isEmpty()) {
            return Optional.empty();
        }
        var packages = jdbc.queryForList(
                "select package_name from rule_watched_package where child_id = ? order by package_name",
                String.class, childId);
        var first = head.get(0);
        return Optional.of(new Rule(first.childId(), first.thresholdMinutes(), first.dailyLimitMinutes(),
                first.graceMinutes(), first.enabled(), first.version(), packages, gameRule(childId)));
    }

    public void bumpRuleVersion(long childId) {
        jdbc.update("update rule set version=greatest(version+1, ?) where child_id=?", System.currentTimeMillis(), childId);
    }

    private com.quizgate.proto.GameModeRule gameRule(long childId) {
        var config = jdbc.query("select * from game_rule where child_id=?", (rs,n) ->
            com.quizgate.proto.GameModeRule.newBuilder().setEnabled(rs.getBoolean("enabled"))
                .setWarningSeconds(rs.getInt("warning_seconds")).setExtensionMinutes(rs.getInt("extension_minutes"))
                .setMaxExtensions(rs.getInt("max_extensions")).build(), childId).stream().findFirst()
                .orElse(com.quizgate.proto.GameModeRule.newBuilder().setMaxExtensions(2).build());
        var packages = new java.util.TreeSet<String>();
        jdbc.query("select a.package_name,a.system_game,coalesce(o.classification,0) as classification"
            + " from device_app a join device d on d.id=a.device_id join device_app_snapshot s on s.device_id=d.id"
            + " left join game_classification o on o.child_id=d.child_id and o.package_name=a.package_name"
            + " where d.child_id=? and s.consent=true", (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                if (GameStore.isGame(rs.getString("package_name"),rs.getBoolean("system_game"),rs.getInt("classification")))
                    packages.add(rs.getString("package_name"));
            }, childId);
        return config.toBuilder().addAllGamePackages(packages).build();
    }

    @org.springframework.transaction.annotation.Transactional
    public void saveRule(Rule rule) {
        jdbc.update("merge into rule (child_id, threshold_minutes, daily_limit_minutes, grace_minutes,"
                + " enabled, version) key(child_id) values (?, ?, ?, ?, ?, ?)",
                rule.childId(), rule.thresholdMinutes(), rule.dailyLimitMinutes(), rule.graceMinutes(),
                rule.enabled(), rule.version());
        var game = rule.gameMode();
        jdbc.update("merge into game_rule (child_id,enabled,warning_seconds,extension_minutes,max_extensions)"
            + " key(child_id) values (?,?,?,?,?)", rule.childId(), game.getEnabled(), game.getWarningSeconds(),
            game.getExtensionMinutes(), game.getMaxExtensions());
        jdbc.update("delete from rule_watched_package where child_id = ?", rule.childId());
        for (var pkg : rule.watchedPackages()) {
            jdbc.update("insert into rule_watched_package (child_id, package_name) values (?, ?)",
                    rule.childId(), pkg);
        }
    }

    /** 令牌自带 kind 前缀，过滤器一眼能分辨设备令牌和家长令牌。 */
    public String issueToken(String kind, long subjectId) {
        var bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        String token = kind + "-" + HexFormat.of().formatHex(bytes);
        jdbc.update("insert into auth_token (token, kind, subject_id, created_at_ms) values (?, ?, ?, ?)",
                token, kind, subjectId, System.currentTimeMillis());
        return token;
    }

    public Optional<TokenSubject> token(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return jdbc.query("select kind, subject_id from auth_token where token = ?",
                (rs, n) -> new TokenSubject(rs.getString("kind"), rs.getLong("subject_id")), token)
                .stream().findFirst();
    }

    /**
     * 发一个注册码。随机源用 SecureRandom，不是绑定码那套 ThreadLocalRandom：
     * 绑定码错了最多重新绑一次，注册码是"谁能建号"那道闸，可预测就等于没有闸。
     */
    public String createInvite(long createdByAccountId) {
        var sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            sb.append(CODE_ALPHABET[RANDOM.nextInt(CODE_ALPHABET.length)]);
        }
        String code = sb.toString();
        jdbc.update("insert into invite_code (code, created_by, used_by, created_at_ms) values (?, ?, null, ?)",
                code, createdByAccountId, System.currentTimeMillis());
        return code;
    }

    /**
     * 用掉一个注册码，返回被命中的行数。
     * "还没人用过"这个判断写在 where 里而不是先查再改——两个请求同时抢同一个码时，先查再改会双双通过。
     */
    public int consumeInvite(String code, long usedByAccountId) {
        return jdbc.update("update invite_code set used_by = ? where code = ? and used_by is null",
                usedByAccountId, code);
    }

    public List<Invite> allInvites() {
        return jdbc.query("select code, created_by, used_by, created_at_ms from invite_code"
                        + " order by created_at_ms desc, code",
                (rs, n) -> new Invite(rs.getString("code"), rs.getLong("created_by"),
                        rs.getLong("used_by"), rs.getLong("created_at_ms")));
    }

    public void updatePassword(long accountId, String passwordHash) {
        jdbc.update("update parent_account set password_hash = ? where id = ?", passwordHash, accountId);
    }

    /**
     * 作废某个账号名下的人用令牌（家长和管理员）。改密之后旧令牌必须失效，否则"改密码"只是改了个数字。
     * <p>
     * kind 条件不能省：auth_token.subject_id 对设备令牌指的是 device.id，对家长令牌指的是
     * parent_account.id，两张表的 id 空间是重叠的。少这一句就会把同号设备的令牌一起删掉。
     */
    public void revokeHumanTokens(long accountId) {
        jdbc.update("delete from auth_token where subject_id = ? and kind in ('parent', 'admin')", accountId);
    }

    private long insertKey(String sql, Object... args) {
        return SqlKeys.insertAndGetId(jdbc, sql, args);
    }

    private static Account toAccount(ResultSet rs, int n) throws SQLException {
        return new Account(rs.getLong("id"), rs.getString("username"), rs.getString("password_hash"),
                rs.getString("display_name"), rs.getString("role"));
    }

    private static Child toChild(ResultSet rs, int n) throws SQLException {
        return new Child(rs.getLong("id"), rs.getLong("parent_id"), rs.getString("display_name"),
                rs.getInt("grade"));
    }

    private static Device toDevice(ResultSet rs, int n) throws SQLException {
        long childId = rs.getLong("child_id");
        boolean bound = !rs.wasNull();
        String model = rs.getString("device_model");
        // 这一列可空，而且"从没上报过"和"上报了 false"必须区分开，所以读法是先 getBoolean 再 wasNull：
        // 直接拿 getBoolean 会把 NULL 静默读成 false，家长端就把"设备还没跑起来"显示成"孩子关了无障碍"。
        // wasNull 只对最后一次读取有效，所以这一句必须紧跟在 getBoolean 后面，不能挪到方法末尾。
        boolean connected = rs.getBoolean("accessibility_connected");
        Boolean accessibility = rs.wasNull() ? null : connected;
        // 这两列在旧库里是 NULL：getLong/getInt 读成 0，正好是"这台设备还没打过心跳"
        // 和"这台设备没上报过 API 等级"，不需要额外的空值分支。
        // 机型不归一成空串就会带着 null 进 protobuf 的 string 字段，那是一句 NPE。
        return new Device(rs.getLong("id"), rs.getString("install_id"), bound ? childId : 0L,
                model == null ? "" : model, rs.getInt("android_api_level"),
                rs.getLong("last_heartbeat_at"), rs.getString("bind_code"), accessibility);
    }

    public record TokenSubject(String kind, long subjectId) {
    }
}
