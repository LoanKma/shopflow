package com.shopflow.inventory.service;

import com.shopflow.inventory.dto.StockResponse;
import com.shopflow.inventory.event.OrderEvent;
import com.shopflow.inventory.event.OrderEventType;
import com.shopflow.inventory.exception.StockItemNotFoundException;
import com.shopflow.inventory.model.ProcessedEvent;
import com.shopflow.inventory.model.StockItem;
import com.shopflow.inventory.repository.ProcessedEventRepository;
import com.shopflow.inventory.repository.StockItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Consultation et mise à jour du stock : à la main (API) ou à partir des événements de commande (Kafka). */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockService {

    private final StockItemRepository repository;
    private final ProcessedEventRepository processedEvents;

    @Transactional(readOnly = true)
    public List<StockResponse> findAll() {
        return repository.findAll(Sort.by("productId")).stream()
                .map(StockResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public StockResponse findByProductId(Long productId) {
        return StockResponse.from(load(productId));
    }

    /**
     * Fixe la quantité en stock d'un produit.
     * Si le produit n'a pas encore de stock, la ligne est créée.
     */
    @Transactional
    public StockResponse updateQuantity(Long productId, int quantity) {
        StockItem item = repository.findById(productId)
                .orElseGet(() -> repository.save(new StockItem(productId, 0)));
        item.changeQuantity(quantity);
        log.info("Stock du produit {} : {}", productId, quantity);
        return StockResponse.from(item);
    }

    /**
     * Applique un événement de commande au stock, une seule fois.
     * Une seule transaction : la vérification du doublon, le stock et le ProcessedEvent
     * sont enregistrés ensemble, ou pas du tout.
     */
    @Transactional
    public void apply(OrderEvent event) {
        // 1. Idempotence : un message déjà traité est ignoré
        if (processedEvents.existsById(event.eventId())) {
            log.info("Doublon ignoré : événement {} ({} de la commande {})",
                    event.eventId(), event.type(), event.orderId());
            return;
        }

        // 2. Retirer (CREATED) ou remettre (CANCELLED) le stock
        boolean stockChanged = switch (event.type()) {
            case CREATED -> removeStock(event);
            case CANCELLED -> restoreStock(event);
        };

        // 3. Noter l'événement comme traité, dans la même transaction que le stock
        processedEvents.save(new ProcessedEvent(event.eventId(), event.orderId(), event.type(), stockChanged));
    }

    /** CREATED : on vérifie TOUTES les lignes avant de modifier quoi que ce soit. */
    private boolean removeStock(OrderEvent event) {
        Map<Long, Integer> wanted = quantitiesByProduct(event);
        Map<Long, StockItem> items = new LinkedHashMap<>();
        for (Map.Entry<Long, Integer> line : wanted.entrySet()) {
            StockItem item = repository.findById(line.getKey()).orElse(null);
            if (item == null || !item.canRemove(line.getValue())) {
                // La Saga traitera ce cas dans un prochain TP (annulation de la commande)
                log.warn("Stock insuffisant pour la commande {} : produit {} ({} en stock, {} demandé(s)). Stock inchangé.",
                        event.orderId(), line.getKey(), item == null ? 0 : item.getQuantity(), line.getValue());
                return false;
            }
            items.put(line.getKey(), item);
        }
        wanted.forEach((productId, quantity) -> items.get(productId).remove(quantity));
        log.info("Stock retiré pour la commande {} : {}", event.orderId(), wanted);
        return true;
    }

    /** CANCELLED : on ne remet en stock que ce que la création avait vraiment retiré. */
    private boolean restoreStock(OrderEvent event) {
        if (!processedEvents.existsByOrderIdAndTypeAndStockChangedTrue(event.orderId(), OrderEventType.CREATED)) {
            log.info("Commande {} annulée : aucun stock n'avait été retiré pour elle, rien à remettre", event.orderId());
            return false;
        }
        Map<Long, Integer> quantities = quantitiesByProduct(event);
        quantities.forEach((productId, quantity) -> repository.findById(productId)
                .orElseGet(() -> repository.save(new StockItem(productId, 0)))
                .add(quantity));
        log.info("Stock remis pour la commande {} : {}", event.orderId(), quantities);
        return true;
    }

    /** Quantité totale par produit (au cas où un produit apparaîtrait sur deux lignes). */
    private static Map<Long, Integer> quantitiesByProduct(OrderEvent event) {
        Map<Long, Integer> quantities = new LinkedHashMap<>();
        event.lines().forEach(l -> quantities.merge(l.productId(), l.quantity(), Integer::sum));
        return quantities;
    }

    private StockItem load(Long productId) {
        return repository.findById(productId)
                .orElseThrow(() -> new StockItemNotFoundException(productId));
    }
}
