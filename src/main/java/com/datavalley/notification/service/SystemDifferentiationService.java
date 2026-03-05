package com.datavalley.notification.service;

import com.datavalley.notification.model.Notification;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class SystemDifferentiationService {

    private static final int MAX_RECENT_EVENTS = 20;

    private final AtomicLong totalProcessed = new AtomicLong();
    private final AtomicLong queueProcessed = new AtomicLong();
    private final AtomicLong directModeProcessed = new AtomicLong();
    private final AtomicLong directFallbackProcessed = new AtomicLong();

    private final AtomicLong queueDelayMsTotal = new AtomicLong();
    private final AtomicLong queueDelaySamples = new AtomicLong();
    private final AtomicLong directDelayMsTotal = new AtomicLong();
    private final AtomicLong directDelaySamples = new AtomicLong();

    private final Object recentEventsLock = new Object();
    private final Deque<Map<String, Object>> recentEvents = new ArrayDeque<>();

    public void record(Notification notification, DeliveryPath path) {
        long delayMs = computeDelayMs(notification);
        totalProcessed.incrementAndGet();

        switch (path) {
            case QUEUE_CONSUMER:
                queueProcessed.incrementAndGet();
                queueDelayMsTotal.addAndGet(delayMs);
                queueDelaySamples.incrementAndGet();
                break;
            case DIRECT_MODE:
                directModeProcessed.incrementAndGet();
                directDelayMsTotal.addAndGet(delayMs);
                directDelaySamples.incrementAndGet();
                break;
            case DIRECT_FALLBACK:
                directFallbackProcessed.incrementAndGet();
                directDelayMsTotal.addAndGet(delayMs);
                directDelaySamples.incrementAndGet();
                break;
            default:
                break;
        }

        addRecentEvent(notification, path, delayMs);
    }

    public Map<String, Object> snapshot() {
        long queueCount = queueProcessed.get();
        long directModeCount = directModeProcessed.get();
        long fallbackCount = directFallbackProcessed.get();
        long impactedInPresentSystem = directModeCount + fallbackCount;

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("totalProcessed", totalProcessed.get());
        metrics.put("queueProcessed", queueCount);
        metrics.put("directModeProcessed", directModeCount);
        metrics.put("fallbackProcessed", fallbackCount);
        metrics.put("presentSystemImpacted", impactedInPresentSystem);
        metrics.put("proposedSystemSaved", impactedInPresentSystem);
        metrics.put("avgQueueDelayMs", average(queueDelayMsTotal.get(), queueDelaySamples.get()));
        metrics.put("avgDirectDelayMs", average(directDelayMsTotal.get(), directDelaySamples.get()));
        metrics.put("recentEvents", copyRecentEvents());
        return metrics;
    }

    private long computeDelayMs(Notification notification) {
        if (notification.getDeliveryLatencyMs() != null) {
            return Math.max(notification.getDeliveryLatencyMs(), 0L);
        }

        if (notification.getTimestamp() == null) {
            return 0;
        }

        LocalDateTime reference = notification.getProcessedAt() != null ? notification.getProcessedAt() : LocalDateTime.now();
        long delay = Duration.between(notification.getTimestamp(), reference).toMillis();
        return Math.max(delay, 0);
    }

    private long average(long total, long samples) {
        if (samples <= 0) {
            return 0;
        }
        return total / samples;
    }

    private void addRecentEvent(Notification notification, DeliveryPath path, long delayMs) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("notificationId", notification.getId());
        event.put("priority", notification.getPriority() != null ? notification.getPriority().name() : "UNKNOWN");
        event.put("target", notification.getRecipient() != null ? "@" + notification.getRecipient().getUsername() : "BROADCAST");
        event.put("path", path.name());
        event.put("delayMs", delayMs);
        event.put("processedAt", (notification.getProcessedAt() != null ? notification.getProcessedAt() : LocalDateTime.now()).toString());

        synchronized (recentEventsLock) {
            recentEvents.addFirst(event);
            while (recentEvents.size() > MAX_RECENT_EVENTS) {
                recentEvents.removeLast();
            }
        }
    }

    private List<Map<String, Object>> copyRecentEvents() {
        synchronized (recentEventsLock) {
            return new ArrayList<>(recentEvents);
        }
    }
}
