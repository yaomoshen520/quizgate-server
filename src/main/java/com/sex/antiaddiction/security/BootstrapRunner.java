package com.sex.antiaddiction.security;

import com.sex.antiaddiction.repo.AccountStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 账号只能由环境变量喂进来：用户名留空就不建号，仓库里不留任何默认口令。
 * <p>
 * 建孩子时顺手落一份默认规则，闸门在家长第一次配置之前也必须是有阈值的，不能是"不设限"。
 */
@Component
public class BootstrapRunner implements ApplicationRunner {

    private final AccountStore accounts;
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final String adminUsername;
    private final String adminPassword;
    private final String parentUsername;
    private final String parentPassword;
    private final String parentDisplayName;
    private final String childName;
    private final int childGrade;
    private final int thresholdMinutes;

    public BootstrapRunner(AccountStore accounts, JdbcTemplate jdbc, PasswordEncoder encoder,
            @Value("${quizgate.bootstrap.admin-username:}") String adminUsername,
            @Value("${quizgate.bootstrap.admin-password:}") String adminPassword,
            @Value("${quizgate.bootstrap.parent-username:}") String parentUsername,
            @Value("${quizgate.bootstrap.parent-password:}") String parentPassword,
            @Value("${quizgate.bootstrap.parent-display-name:家长}") String parentDisplayName,
            @Value("${quizgate.bootstrap.child-name:孩子}") String childName,
            @Value("${quizgate.bootstrap.child-grade:2}") int childGrade,
            @Value("${quizgate.default-threshold-minutes:30}") int thresholdMinutes) {
        this.accounts = accounts;
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.adminUsername = adminUsername;
        this.adminPassword = adminPassword;
        this.parentUsername = parentUsername;
        this.parentPassword = parentPassword;
        this.parentDisplayName = parentDisplayName;
        this.childName = childName;
        this.childGrade = childGrade;
        this.thresholdMinutes = thresholdMinutes;
    }

    @Override
    public void run(ApplicationArguments args) {
        // 每次启动都要做，和"要不要建号"无关：内置题占用了 1~12 号，自增指针必须落在所有已存在行之后。
        // 放在 data.sql 里做不到这件事——脚本只能写死数值，写死就会在重启后回退去撞已上传的题。
        reseedIdentity("question");
        reseedIdentity("question_bank");
        if (!adminUsername.isBlank() && accounts.accountByUsername(adminUsername).isEmpty()) {
            accounts.createAccount(adminUsername, encoder.encode(adminPassword), "平台管理员", "ADMIN");
        }
        if (parentUsername.isBlank() || accounts.accountByUsername(parentUsername).isPresent()) {
            return;
        }
        long parentId = accounts.createAccount(parentUsername, encoder.encode(parentPassword),
                parentDisplayName, "PARENT");
        // 建孩子走和自助注册同一条路：默认规则由仓储层一起落，两处各写一份迟早会漏一份。
        accounts.createChildWithDefaultRule(parentId, childName, childGrade, thresholdMinutes);
    }

    /**
     * 把自增指针推到当前最大 id 之后。表名来自这里写死的两个字面量，不接受任何外部输入。
     * max 只会被读出来当作数值用，所以指针永远严格大于所有已存在的行——重启多少次都不会回退。
     */
    private void reseedIdentity(String table) {
        Number max = jdbc.queryForObject("select coalesce(max(id), 0) from " + table, Number.class);
        long next = (max == null ? 0L : max.longValue()) + 1L;
        jdbc.execute("alter table " + table + " alter column id restart with " + next);
    }
}
