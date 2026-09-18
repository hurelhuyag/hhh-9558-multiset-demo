package demo.onetomany;

import java.math.BigDecimal;

import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

/** embeddable inside the multiset element, with a nested embeddable */
@Embeddable
@Getter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@ToString
public class Dimensions {

    private Integer widthMm;

    private Integer heightMm;

    @Embedded
    private Weight weight;

    @Embeddable
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    @ToString
    public static class Weight {
        private BigDecimal kg;
    }
}
