package com.datavalley.notification.service;

import com.datavalley.notification.config.RabbitMQConfig;
import com.datavalley.notification.model.MultiTargetNotificationRequest;
import com.datavalley.notification.model.MultiTargetNotificationResult;
import com.datavalley.notification.model.Notification;
import com.datavalley.notification.model.NotificationDTO;
import com.datavalley.notification.model.User;
import com.datavalley.notification.repository.NotificationRepository;
import com.datavalley.notification.repository.UserRepository;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class NotificationService {

    private static final String PRIORITY_MODE_SINGLE = "SINGLE";
    private static final String PRIORITY_MODE_MIXED_HIGH_FIRST = "MIXED_HIGH_FIRST";
    private static final String PRIORITY_MODE_BALANCED = "BALANCED";
    private static final String PRIORITY_MODE_HIGH_FOCUS = "HIGH_FOCUS";
    private static final String PRIORITY_MODE_NORMAL_INFO = "NORMAL_INFO";
    private static final List<String> SUPPORTED_PRIORITY_MODES = List.of(
            PRIORITY_MODE_SINGLE,
            PRIORITY_MODE_MIXED_HIGH_FIRST,
            PRIORITY_MODE_BALANCED,
            PRIORITY_MODE_HIGH_FOCUS,
            PRIORITY_MODE_NORMAL_INFO
    );
    private static final List<Notification.Priority> HIGH_PRIORITY_PHASE = List.of(
            Notification.Priority.CRITICAL,
            Notification.Priority.WARNING
    );
    private static final List<Notification.Priority> LOW_PRIORITY_PHASE = List.of(
            Notification.Priority.NORMAL,
            Notification.Priority.INFO
    );
    private static final List<WeightedPriority> MIXED_WEIGHTS = List.of(
            new WeightedPriority(Notification.Priority.CRITICAL, 35),
            new WeightedPriority(Notification.Priority.WARNING, 30),
            new WeightedPriority(Notification.Priority.NORMAL, 20),
            new WeightedPriority(Notification.Priority.INFO, 15)
    );

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired(required = false)
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private NotificationProcessor notificationProcessor;

    @Autowired
    private RabbitMQModeService rabbitMQModeService;

    @Autowired
    private RabbitMQCircuitBreakerService rabbitMQCircuitBreakerService;

    @Value("${notification.rate-limit.enabled:false}")
    private boolean rateLimitEnabled;

    @Value("${notification.rate-limit.min-interval-ms:50}")
    private long minIntervalMs;

    @Value("${notification.direct-mode.degraded.enabled:true}")
    private boolean directModeDegradedEnabled;

    @Value("${notification.direct-mode.degraded.normal-delivery-rate:0.75}")
    private double directModeNormalDeliveryRate;

    @Value("${notification.direct-mode.degraded.info-delivery-rate:0.50}")
    private double directModeInfoDeliveryRate;

    // Simple in-memory limiter for demo-mode only.
    private final java.util.concurrent.ConcurrentHashMap<String, Long> requestTimestamps = new java.util.concurrent.ConcurrentHashMap<>();

    public Notification sendNotification(NotificationDTO dto) {
        String key = dto.getUsername() != null ? dto.getUsername() : "GLOBAL_BROADCAST";
        applyRateLimit(key);

        Notification notification = new Notification();
        notification.setMessage(dto.getMessage());
        notification.setPriority(dto.getPriority());
        notification.setTimestamp(LocalDateTime.now());
        notification.setStatus(Notification.NotificationStatus.PENDING);
        notification.setDeliveryAttempts(0);

        if (dto.getUsername() != null && !dto.getUsername().isEmpty()) {
            User user = userRepository.findByUsername(dto.getUsername())
                    .orElseThrow(() -> new com.datavalley.notification.exception.UserNotFoundException("User not found: " + dto.getUsername()));
            notification.setRecipient(user);
        }

        // Save PENDING notification to DB
        Notification savedNotification = notificationRepository.save(notification);

        // Push to RabbitMQ based on Priority
        // Map CRITICAL and WARNING to the Critical Queue (High Priority)
        // Map NORMAL and INFO to the Normal Queue (Low Priority)
        String routingKey;
        switch (dto.getPriority()) {
            case CRITICAL:
            case WARNING:
                routingKey = RabbitMQConfig.ROUTING_KEY_CRITICAL;
                break;
            default:
                routingKey = RabbitMQConfig.ROUTING_KEY_NORMAL;
                break;
        }

        if (!rabbitMQModeService.isRabbitMqEnabled()) {
            System.out.println("RabbitMQ mode is OFF. Processing notification directly. ID: " + savedNotification.getId());
            if (shouldSkipDirectModeDelivery(savedNotification)) {
                System.out.println("Direct mode degraded delivery skipped notification. ID: " + savedNotification.getId() + " Priority: " + savedNotification.getPriority());
                notificationProcessor.markAsFailed(savedNotification, DeliveryPath.DIRECT_MODE);
                return savedNotification;
            }
            notificationProcessor.process(savedNotification, false, DeliveryPath.DIRECT_MODE);
            return savedNotification;
        }

        if (rabbitTemplate == null) {
            System.out.println("RabbitTemplate unavailable. Processing notification directly. ID: " + savedNotification.getId());
            notificationProcessor.process(savedNotification, false, DeliveryPath.DIRECT_FALLBACK);
            return savedNotification;
        }

        if (!rabbitMQCircuitBreakerService.canAttemptPublish()) {
            System.out.println("RabbitMQ circuit is OPEN. Skipping publish and using direct fallback. ID: " + savedNotification.getId());
            notificationProcessor.process(savedNotification, false, DeliveryPath.DIRECT_FALLBACK);
            return savedNotification;
        }

        try {
            rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE_NAME, routingKey, savedNotification);
            rabbitMQCircuitBreakerService.recordPublishSuccess();
            System.out.println("Message sent to Queue: " + routingKey + " ID: " + savedNotification.getId());
        } catch (AmqpException ex) {
            rabbitMQCircuitBreakerService.recordPublishFailure(ex);
            System.err.println("RabbitMQ publish failed, fallback to direct dispatch. ID: " + savedNotification.getId() + " Error: " + ex.getMessage());
            notificationProcessor.process(savedNotification, false, DeliveryPath.DIRECT_FALLBACK);
        }

        return savedNotification;
    }

    public MultiTargetNotificationResult sendMultiTargetNotifications(MultiTargetNotificationRequest request) {
        List<NormalizedItem> items = normalizeItems(request);

        int totalRequested = 0;
        int totalSent = 0;
        int totalFailed = 0;
        List<MultiTargetNotificationResult.ItemResult> itemResults = new ArrayList<>();

        for (NormalizedItem item : items) {
            int sent = 0;
            int failed = 0;
            String lastError = null;
            List<Notification.Priority> priorityPlan = buildPriorityPlan(item.count(), item.priorityMode(), item.singlePriority());

            for (int i = 1; i <= item.count(); i++) {
                NotificationDTO dto = new NotificationDTO();
                dto.setUsername(item.username());
                dto.setPriority(priorityPlan.get(i - 1));
                dto.setMessage(resolveMessage(item.message(), i));

                try {
                    sendNotification(dto);
                    sent++;
                } catch (RuntimeException ex) {
                    failed++;
                    lastError = ex.getMessage() != null ? ex.getMessage() : "Failed to send notification.";
                }
            }

            totalRequested += item.count();
            totalSent += sent;
            totalFailed += failed;

            itemResults.add(new MultiTargetNotificationResult.ItemResult(
                    item.username(),
                    item.message(),
                    item.priorityMode(),
                    summarizePriorityPlan(priorityPlan),
                    item.count(),
                    sent,
                    failed,
                    lastError
            ));
        }

        return new MultiTargetNotificationResult(totalRequested, totalSent, totalFailed, itemResults);
    }

    private void applyRateLimit(String key) {
        if (!rateLimitEnabled || minIntervalMs <= 0) {
            return;
        }

        long now = System.currentTimeMillis();
        AtomicBoolean blocked = new AtomicBoolean(false);
        requestTimestamps.compute(key, (k, last) -> {
            if (last != null && (now - last) < minIntervalMs) {
                blocked.set(true);
                return last;
            }
            return now;
        });

        if (blocked.get()) {
            throw new RuntimeException("Rate limit exceeded! Please wait before sending another notification.");
        }
    }

    public java.util.List<Notification> getAllNotifications() {
        return notificationRepository.findAll(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "timestamp"));
    }

    public void deleteNotification(Long id) {
        notificationRepository.deleteById(id);
    }

    public long getNotificationCount() {
        return notificationRepository.count();
    }

    private boolean shouldSkipDirectModeDelivery(Notification notification) {
        if (!directModeDegradedEnabled || notification == null || notification.getPriority() == null) {
            return false;
        }

        return switch (notification.getPriority()) {
            case CRITICAL, WARNING -> false;
            case NORMAL -> !passesDeliveryRate(directModeNormalDeliveryRate);
            case INFO -> !passesDeliveryRate(directModeInfoDeliveryRate);
        };
    }

    private boolean passesDeliveryRate(double configuredRate) {
        double rate = Math.max(0.0, Math.min(configuredRate, 1.0));
        return ThreadLocalRandom.current().nextDouble() < rate;
    }

    private List<NormalizedItem> normalizeItems(MultiTargetNotificationRequest request) {
        if (request == null || request.getItems() == null || request.getItems().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At least one target item is required.");
        }

        List<NormalizedItem> normalized = new ArrayList<>();
        int index = 0;

        for (MultiTargetNotificationRequest.DeliveryItem item : request.getItems()) {
            index++;
            if (item == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid target item at position " + index + ".");
            }

            String message = normalizeRequired(item.getMessage(), "Message is required for target item " + index + ".");
            int count = item.getCount() == null ? 1 : item.getCount();
            if (count < 1 || count > 5000) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Count must be between 1 and 5000 for target item " + index + ".");
            }

            String username = StringUtils.hasText(item.getUsername()) ? item.getUsername().trim() : null;
            String priorityMode = normalizePriorityMode(item, index);
            Notification.Priority singlePriority = resolveSinglePriority(item, priorityMode, index);

            normalized.add(new NormalizedItem(username, message, count, priorityMode, singlePriority));
        }

        return normalized;
    }

    private String normalizeRequired(String value, String errorMessage) {
        if (!StringUtils.hasText(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, errorMessage);
        }
        return value.trim();
    }

    private String resolveMessage(String template, int iteration) {
        if (template.contains("{i}")) {
            return template.replace("{i}", String.valueOf(iteration));
        }
        return iteration > 1 ? template + " #" + iteration : template;
    }

    private String normalizePriorityMode(MultiTargetNotificationRequest.DeliveryItem item, int index) {
        String mode = StringUtils.hasText(item.getPriorityMode())
                ? item.getPriorityMode().trim().toUpperCase()
                : null;

        if (mode == null) {
            if (item.getPriority() != null || item.getSinglePriority() != null) {
                return PRIORITY_MODE_SINGLE;
            }
            return PRIORITY_MODE_BALANCED;
        }

        if (!SUPPORTED_PRIORITY_MODES.contains(mode)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid priority mode for target item " + index + ". Supported: " + String.join(", ", SUPPORTED_PRIORITY_MODES)
            );
        }

        return mode;
    }

    private Notification.Priority resolveSinglePriority(
            MultiTargetNotificationRequest.DeliveryItem item,
            String priorityMode,
            int index) {
        if (!PRIORITY_MODE_SINGLE.equals(priorityMode)) {
            return null;
        }

        Notification.Priority singlePriority = item.getSinglePriority() != null ? item.getSinglePriority() : item.getPriority();
        if (singlePriority == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Single priority is required when priorityMode is SINGLE for target item " + index + "."
            );
        }
        return singlePriority;
    }

    private List<Notification.Priority> buildPriorityPlan(
            int count,
            String priorityMode,
            Notification.Priority singlePriority) {
        if (count <= 0) {
            return List.of();
        }

        if (PRIORITY_MODE_SINGLE.equals(priorityMode)) {
            Notification.Priority value = singlePriority != null ? singlePriority : Notification.Priority.WARNING;
            return java.util.Collections.nCopies(count, value);
        }

        if (PRIORITY_MODE_MIXED_HIGH_FIRST.equals(priorityMode)) {
            List<Notification.Priority> sampled = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                sampled.add(pickWeightedPriority(MIXED_WEIGHTS));
            }

            List<Notification.Priority> high = new ArrayList<>();
            List<Notification.Priority> low = new ArrayList<>();
            for (Notification.Priority priority : sampled) {
                if (HIGH_PRIORITY_PHASE.contains(priority)) {
                    high.add(priority);
                } else {
                    low.add(priority);
                }
            }

            List<Notification.Priority> result = new ArrayList<>(count);
            result.addAll(buildRandomizedPhase(high, HIGH_PRIORITY_PHASE));
            result.addAll(buildRandomizedPhase(low, LOW_PRIORITY_PHASE));
            return result;
        }

        List<Notification.Priority> pattern = switch (priorityMode) {
            case PRIORITY_MODE_HIGH_FOCUS -> HIGH_PRIORITY_PHASE;
            case PRIORITY_MODE_NORMAL_INFO -> LOW_PRIORITY_PHASE;
            case PRIORITY_MODE_BALANCED -> List.of(
                    Notification.Priority.CRITICAL,
                    Notification.Priority.WARNING,
                    Notification.Priority.NORMAL,
                    Notification.Priority.INFO
            );
            default -> List.of(Notification.Priority.WARNING);
        };

        List<Notification.Priority> plan = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            plan.add(pattern.get(i % pattern.size()));
        }
        return plan;
    }

    private Notification.Priority pickWeightedPriority(List<WeightedPriority> weightedPool) {
        int totalWeight = 0;
        for (WeightedPriority item : weightedPool) {
            totalWeight += item.weight();
        }

        double cursor = ThreadLocalRandom.current().nextDouble(totalWeight);
        for (WeightedPriority item : weightedPool) {
            cursor -= item.weight();
            if (cursor <= 0) {
                return item.priority();
            }
        }
        return weightedPool.get(weightedPool.size() - 1).priority();
    }

    private List<Notification.Priority> buildRandomizedPhase(
            List<Notification.Priority> priorities,
            List<Notification.Priority> phaseOrder) {
        if (priorities.isEmpty()) {
            return List.of();
        }

        Map<Notification.Priority, List<Notification.Priority>> buckets = new EnumMap<>(Notification.Priority.class);
        for (Notification.Priority priority : phaseOrder) {
            buckets.put(priority, new ArrayList<>());
        }

        for (Notification.Priority priority : priorities) {
            List<Notification.Priority> bucket = buckets.get(priority);
            if (bucket != null) {
                bucket.add(priority);
            }
        }

        List<Notification.Priority> phasePlan = new ArrayList<>(priorities.size());
        Notification.Priority previous = null;

        while (phasePlan.size() < priorities.size()) {
            List<Notification.Priority> available = new ArrayList<>();
            for (Notification.Priority priority : phaseOrder) {
                List<Notification.Priority> bucket = buckets.get(priority);
                if (bucket != null && !bucket.isEmpty()) {
                    available.add(priority);
                }
            }
            if (available.isEmpty()) {
                break;
            }

            List<Notification.Priority> withoutPrevious = new ArrayList<>();
            for (Notification.Priority priority : available) {
                if (priority != previous) {
                    withoutPrevious.add(priority);
                }
            }

            List<Notification.Priority> candidates = withoutPrevious.isEmpty() ? available : withoutPrevious;
            Notification.Priority selected = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
            List<Notification.Priority> bucket = buckets.get(selected);
            bucket.remove(bucket.size() - 1);
            phasePlan.add(selected);
            previous = selected;
        }

        return phasePlan;
    }

    private String summarizePriorityPlan(List<Notification.Priority> priorityPlan) {
        Map<Notification.Priority, Integer> counts = new EnumMap<>(Notification.Priority.class);
        for (Notification.Priority priority : Notification.Priority.values()) {
            counts.put(priority, 0);
        }
        for (Notification.Priority priority : priorityPlan) {
            counts.put(priority, counts.getOrDefault(priority, 0) + 1);
        }

        List<String> tokens = new ArrayList<>();
        for (Notification.Priority priority : Notification.Priority.values()) {
            int value = counts.getOrDefault(priority, 0);
            if (value > 0) {
                tokens.add(priority.name() + ":" + value);
            }
        }
        return tokens.isEmpty() ? "-" : String.join(", ", tokens);
    }

    private record NormalizedItem(
            String username,
            String message,
            int count,
            String priorityMode,
            Notification.Priority singlePriority) {
    }

    private record WeightedPriority(Notification.Priority priority, int weight) {
    }
}
