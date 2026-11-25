package com.shopflow.inventory.model;

import com.shopflow.inventory.event.OrderEventType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Événement Kafka déjà traité. Kafka livre « au moins une fois » : un même message peut arriver
 * deux fois. Avant de toucher au stock, on vérifie que son eventId n'est pas déjà dans cette table.
 */
@Entity
@Table(name = "processed_event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProcessedEvent {

    @Id
    private UUID eventId;

    @Column(nullable = false)
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderEventType type;

    /** Faux si l'événement n'a rien changé (stock insuffisant) : une annulation n'aura alors rien à remettre. */
    @Column(nullable = false)
    private boolean stockChanged;

    @Column(nullable = false)
    private Instant processedAt;

    public ProcessedEvent(UUID eventId, Long orderId, OrderEventType type, boolean stockChanged) {
        this.eventId = eventId;
        this.orderId = orderId;
        this.type = type;
        this.stockChanged = stockChanged;
        this.processedAt = Instant.now();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ProcessedEvent other)) return false;
        return eventId != null && Objects.equals(eventId, other.eventId);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
