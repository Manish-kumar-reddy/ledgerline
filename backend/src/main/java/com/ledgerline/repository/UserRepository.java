package com.ledgerline.repository;

import com.ledgerline.entity.User;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Note: JpaRepository also exposes save-as-update and delete methods. They cannot succeed: the runtime database
 * role has no UPDATE, DELETE or TRUNCATE privilege on {@code users}.
 */
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);
}
