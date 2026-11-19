package com.ap.helpdesk.server.account;

import java.util.List;
import java.util.Locale;

import com.ap.helpdesk.server.account.UserAccount.RequesterKind;
import com.ap.helpdesk.server.account.UserAccount.Role;
import com.ap.helpdesk.server.exception.UserNotFoundException;
import com.ap.helpdesk.server.ticket.TicketRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Registers users, provides account details, and manages roles and passwords. */
@Service
public class AccountService implements UserDetailsService {
    private final UserAccountRepository accounts;
    private final PasswordEncoder passwords;
    private final TechnicianRepository technicians;
    private final TicketRepository tickets;

    public AccountService(UserAccountRepository accounts, PasswordEncoder passwords,
            TechnicianRepository technicians, TicketRepository tickets) {
        this.accounts = accounts;
        this.passwords = passwords;
        this.technicians = technicians;
        this.tickets = tickets;
    }

    /** Saves a requester and returns the information the client needs. */
    public Profile register(String fullName, String email, String password, RequesterKind kind) {
        UserAccount account = new UserAccount(fullName.strip(), normalizeEmail(email), passwords.encode(password), kind);
        try {
            return profileOf(accounts.saveAndFlush(account));
        } catch (DataIntegrityViolationException exception) {
            // The database's unique email constraint also covers simultaneous sign-ups.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That email is already registered", exception);
        }
    }

    /** Lets Spring Security check the supplied password against the saved hash. */
    @Override
    public UserDetails loadUserByUsername(String email) {
        UserAccount account = findAccount(email);
        return User.withUsername(account.getEmail())
                .password(account.getPasswordHash())
                .roles(account.getRole().name())
                .build();
    }

    /** Returns safe account details after a successful sign-in. */
    public Profile getProfile(String email) {
        return profileOf(findAccount(email));
    }

    /** Returns accounts for the administrator without exposing credentials. */
    public List<Profile> listUsers() {
        return accounts.findAll(Sort.by("fullName", "id")).stream()
                .map(AccountService::profileOf)
                .toList();
    }

    /** Checks the saved role in case an administrator's session predates a role change. */
    boolean isAdministrator(String email) {
        return accounts.existsByEmailAndRole(email, Role.ADMINISTRATOR);
    }

    /** Saves the role and its Technician entry in the same transaction. */
    @Transactional
    public Profile changeRole(String administrator, long userId, Role role, RequesterKind kind) {
        if ((role == Role.REQUESTER) != (kind != null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose Student or Staff for a requester");
        }

        UserAccount account = lockManagedAccount(administrator, userId).target();

        if (account.getRole() == Role.TECHNICIAN && role != Role.TECHNICIAN
                && !tickets.findActiveTechnicianAssignments(userId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This technician still has active assignments");
        }
        account.changeRole(role, kind);
        // Keep existing Technician entries because past ticket assignments still refer to them.
        if (role == Role.TECHNICIAN && !technicians.existsById(userId)) {
            technicians.save(new Technician(account));
        }
        return profileOf(account);
    }

    /** Changes a signed-in user's password after verifying their current one. */
    @Transactional
    public long changePassword(String email, String currentPassword, String newPassword) {
        UserAccount account = accounts.findByEmailForUpdate(normalizeEmail(email))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in again"));
        if (!passwords.matches(currentPassword, account.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Current password is incorrect");
        }
        account.changePasswordHash(passwords.encode(newPassword));
        return account.getId();
    }

    /** Replaces another user's password immediately under administrator control. */
    @Transactional
    public long resetPassword(String administrator, long userId, String newPassword) {
        ManagedAccount change = lockManagedAccount(administrator, userId);
        change.target().changePasswordHash(passwords.encode(newPassword));
        return change.administratorId();
    }

    private ManagedAccount lockManagedAccount(String administrator, long userId) {
        // Recheck the current role under the same lock as the requested change.
        List<UserAccount> locked = accounts.findAdminAndTargetForUpdate(administrator, userId);
        UserAccount actor = locked.stream().filter(account -> account.getEmail().equals(administrator)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Administrator access is required"));
        if (actor.getRole() != Role.ADMINISTRATOR || actor.getId() == userId) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "An administrator can change only other accounts");
        }
        UserAccount target = locked.stream().filter(user -> user.getId() == userId).findFirst()
                .orElseThrow(UserNotFoundException::new);
        return new ManagedAccount(actor.getId(), target);
    }

    private record ManagedAccount(long administratorId, UserAccount target) {}

    private UserAccount findAccount(String email) {
        return accounts.findByEmail(normalizeEmail(email))
                .orElseThrow(() -> new UsernameNotFoundException("Account not found"));
    }

    private static Profile profileOf(UserAccount account) {
        return new Profile(account.getId(), account.getFullName(), account.getEmail(),
                account.getRole(), account.getRequesterKind());
    }

    private static String normalizeEmail(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    /** Account fields that may be returned to the client. */
    public record Profile(long userId, String fullName, String email, Role role, RequesterKind requesterKind) {}
}
