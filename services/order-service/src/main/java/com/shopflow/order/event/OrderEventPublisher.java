package com.shopflow.order.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Envoie à Kafka les événements de commande publiés par OrderService,
 * seulement une fois la transaction en base validée.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventPublisher {

    public static final String TOPIC = "order-events";

    private final KafkaTemplate<String, OrderEvent> kafka;

    // AFTER_COMMIT : si la base annule la transaction, rien ne part.
    // fallbackExecution : place() n'est pas transactionnelle, l'envoi suit alors directement la sauvegarde.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void publish(OrderEvent event) {
        // Clé = orderId : tous les événements d'une commande vont dans la même partition, donc dans l'ordre
        kafka.send(TOPIC, event.orderId().toString(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Échec d'envoi de l'événement {} ({} de la commande {})",
                                event.eventId(), event.type(), event.orderId(), ex);
                    } else {
                        log.info("Événement {} publié pour la commande {} (partition {}, offset {})",
                                event.type(), event.orderId(),
                                result.getRecordMetadata().partition(), result.getRecordMetadata().offset());
                    }
                });
    }
}
