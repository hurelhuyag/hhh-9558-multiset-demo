package demo.graph;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import jakarta.persistence.CascadeType;
import dev.hurlee.hibernate.multiset.MultisetFetch;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderLine {

    @Id
    @GeneratedValue
    private Long id;

    @ManyToOne(optional = false)
    private Order order;

    /** LAZY; part of the {@code Order.withProducts} graph */
    @ManyToOne(fetch = FetchType.LAZY)
    private Product product;

    private int quantity;

    /** a collection inside the multiset element: nested multiset */
    @OneToMany(mappedBy = "line", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @MultisetFetch
    private List<Discount> discounts = new ArrayList<>();

    public OrderLine discount(String reason, BigDecimal amount) {
        discounts.add(new Discount(this, reason, amount));
        return this;
    }

    OrderLine(Order order, Product product, int quantity) {
        this.order = order;
        this.product = product;
        this.quantity = quantity;
    }
}
