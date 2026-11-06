package com.sex.antiaddiction.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;

@Configuration
public class SecurityConfig {

    /**
     * app 侧走无状态令牌，浏览器会话一律不建。
     * enroll、login、register 是仅有的三个匿名入口：一个换设备令牌，一个换家长令牌，
     * 一个凭注册码建号。register 匿名是产品要求（家长手上还没有账号），
     * 但闸门在注册码上，而且服务层写死只产出 PARENT 角色。
     */
    @Bean
    @Order(1)
    public SecurityFilterChain appApi(HttpSecurity http, GateAuthFilter tokenFilter) throws Exception {
        return http
                .securityMatcher("/api/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(registry -> registry
                        .requestMatchers("/api/device/enroll", "/api/parent/login", "/api/parent/register")
                        .permitAll()
                        .requestMatchers("/api/device/**").hasRole("DEVICE")
                        // 管理员也走家长这一档：他要用同一套接口替某个孩子配规则、传题库，
                        // 数据隔离由服务层按 child 的归属校验兜住（有测试反证）。
                        .requestMatchers("/api/parent/**").hasAnyRole("PARENT", "ADMIN")
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .anyRequest().denyAll())
                .addFilterBefore(tokenFilter, BasicAuthenticationFilter.class)
                .build();
    }

    /**
     * web 后台的构建产物。只放行这几个具体路径，不用 "/**"：
     * 兜底那条链负责"没点名的路径一律拒绝"，这里一松就把那个不变量废掉了。
     * 前端路由走 hash 模式，所以深链不需要服务端 fallback。
     */
    @Bean
    @Order(2)
    public SecurityFilterChain staticAssets(HttpSecurity http) throws Exception {
        return http
                .securityMatcher("/", "/index.html", "/favicon.ico", "/favicon.svg", "/assets/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(registry -> registry.anyRequest().permitAll())
                .build();
    }

    /**
     * 没有下一条链匹配上的请求全部拒绝：新增接口不会因为忘了挂权限而默认开放。
     * 这里刻意不挂 httpBasic——它在这条链上永远走不到（所有请求先被 denyAll），
     * 留着只会让人以为存在一套 Basic 口令能进门。
     */
    @Bean
    @Order(3)
    public SecurityFilterChain everythingElse(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(registry -> registry.anyRequest().denyAll())
                .csrf(csrf -> csrf.disable())
                .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
