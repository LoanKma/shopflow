package com.shopflow.inventory.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Événement de commande lu sur le topic order-events (publié par order-service).
 * Copie locale du contrat : chaque service a sa propre classe, seul le format JSON est partagé.
 */
public record OrderEvent(
        UUID eventId,
        OrderEventType type,
        Long orderId,
        String customerId,
        BigDecimal total,
        List<Line> lines,
        Instant occurredAt
) {

    public record Line(Long productId, int quantity) {
    }
}
