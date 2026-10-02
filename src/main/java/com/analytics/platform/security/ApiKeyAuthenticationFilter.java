package com.analytics.platform.security;

import com.analytics.platform.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates X-API-Key and binds the tenant to the request thread.
 * The tenant is derived ONLY from the credential, never from the body or query string.
 * TenantContext is cleared in finally so tenants cannot leak between pooled threads.
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-API-Key";

    private final ApiKeyRegistry registry;

    public ApiKeyAuthenticationFilter(ApiKeyRegistry registry) {
        this.registry = registry;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String apiKey = request.getHeader(HEADER);

        if (apiKey != null) {
            Optional<String> tenant = registry.tenantFor(apiKey);
            if (tenant.isEmpty()) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":\"invalid API key\"}");
                return;
            }
            TenantContext.setTenantId(tenant.get());
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(
                            tenant.get(), null, List.of(new SimpleGrantedAuthority("ROLE_TENANT"))));
        }

        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
