package demo.pgenum;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Volume {

    @Id
    @GeneratedValue
    private Long id;

    @ManyToOne(optional = false)
    private Shelf shelf;

    private String title;

    /** {@code create type Genre as enum ('FICTION', 'SCIENCE', 'HISTORY')} — its own type OID on the wire */
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private Genre genre;

    Volume(Shelf shelf, String title, Genre genre) {
        this.shelf = shelf;
        this.title = title;
        this.genre = genre;
    }
}
