package demo.onetomany;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
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
public class Book {

    @Id
    @GeneratedValue
    private Long id;

    @ManyToOne(optional = false)
    private Author author;

    private String title;

    private int pages;

    /** lazy to-one inside the multiset element: its FK travels in the record and yields a proxy */
    @ManyToOne(fetch = FetchType.LAZY)
    private Publisher publisher;

    Book(Author author, String title, int pages) {
        this.author = author;
        this.title = title;
        this.pages = pages;
    }
}
