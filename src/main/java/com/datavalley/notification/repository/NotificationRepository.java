package com.datavalley.notification.repository;

import com.datavalley.notification.model.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {
    List<Notification> findByStatusOrderByTimestampDesc(Notification.NotificationStatus status);
    List<Notification> findByRecipientIdAndStatus(Long userId, Notification.NotificationStatus status);
    List<Notification> findByRecipientIsNullAndStatus(Notification.NotificationStatus status);
    List<Notification> findByRecipientId(Long userId);
    List<Notification> findByRecipientIsNull(); // Broadcasts
    List<Notification> findByProcessedAtAfterAndStatusOrderByProcessedAtAsc(LocalDateTime processedAt, Notification.NotificationStatus status);
    List<Notification> findByProcessedAtAfterOrderByProcessedAtAsc(LocalDateTime processedAt);
}
