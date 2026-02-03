package com.datavalley.notification.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "notifications")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String message;

    @Enumerated(EnumType.STRING)
    private Priority priority;

    @ManyToOne
    @JoinColumn(name = "user_id")
    private User recipient;

    @Enumerated(EnumType.STRING)
    private NotificationStatus status;

    private LocalDateTime timestamp;

    public enum Priority {
        CRITICAL,
        WARNING,
        NORMAL,
        INFO
    }

    public enum NotificationStatus {
        PENDING,
        SENT,
        FAILED
    }
}
