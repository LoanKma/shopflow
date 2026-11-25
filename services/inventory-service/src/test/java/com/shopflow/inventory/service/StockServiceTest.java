package com.shopflow.inventory.service;

import com.shopflow.inventory.dto.StockResponse;
import com.shopflow.inventory.event.OrderEvent;
import com.shopflow.inventory.event.OrderEventType;
import com.shopflow.inventory.exception.StockItemNotFoundException;
import com.shopflow.inventory.model.ProcessedEvent;
import com.shopflow.inventory.model.StockItem;
import com.shopflow.inventory.repository.ProcessedEventRepository;
import com.shopflow.inventory.repository.StockItemRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StockServiceTest {

    @Mock
    StockItemRepository repository;

    @Mock
    ProcessedEventRepository processedEvents;

    @InjectMocks
    StockService service;

    private static OrderEvent event(OrderEventType type, OrderEvent.Line... lines) {
        return new OrderEvent(UUID.randomUUID(), type, 42L, "c-1", new BigDecimal("100.00"),
                List.of(lines), Instant.now());
    }

    @Test
    void returns_the_stock_of_a_product() {
        when(repository.findById(1L)).thenReturn(Optional.of(new StockItem(1L, 10)));

        StockResponse response = service.findByProductId(1L);

        assertThat(response.quantity()).isEqualTo(10);
    }

    @Test
    void unknown_product_raises_not_found() {
        when(repository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findByProductId(999L)).isInstanceOf(StockItemNotFoundException.class);
    }

    @Test
    void update_creates_the_stock_line_when_missing() {
        when(repository.findById(11L)).thenReturn(Optional.empty());
        when(repository.save(any(StockItem.class))).thenAnswer(inv -> inv.getArgument(0));

        StockResponse response = service.updateQuantity(11L, 7);

        assertThat(response.productId()).isEqualTo(11L);
        assertThat(response.quantity()).isEqualTo(7);
    }

    @Test
    void created_event_removes_the_stock_and_is_recorded() {
        StockItem keyboard = new StockItem(1L, 10);
        when(repository.findById(1L)).thenReturn(Optional.of(keyboard));
        OrderEvent created = event(OrderEventType.CREATED, new OrderEvent.Line(1L, 2));

        service.apply(created);

        assertThat(keyboard.getQuantity()).isEqualTo(8);
        ArgumentCaptor<ProcessedEvent> captor = ArgumentCaptor.forClass(ProcessedEvent.class);
        verify(processedEvents).save(captor.capture());
        assertThat(captor.getValue().getEventId()).isEqualTo(created.eventId());
        assertThat(captor.getValue().isStockChanged()).isTrue();
    }

    @Test
    void duplicate_event_is_ignored() {
        OrderEvent created = event(OrderEventType.CREATED, new OrderEvent.Line(1L, 2));
        when(processedEvents.existsById(created.eventId())).thenReturn(true);

        service.apply(created);

        verify(repository, never()).findById(any());
        verify(processedEvents, never()).save(any());
    }

    @Test
    void insufficient_stock_on_one_line_changes_no_line() {
        StockItem keyboard = new StockItem(1L, 10);
        StockItem webcam = new StockItem(8L, 2);
        when(repository.findById(1L)).thenReturn(Optional.of(keyboard));
        when(repository.findById(8L)).thenReturn(Optional.of(webcam));

        service.apply(event(OrderEventType.CREATED, new OrderEvent.Line(1L, 1), new OrderEvent.Line(8L, 5)));

        assertThat(keyboard.getQuantity()).isEqualTo(10);
        assertThat(webcam.getQuantity()).isEqualTo(2);
        ArgumentCaptor<ProcessedEvent> captor = ArgumentCaptor.forClass(ProcessedEvent.class);
        verify(processedEvents).save(captor.capture());
        assertThat(captor.getValue().isStockChanged()).isFalse();
    }

    @Test
    void cancelled_event_puts_the_stock_back() {
        StockItem keyboard = new StockItem(1L, 8);
        when(processedEvents.existsByOrderIdAndTypeAndStockChangedTrue(42L, OrderEventType.CREATED)).thenReturn(true);
        when(repository.findById(1L)).thenReturn(Optional.of(keyboard));

        service.apply(event(OrderEventType.CANCELLED, new OrderEvent.Line(1L, 2)));

        assertThat(keyboard.getQuantity()).isEqualTo(10);
    }

    @Test
    void cancelling_an_order_whose_stock_was_never_removed_changes_nothing() {
        when(processedEvents.existsByOrderIdAndTypeAndStockChangedTrue(42L, OrderEventType.CREATED)).thenReturn(false);

        service.apply(event(OrderEventType.CANCELLED, new OrderEvent.Line(8L, 5)));

        verify(repository, never()).findById(any());
        verify(processedEvents).save(any(ProcessedEvent.class));
    }
}
