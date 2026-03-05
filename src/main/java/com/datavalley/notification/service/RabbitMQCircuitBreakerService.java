package com.datavalley.notification.service;

import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class RabbitMQCircuitBreakerService {

    private final AtomicBoolean circuitOpen = new AtomicBoolean(false);

    private volatile long circuitOpenedAtMs = 0L;
    private volatile long lastFailureAtMs = 0L;
    private volatile long lastSuccessAtMs = 0L;
    private volatile String lastFailureReason = "";

    @Value("${notification.rabbitmq.circuit.open-duration-ms:10000}")
    private long circuitOpenDurationMs;

    @Autowired(required = false)
    private RabbitTemplate rabbitTemplate;

    public boolean canAttemptPublish() {
        if (rabbitTemplate == null) {
            return false;
        }

        if (!circuitOpen.get()) {
            return true;
        }

        long now = System.currentTimeMillis();
        if ((now - circuitOpenedAtMs) < circuitOpenDurationMs) {
            return false;
        }

        return probeAndRecoverIfHealthy();
    }

    public void recordPublishSuccess() {
        lastSuccessAtMs = System.currentTimeMillis();
        if (circuitOpen.get()) {
            circuitOpen.set(false);
            lastFailureReason = "";
            System.out.println("RabbitMQ circuit closed after successful publish.");
        }
    }

    public void recordPublishFailure(Exception ex) {
        long now = System.currentTimeMillis();
        circuitOpen.set(true);
        circuitOpenedAtMs = now;
        lastFailureAtMs = now;
        lastFailureReason = ex != null ? ex.getClass().getSimpleName() + ": " + ex.getMessage() : "Unknown publish failure";
        System.err.println("RabbitMQ circuit opened: " + lastFailureReason);
    }

    @Scheduled(fixedDelayString = "${notification.rabbitmq.health.probe-interval-ms:3000}")
    public void probeWhenCircuitOpen() {
        if (!circuitOpen.get()) {
            return;
        }
        probeAndRecoverIfHealthy();
    }

    public Map<String, Object> snapshot() {
        long now = System.currentTimeMillis();
        long cooldownRemainingMs = circuitOpen.get()
                ? Math.max(circuitOpenDurationMs - (now - circuitOpenedAtMs), 0L)
                : 0L;

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("circuitOpen", circuitOpen.get());
        state.put("cooldownRemainingMs", cooldownRemainingMs);
        state.put("lastFailureAtMs", lastFailureAtMs);
        state.put("lastSuccessAtMs", lastSuccessAtMs);
        state.put("lastFailureReason", lastFailureReason);
        return state;
    }

    private synchronized boolean probeAndRecoverIfHealthy() {
        if (rabbitTemplate == null) {
            return false;
        }

        try {
            Boolean healthy = rabbitTemplate.execute(channel -> channel != null && channel.isOpen());
            if (Boolean.TRUE.equals(healthy)) {
                if (circuitOpen.get()) {
                    System.out.println("RabbitMQ probe healthy. Closing circuit.");
                }
                circuitOpen.set(false);
                lastFailureReason = "";
                lastSuccessAtMs = System.currentTimeMillis();
                return true;
            }
        } catch (AmqpException ex) {
            lastFailureReason = ex.getClass().getSimpleName() + ": " + ex.getMessage();
        } catch (RuntimeException ex) {
            lastFailureReason = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
        }

        circuitOpen.set(true);
        circuitOpenedAtMs = System.currentTimeMillis();
        return false;
    }
}
