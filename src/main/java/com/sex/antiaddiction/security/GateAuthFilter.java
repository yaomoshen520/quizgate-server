package com.sex.antiaddiction.security;

import com.sex.antiaddiction.repo.AccountStore;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 设备令牌与家长令牌走同一个头，靠令牌前缀区分身份，再要求身份与路径匹配。
 * <p>
 * 这一步不是形式主义：设备令牌拿到 parent 身份就能改自己孩子的阈值，
 * 家长令牌拿到 device 身份就能替孩子答题。两类令牌互不通用，路径也必须对得上。
 */
@Component
public class GateAuthFilter extends OncePerRequestFilter {

    private final AccountStore accounts;

    public GateAuthFilter(AccountStore accounts) {
        this.accounts = accounts;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = request.getHeader(AppPrincipal.HEADER);
        var subject = accounts.token(token);
        if (subject.isPresent() && allowed(request.getRequestURI(), subject.get().kind())) {
            var principal = new AppPrincipal(subject.get().kind(), subject.get().subjectId());
            var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + principal.kind().toUpperCase()));
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(principal, null, authorities));
        }
        chain.doFilter(request, response);
    }

    /**
     * 令牌类型必须和路径档位对得上，对不上就干脆不挂身份——后面的链会把它当匿名请求拒掉。
     * <p>
     * admin 可以走家长接口：管理员本来就要替家长配阈值、传题库，跨孩子的豁免在服务层做。
     * 反过来不行：家长令牌既进不了 /api/admin/**，也拿不到 ROLE_ADMIN。
     */
    private static boolean allowed(String uri, String kind) {
        if (uri.startsWith("/api/device/")) {
            return "device".equals(kind);
        }
        if (uri.startsWith("/api/admin/")) {
            return "admin".equals(kind);
        }
        return !uri.startsWith("/api/parent/") || "parent".equals(kind) || "admin".equals(kind);
    }
}
