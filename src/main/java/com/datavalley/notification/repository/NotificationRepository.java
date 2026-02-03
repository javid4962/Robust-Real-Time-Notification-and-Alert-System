package com.datavalley.notification.repository;

import com.datavalley.notification.model.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {
    List<Notification> findByRecipientId(Long userId);
    List<Notification> findByRecipientIsNull(); // Broadcasts
}
