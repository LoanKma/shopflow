package com.shopflow.inventory.repository;

import com.shopflow.inventory.event.OrderEventType;
import com.shopflow.inventory.model.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, UUID> {

    /** La création de cette commande a-t-elle vraiment retiré du stock ? */
    boolean existsByOrderIdAndTypeAndStockChangedTrue(Long orderId, OrderEventType type);
}
