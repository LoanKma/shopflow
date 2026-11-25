package com.shopflow.order.event;

import com.shopflow.order.model.Order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Événement publié sur le topic order-events quand une commande est créée ou annulée.
 * inventory-service et notification-service ont chacun leur propre copie de ce record :
 * le contrat entre services, c'est le JSON, pas une classe Java partagée.
 */
public record OrderEvent(
        UUID eventId,          // identifiant unique du message : sert à l'idempotence côté consommateur
        OrderEventType type,
        Long orderId,
        String customerId,
        BigDecimal total,
        List<Line> lines,
        Instant occurredAt
) {

    /** Une ligne commandée : seulement ce dont les autres services ont besoin. */
    public record Line(Long productId, int quantity) {
    }

    public static OrderEvent of(OrderEventType type, Order order) {
        List<Line> lines = order.getLines().stream()
                .map(l -> new Line(l.getProductId(), l.getQuantity()))
                .toList();
        return new OrderEvent(UUID.randomUUID(), type, order.getId(), order.getCustomerId(),
                order.getTotal(), lines, Instant.now());
    }
}
