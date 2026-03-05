package com.datavalley.notification.repository;

import com.datavalley.notification.model.AuthSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AuthSessionRepository extends JpaRepository<AuthSession, Long> {
    Optional<AuthSession> findByToken(String token);
    void deleteByToken(String token);
    void deleteByUser_Id(Long userId);
}
