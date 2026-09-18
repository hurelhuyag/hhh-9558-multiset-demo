package demo.graph;

import java.math.BigDecimal;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Product {

    @Id
    @GeneratedValue
    private Long id;

    private String sku;

    private BigDecimal price;

    /** second nesting level: Order -> lines -> product -> category, all EAGER */
    @ManyToOne
    private Category category;

    public Product(String sku, BigDecimal price, Category category) {
        this.sku = sku;
        this.price = price;
        this.category = category;
    }
}
