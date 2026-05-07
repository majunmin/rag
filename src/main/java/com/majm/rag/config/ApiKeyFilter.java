package com.majm.rag.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Slf4j
@Configuration
public class ApiKeyFilter {

    private static final String HEADER = "X-API-Key";
    private static final String GUARDED_PREFIX = "/api/v1/";
    private static final List<String> EXEMPT_PREFIXES = List.of(
        "/v3/api-docs",
        "/swagger-ui",
        "/swagger-resources",
        "/actuator/health"
    );

    @Bean
    FilterRegistrationBean<OncePerRequestFilter> apiKeyFilterRegistration(
        @Value("${app.security.api-key-auth.enabled:false}") boolean enabled,
        @Value("${app.security.api-key-auth.expected-key:}") String expectedKey
    ) {
        FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(buildFilter(enabled, expectedKey));
        registration.addUrlPatterns("/*");
        registration.setOrder(0);
        return registration;
    }

    private OncePerRequestFilter buildFilter(boolean enabled, String expectedKey) {
        if (enabled && (expectedKey == null || expectedKey.isBlank())) {
            throw new IllegalStateException(
                "app.security.api-key-auth.enabled=true but app.security.api-key-auth.expected-key is empty");
        }

        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                            FilterChain chain) throws ServletException, IOException {
                if (!enabled || !isGuarded(request.getRequestURI())) {
                    chain.doFilter(request, response);
                    return;
                }

                String provided = request.getHeader(HEADER);
                if (provided == null || !provided.equals(expectedKey)) {
                    log.debug("Rejecting request to {}: missing or invalid {}", request.getRequestURI(), HEADER);
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"Invalid or missing API key\"}");
                    return;
                }

                chain.doFilter(request, response);
            }
        };
    }

    private boolean isGuarded(String uri) {
        if (!uri.startsWith(GUARDED_PREFIX)) {
            return false;
        }
        for (String prefix : EXEMPT_PREFIXES) {
            if (uri.startsWith(prefix)) {
                return false;
            }
        }
        return true;
    }
}
