package com.ap.helpdesk.server.controller;

import java.security.Principal;
import java.util.List;

import com.ap.helpdesk.server.account.AccountService;
import com.ap.helpdesk.server.account.AccountService.Profile;
import com.ap.helpdesk.server.account.UserAccount.RequesterKind;
import com.ap.helpdesk.server.account.UserAccount.Role;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Handles sign-in, registration, and administrator account management. */
@RestController
@RequestMapping("/api/users")
public class UserController {
    private static final Logger LOG = LoggerFactory.getLogger(UserController.class);
    private final AccountService accounts;
    private final AuthenticationManager authentication;
    private final SecurityContextRepository sessions;

    public UserController(AccountService accounts, AuthenticationManager authentication,
            SecurityContextRepository sessions) {
        this.accounts = accounts;
        this.authentication = authentication;
        this.sessions = sessions;
    }

    /** Gives the client the token it needs to submit a form with its session cookie. */
    @GetMapping("/csrf")
    public CsrfToken csrfToken(CsrfToken token) {
        return token;
    }

    /** Saves a student or staff requester and signs them in. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Profile register(@Valid @RequestBody RegisterRequest account,
            HttpServletRequest request, HttpServletResponse response) {
        Profile profile = accounts.register(account.fullName(), account.email(), account.password(),
                account.requesterKind());
        LOG.info("Account created for user {} ({})", profile.userId(), profile.requesterKind());
        return startSession(profile, request, response);
    }

    /** Verifies the credentials and returns the signed-in account profile. */
    @PostMapping("/login")
    public Profile login(@Valid @RequestBody LoginRequest credentials,
            HttpServletRequest request, HttpServletResponse response) {
        try {
            var token = UsernamePasswordAuthenticationToken.unauthenticated(credentials.email(), credentials.password());
            var verified = authentication.authenticate(token);
            return startSession(accounts.getProfile(verified.getName()), request, response);
        } catch (AuthenticationException exception) {
            LOG.warn("Invalid login attempt");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password");
        }
    }

    @PostMapping("/recover-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void recoverPassword(@Valid @RequestBody RecoveryRequest change) {
        accounts.recoverPassword(change.fullName(), change.email(), change.newPassword());
    }

    public record RecoveryRequest(
            @NotBlank @Size(max = 120) String fullName,
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 8, max = 256) String newPassword) {}

    /** Lists accounts for administrator use. */
    @GetMapping
    public List<Profile> listUsers() {
        return accounts.listUsers();
    }

    /** Updates another user's role from the administrator's account editor. */
    @PatchMapping("/{id}/role")
    public Profile changeRole(@PathVariable long id, @Valid @RequestBody RoleRequest change, Principal administrator) {
        return accounts.changeRole(administrator.getName(), id, change.role(), change.requesterKind());
    }

    /** Changes the authenticated user's own password. */
    @PatchMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@Valid @RequestBody ChangePasswordRequest change, Principal user) {
        long userId = accounts.changePassword(user.getName(), change.currentPassword(), change.newPassword());
        LOG.info("User {} changed their password", userId);
    }

    /** Resets the selected user's password from Manage accounts. */
    @PatchMapping("/{id}/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(@PathVariable("id") long id,
            @Valid @RequestBody ResetPasswordRequest change, Principal administrator) {
        long administratorId = accounts.resetPassword(administrator.getName(), id, change.newPassword());
        LOG.info("Administrator {} reset the password for user {}", administratorId, id);
    }

    /** Saves the verified email and role in the server-side session. */
    private Profile startSession(Profile profile, HttpServletRequest request, HttpServletResponse response) {
        // Rotate the pre-login session ID when the caller becomes authenticated.
        request.getSession(true);
        request.changeSessionId();

        var role = new SimpleGrantedAuthority("ROLE_" + profile.role().name());
        var context = SecurityContextHolder.createEmptyContext();
        var signedIn = UsernamePasswordAuthenticationToken.authenticated(profile.email(), null, List.of(role));
        signedIn.setDetails(profile.userId());
        context.setAuthentication(signedIn);
        SecurityContextHolder.setContext(context);
        sessions.saveContext(context, request, response);
        LOG.info("User {} signed in as {}", profile.userId(), profile.role());
        return profile;
    }

    public record RegisterRequest(
            @NotBlank @Size(max = 120) String fullName,
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 8, max = 256) String password,
            @NotNull RequesterKind requesterKind) {}

    public record RoleRequest(@NotNull Role role, RequesterKind requesterKind) {}

    public record ChangePasswordRequest(
            @NotBlank @Size(max = 256) String currentPassword,
            @NotBlank @Size(min = 8, max = 256) String newPassword) {}

    public record ResetPasswordRequest(@NotBlank @Size(min = 8, max = 256) String newPassword) {}

    public record LoginRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 256) String password) {}
}
