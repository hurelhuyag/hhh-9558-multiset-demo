package demo.graph;

import java.util.ArrayList;
import java.util.List;

import dev.hurlee.hibernate.multiset.MultisetFetch;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "orders")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@NamedEntityGraph(
    name = Order.WITH_PRODUCTS,
    attributeNodes = {
        @NamedAttributeNode(value = "lines", subgraph = "OrderLine.withProduct"),
    },
    subgraphs = {
        @NamedSubgraph(
            name = "OrderLine.withProduct",
            attributeNodes = {
                @NamedAttributeNode("product")
            }
        )
    }
)
public class Order {

    public static final String WITH_PRODUCTS = "Order.withProducts";

    @Id
    @GeneratedValue
    private Long id;

    private String customer;

    /** LAZY by default; loaded as a multiset when the {@link #WITH_PRODUCTS} graph is applied. */
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @MultisetFetch
    private List<OrderLine> lines = new ArrayList<>();

    public Order(String customer) {
        this.customer = customer;
    }

    public Order addLine(Product product, int quantity) {
        lines.add(new OrderLine(this, product, quantity));
        return this;
    }
}
