package demo.onetomany;

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
public class Review {

    @Id
    @GeneratedValue
    private Long id;

    @ManyToOne(optional = false)
    private Author author;

    private String text;

    /** nullable on purpose: exercises NULL fields inside the record */
    private Integer stars;

    Review(Author author, String text, Integer stars) {
        this.author = author;
        this.text = text;
        this.stars = stars;
    }
}
