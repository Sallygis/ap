package com.ap.helpdesk.server.account;

import java.util.List;
import java.util.Optional;

import com.ap.helpdesk.server.account.UserAccount.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Loads technician choices and checks whether a technician can be assigned. */
public interface TechnicianRepository extends JpaRepository<Technician, Long> {
    /** Keeps the technician assignable until the ticket update commits. */
    @Query(value = """
            select u.full_name from users u join technicians t on t.user_id = u.id
            where u.id = :userId and u.role = 'TECHNICIAN' for update
            """, nativeQuery = true)
    Optional<String> findAssignableForUpdate(@Param("userId") long userId);

    /** Looks up assignees together, including technicians named on past tickets. */
    @Query("""
            select t.userId as userId, a.fullName as fullName
            from Technician t join t.account a
            where t.userId in :userIds
            """)
    List<TechnicianSummary> findAssignedNames(@Param("userIds") List<Long> userIds);

    /** Returns just the ID and name needed for the assignment dialog. */
    @Query("""
            select t.userId as userId, a.fullName as fullName
            from Technician t join t.account a
            where a.role = :role
            order by a.fullName, t.userId
            """)
    List<TechnicianSummary> findDirectory(@Param("role") Role role);

    interface TechnicianSummary {
        long getUserId();

        String getFullName();
    }
}
