package com.datavalley.notification.service;

import com.datavalley.notification.model.Notification;
import com.datavalley.notification.repository.NotificationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class NotificationAnalyticsService {

    private static final DateTimeFormatter LABEL_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    @Autowired
    private NotificationRepository notificationRepository;

    public Map<String, Object> getDeliveryHistory(int minutes, int bucketMinutes) {
        int safeMinutes = Math.max(15, Math.min(minutes, 24 * 60));
        int safeBucketMinutes = Math.max(1, Math.min(bucketMinutes, 60));

        LocalDateTime now = LocalDateTime.now().withSecond(0).withNano(0);
        LocalDateTime start = now.minusMinutes(safeMinutes);
        start = alignToBucket(start, safeBucketMinutes);

        List<Notification> notifications = notificationRepository.findByProcessedAtAfterOrderByProcessedAtAsc(start);

        List<LocalDateTime> buckets = buildBuckets(start, now, safeBucketMinutes);
        Map<LocalDateTime, Bucket> bucketMap = new LinkedHashMap<>();
        for (LocalDateTime bucketStart : buckets) {
            bucketMap.put(bucketStart, new Bucket());
        }

        notifications.forEach(notification -> {
            if (notification.getProcessedAt() == null) {
                return;
            }

            LocalDateTime bucketStart = alignToBucket(notification.getProcessedAt(), safeBucketMinutes);
            Bucket bucket = bucketMap.get(bucketStart);
            if (bucket == null) {
                return;
            }

            String path = notification.getDeliveryPath();
            if ("QUEUE_CONSUMER".equals(path)) {
                bucket.queueCount++;
            } else if ("DIRECT_MODE".equals(path)) {
                bucket.directCount++;
            } else if ("DIRECT_FALLBACK".equals(path)) {
                bucket.fallbackCount++;
            }

            if (notification.getDeliveryLatencyMs() != null) {
                bucket.latencyTotal += notification.getDeliveryLatencyMs();
                bucket.latencySamples++;
            }
        });

        List<String> labels = new ArrayList<>();
        List<Long> queueSeries = new ArrayList<>();
        List<Long> directSeries = new ArrayList<>();
        List<Long> fallbackSeries = new ArrayList<>();
        List<Long> latencySeries = new ArrayList<>();

        long totalQueue = 0;
        long totalDirect = 0;
        long totalFallback = 0;

        for (LocalDateTime bucketStart : buckets) {
            Bucket bucket = bucketMap.get(bucketStart);
            labels.add(bucketStart.format(LABEL_FORMAT));
            queueSeries.add(bucket.queueCount);
            directSeries.add(bucket.directCount);
            fallbackSeries.add(bucket.fallbackCount);
            latencySeries.add(bucket.latencySamples == 0 ? 0 : (bucket.latencyTotal / bucket.latencySamples));
            totalQueue += bucket.queueCount;
            totalDirect += bucket.directCount;
            totalFallback += bucket.fallbackCount;
        }

        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("queue", totalQueue);
        totals.put("direct", totalDirect);
        totals.put("fallback", totalFallback);
        totals.put("all", totalQueue + totalDirect + totalFallback);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rangeMinutes", safeMinutes);
        result.put("bucketMinutes", safeBucketMinutes);
        result.put("labels", labels);
        result.put("queueSeries", queueSeries);
        result.put("directSeries", directSeries);
        result.put("fallbackSeries", fallbackSeries);
        result.put("avgLatencySeries", latencySeries);
        result.put("totals", totals);
        return result;
    }

    private List<LocalDateTime> buildBuckets(LocalDateTime start, LocalDateTime end, int bucketMinutes) {
        List<LocalDateTime> buckets = new ArrayList<>();
        LocalDateTime cursor = start;
        while (!cursor.isAfter(end)) {
            buckets.add(cursor);
            cursor = cursor.plusMinutes(bucketMinutes);
        }
        return buckets;
    }

    private LocalDateTime alignToBucket(LocalDateTime value, int bucketMinutes) {
        int alignedMinute = (value.getMinute() / bucketMinutes) * bucketMinutes;
        return value.withMinute(alignedMinute).withSecond(0).withNano(0);
    }

    private static class Bucket {
        long queueCount;
        long directCount;
        long fallbackCount;
        long latencyTotal;
        long latencySamples;
    }
}
