package com.ap.helpdesk.server.account;

import java.util.List;
import java.util.Optional;

import com.ap.helpdesk.server.account.UserAccount.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Looks up accounts and locks rows while an administrator changes roles. */
public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {
    Optional<UserAccount> findByEmail(String email);

    boolean existsByEmailAndRole(String email, Role role);

    /** Locks the saved hash until the password check and change are complete. */
    @Query(value = "select * from users where email = :email for update", nativeQuery = true)
    Optional<UserAccount> findByEmailForUpdate(@Param("email") String email);

    /** Locks the administrator and the selected user during an account change. */
    @Query(value = """
            select * from users
            where email = :administrator or id = :userId
            order by id for update
            """, nativeQuery = true)
    List<UserAccount> findAdminAndTargetForUpdate(@Param("administrator") String administrator,
            @Param("userId") long userId);
}
