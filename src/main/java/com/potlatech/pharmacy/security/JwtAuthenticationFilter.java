package com.potlatech.pharmacy.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.potlatech.pharmacy.entity.User;
import com.potlatech.pharmacy.service.JwtService;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Reads {@code Authorization: Bearer <jwt>} and populates the security context.
 *
 * <p><strong>Does not reject a missing or bad token.</strong> It leaves the
 * request unauthenticated and lets the authorisation rules decide. A filter that
 * 401s on a bad token would also 401 the login and refresh endpoints, which
 * cannot carry one, and it would duplicate the decision the authorisation
 * manager is already set up to make.
 *
 * <p><strong>Does not load the user from the database.</strong> That is the
 * deliberate trade: a stateless token check cannot notice that the account was
 * deactivated a second ago. The window is the token lifetime (15 minutes), which
 * is why it is short, and why refresh — which does hit the database — is the
 * gate that actually enforces revocation. Checking the database here instead
 * would put a query on every request, including static ones.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String BEARER = "Bearer ";

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER)) {
            chain.doFilter(request, response);
            return;
        }

        // A context may already be populated when the filter is reached twice
        // (an ERROR dispatch, or an async dispatch). Overwriting a good
        // authentication with one from a possibly-different header would be a
        // subtle way to lose the original caller.
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                Claims claims = jwtService.parse(header.substring(BEARER.length()).trim());
                authenticate(request, claims);
            } catch (JwtService.InvalidTokenException e) {
                // Not an error response here - see the class Javadoc. Logged at
                // debug because an expired token is routine, not an incident.
                log.debug("Rejected bearer token: {}", e.getMessage());
                SecurityContextHolder.clearContext();
            }
        }

        chain.doFilter(request, response);
    }

    private void authenticate(HttpServletRequest request, Claims claims) {
        UUID userId;
        User.Role role;
        try {
            userId = JwtService.subjectOf(claims);
            role = User.Role.valueOf(claimString(claims, "roles"));
        } catch (IllegalArgumentException e) {
            // A well-signed token with an unusable subject or role. Only
            // possible if the signing key is shared with something else or the
            // claim shape changed under a live token, so treat it as invalid
            // rather than guessing at a default.
            log.debug("Bearer token carried an unusable subject or role claim");
            return;
        }

        AuthenticatedUser principal = new AuthenticatedUser(
                userId,
                claimString(claims, "username"),
                claimString(claims, "fullName"),
                role);

        var authentication = new UsernamePasswordAuthenticationToken(
                principal, null, principal.authorities());
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    /**
     * @return the first element of a single-element list claim, or an empty
     *         string. The {@code roles} claim is written as a list so it can
     *         grow to multiple roles without changing the token shape.
     */
    @SuppressWarnings("unchecked")
    private String claimString(Claims claims, String name) {
        Object value = claims.get(name);
        if (value instanceof List<?> list && !list.isEmpty()) {
            return String.valueOf(list.get(0));
        }
        return value == null ? "" : String.valueOf(value);
    }
}
