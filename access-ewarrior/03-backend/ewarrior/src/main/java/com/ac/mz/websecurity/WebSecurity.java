package com.ac.mz.websecurity;

import com.ac.mz.dao.Daos;
import com.ac.mz.utility.Utilities;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Web security: who the caller is, and which routes need a caller at all. */
public final class WebSecurity {

    private WebSecurity() {}

    /**
     * The authenticated caller, placed in the SecurityContext by the filter below.
     * Two admin flags: productAdmin can approve products and grant admin rights;
     * collaboratorAdmin can manage accounts but not approve a product.
     */
    public record CurrentUser(
            Long collaboratorId,
            String name,
            String email,
            boolean productAdmin,
            boolean collaboratorAdmin,
            String jti
    ) {
        public boolean canManageCollaborators() { return productAdmin || collaboratorAdmin; }
    }

    /**
     * Runs once per request before any controller. Any failure leaves the request
     * anonymous and Spring Security rejects it — the filter never writes a response itself.
     */
    @Component
    public static class JwtAuthenticationFilter extends OncePerRequestFilter {

        private final Utilities.JwtUtil jwt;
        private final Daos.TokenDao tokens;

        public JwtAuthenticationFilter(Utilities.JwtUtil jwt, Daos.TokenDao tokens) {
            this.jwt = jwt;
            this.tokens = tokens;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {

            String header = request.getHeader("Authorization");
            if (header != null && header.startsWith("Bearer ")) {
                try {
                    Claims claims = jwt.parse(header.substring(7));
                    String jti = claims.getId();

                    if (!tokens.isRevoked(jti)) {
                        boolean productAdmin = Boolean.TRUE.equals(claims.get("isProductAdmin", Boolean.class));
                        boolean collabAdmin  = Boolean.TRUE.equals(claims.get("isCollaboratorAdmin", Boolean.class));

                        CurrentUser user = new CurrentUser(
                                Long.valueOf(claims.getSubject()),
                                claims.get("name", String.class),
                                claims.get("email", String.class),
                                productAdmin, collabAdmin, jti);

                        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
                        authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
                        if (productAdmin) authorities.add(new SimpleGrantedAuthority("ROLE_PRODUCT_ADMIN"));
                        if (collabAdmin)  authorities.add(new SimpleGrantedAuthority("ROLE_COLLABORATOR_ADMIN"));

                        SecurityContextHolder.getContext().setAuthentication(
                                new UsernamePasswordAuthenticationToken(user, null, authorities));
                    }
                } catch (Exception ignored) {
                    // Bad signature, expired, malformed: stay anonymous.
                }
            }
            chain.doFilter(request, response);
        }
    }

    @Configuration
    public static class SecurityConfig {

        private final JwtAuthenticationFilter jwtFilter;

        public SecurityConfig(JwtAuthenticationFilter jwtFilter) { this.jwtFilter = jwtFilter; }

        @Bean
        public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
            http
                .csrf(csrf -> csrf.disable())          // stateless JWT API, no cookies
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                    .requestMatchers("/api/auth/login").permitAll()
                    .requestMatchers("/c/**").permitAll()   // public card page, reachable externally
                    .requestMatchers("/actuator/health").permitAll()
                    .anyRequest().authenticated())
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

            return http.build();
        }
    }
}
