package com.sex.antiaddiction.infra;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;

/** 取回自增主键的唯一一处封装，仓储层不再各写一遍。 */
public final class SqlKeys {

    private SqlKeys() {
    }

    public static long insertAndGetId(JdbcTemplate jdbc, String sql, Object... args) {
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement(sql, new String[] {"id"});
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps;
        }, key);
        return key.getKey() == null ? 0L : key.getKey().longValue();
    }
}
