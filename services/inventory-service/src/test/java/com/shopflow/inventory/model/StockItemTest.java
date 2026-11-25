package com.shopflow.inventory.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StockItemTest {

    @Test
    void change_quantity_replaces_the_stock() {
        StockItem item = new StockItem(1L, 10);

        item.changeQuantity(25);

        assertThat(item.getQuantity()).isEqualTo(25);
    }

    @Test
    void negative_quantity_is_refused() {
        StockItem item = new StockItem(1L, 10);

        assertThatThrownBy(() -> item.changeQuantity(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StockItem(2L, -5)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void remove_and_add_change_the_stock() {
        StockItem item = new StockItem(1L, 10);

        item.remove(3);
        assertThat(item.getQuantity()).isEqualTo(7);

        item.add(3);
        assertThat(item.getQuantity()).isEqualTo(10);
    }

    @Test
    void stock_never_goes_below_zero() {
        StockItem item = new StockItem(8L, 2);

        assertThat(item.canRemove(2)).isTrue();
        assertThat(item.canRemove(5)).isFalse();
        assertThatThrownBy(() -> item.remove(5)).isInstanceOf(IllegalStateException.class);
        assertThat(item.getQuantity()).isEqualTo(2);
    }
}
