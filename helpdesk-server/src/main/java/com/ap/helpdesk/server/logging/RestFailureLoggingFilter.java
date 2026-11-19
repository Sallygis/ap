package com.ap.helpdesk.server.logging;

import java.io.IOException;
import java.sql.SQLException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/** Logs REST errors while leaving the usual response handling in place. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RestFailureLoggingFilter extends OncePerRequestFilter {
    private static final Logger LOG = LoggerFactory.getLogger(RestFailureLoggingFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException failure) {
            if (NestedExceptionUtils.getMostSpecificCause(failure) instanceof SQLException) {
                LOG.error("Database error during {} {}", request.getMethod(), route(request), failure);
            } else {
                LOG.error("Unhandled REST failure during {} {}", request.getMethod(), route(request), failure);
            }
            throw failure;
        }

        int status = response.getStatus();
        if (status >= 500) {
            LOG.error("REST {} {} returned {}", request.getMethod(), route(request), status);
        } else if (status >= 400 && !request.getRequestURI().equals("/api/notifications/subscription")) {
            LOG.warn("REST {} {} returned {}", request.getMethod(), route(request), status);
        }
    }

    /** Uses a route pattern without logging query strings or form contents. */
    private static String route(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return pattern == null ? request.getRequestURI() : pattern.toString();
    }
}
