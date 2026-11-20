package com.ap.helpdesk.server.account;

import jakarta.servlet.DispatcherType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

/** Sets up password verification, session cookies, and API access rules. */
@Configuration
public class SecurityConfig {
    private static final Logger LOG = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    PasswordEncoder passwordEncoder() {
        return Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    @Bean
    AuthenticationManager authenticationManager(AccountService accounts, PasswordEncoder passwords) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(accounts);
        provider.setPasswordEncoder(passwords);
        return new ProviderManager(provider);
    }

    /** Stores Spring Security's authentication details in the HTTP session. */
    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityContextRepository contexts,
            AccountService accounts) throws Exception {
        // A signed-in administrator may have had their role changed since signing in.
        AuthorizationManager<RequestAuthorizationContext> administrator = (authentication, request) -> {
            var user = authentication.get();
            boolean hasAdminSession = user != null && user.getAuthorities().stream()
                    .anyMatch(authority -> authority.getAuthority().equals("ROLE_ADMINISTRATOR"));
            return new AuthorizationDecision(hasAdminSession && accounts.isAdministrator(user.getName()));
        };

        return http
                .securityContext(security -> security.securityContextRepository(contexts))
                .requestCache(cache -> cache.disable())
                .exceptionHandling(errors -> errors.authenticationEntryPoint(
                        (request, response, failure) -> response.setStatus(HttpStatus.UNAUTHORIZED.value())))
                .authorizeHttpRequests(routes -> routes
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/users/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/users", "/api/users/login").permitAll()
                        
                        .requestMatchers(HttpMethod.POST, "/api/users/recover-password")
                        .access((authentication, context) -> {
                            String address = context.getRequest().getRemoteAddr();
                            boolean local = "127.0.0.1".equals(address)
                                    || "::1".equals(address)
                                    || "0:0:0:0:0:0:0:1".equals(address);
                            return new AuthorizationDecision(local);
                        })
                        .requestMatchers(HttpMethod.GET, "/api/users", "/api/technicians").access(administrator)
                        .requestMatchers(HttpMethod.PATCH, "/api/users/*/role", "/api/users/*/password").access(administrator)
                        .requestMatchers("/api/reports/**", "/api/tickets/*/assignment").access(administrator)
                        .anyRequest().authenticated())
                .logout(logout -> logout.logoutUrl("/api/users/logout")
                        .deleteCookies("JSESSIONID")
                        .logoutSuccessHandler((request, response, authentication) -> {
                            if (authentication != null && authentication.getDetails() instanceof Long userId) {
                                LOG.info("User {} signed out", userId);
                            }
                            response.setStatus(HttpStatus.NO_CONTENT.value());
                        }))
                .build();
    }
}
