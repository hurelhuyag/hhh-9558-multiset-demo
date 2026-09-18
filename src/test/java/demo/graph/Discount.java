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
public class Discount {

    @Id
    @GeneratedValue
    private Long id;

    @ManyToOne(optional = false)
    private OrderLine line;

    private String reason;

    private BigDecimal amount;

    Discount(OrderLine line, String reason, BigDecimal amount) {
        this.line = line;
        this.reason = reason;
        this.amount = amount;
    }
}
