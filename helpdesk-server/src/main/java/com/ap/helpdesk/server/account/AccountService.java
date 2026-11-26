package com.ap.helpdesk.server.account;

import java.util.List;
import java.util.Locale;

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
import org.springframework.beans.factory.annotation.Value;

/** Registers users, provides account details, and manages roles and passwords. */
@Service
public class AccountService implements UserDetailsService {
    private final UserAccountRepository accounts;
    private final PasswordEncoder passwords;
    private final TechnicianRepository technicians;
    private final TicketRepository tickets;
    private final String adminSetupCode;
    private final String technicianSetupCode;

    public AccountService(UserAccountRepository accounts, PasswordEncoder passwords,
            TechnicianRepository technicians, TicketRepository tickets,
            @Value("${HELPDESK_ADMIN_SETUP_CODE:}") String adminSetupCode,
            @Value("${HELPDESK_TECHNICIAN_SETUP_CODE:}") String technicianSetupCode) {
        this.accounts = accounts;
        this.passwords = passwords;
        this.technicians = technicians;
        this.tickets = tickets;
        this.adminSetupCode = adminSetupCode;
        this.technicianSetupCode = technicianSetupCode;
    }

    /** Saves a USER and returns the information the client needs. */
    @Transactional
    public Profile register(String fullName, String email, String password,
            Role role, String setupCode) {

        if (role == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Select an account role");
        }

        if (role == Role.TECHNICIAN || role == Role.ADMINISTRATOR) {
            String expectedCode = role == Role.TECHNICIAN
                    ? technicianSetupCode
                    : adminSetupCode;

            if (expectedCode.isBlank() || !expectedCode.equals(setupCode)) {
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN, "Invalid setup code for this role");
            }
        }

        UserAccount account = new UserAccount(
                fullName.strip(),
                normalizeEmail(email),
                passwords.encode(password),
                role
        );

        try {
            accounts.saveAndFlush(account);
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "That email is already registered", exception);
        }

        if (role == Role.TECHNICIAN) {
            technicians.save(new Technician(account));
        }

        return profileOf(account);
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
    public Profile changeRole(String administrator, long userId, Role role) {
        if (role == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Select an account role");
        }

        UserAccount account = lockManagedAccount(administrator, userId).target();

        if (account.getRole() == Role.TECHNICIAN
                && role != Role.TECHNICIAN
                && !tickets.findActiveTechnicianAssignments(userId).isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "This technician still has active assignments");
        }

        account.changeRole(role);

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

    @Transactional
    public void recoverPassword(String fullName, String email, String newPassword) {
        UserAccount account = accounts.findByEmailForUpdate(normalizeEmail(email))
                .filter(user -> user.getFullName().equalsIgnoreCase(fullName.strip()))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "The full name and email do not match an account"
                ));

        account.changePasswordHash(passwords.encode(newPassword));
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
    		return new Profile(account.getId(), account.getFullName(),
    	        account.getEmail(), account.getRole());
    }

    private static String normalizeEmail(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    /** Account fields that may be returned to the client. */
    public record Profile(long userId, String fullName, String email, Role role) {}
}
