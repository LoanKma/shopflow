package com.shopflow.notification.listener;

import com.shopflow.notification.dto.NotificationRequest;
import com.shopflow.notification.event.OrderEvent;
import com.shopflow.notification.event.OrderEventType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OrderEventsListenerTest {

    private static OrderEvent event(OrderEventType type) {
        return new OrderEvent(UUID.randomUUID(), type, 7L, "c-1", new BigDecimal("229.70"),
                List.of(new OrderEvent.Line(1L, 2)), Instant.now());
    }

    @Test
    void created_order_sends_a_received_email_to_the_customer() {
        NotificationRequest email = OrderEventsListener.toEmail(event(OrderEventType.CREATED));

        assertThat(email.to()).isEqualTo("c-1@shopflow.example");
        assertThat(email.subject()).isEqualTo("Commande n° 7 reçue");
        assertThat(email.body()).contains("229.70");
    }

    @Test
    void cancelled_order_sends_a_cancelled_email() {
        NotificationRequest email = OrderEventsListener.toEmail(event(OrderEventType.CANCELLED));

        assertThat(email.subject()).isEqualTo("Commande n° 7 annulée");
    }
}
