package demo.onetomany;

import java.util.ArrayList;
import java.util.List;

import dev.hurlee.hibernate.multiset.MultisetFetch;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.OrderColumn;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Two EAGER bags on one entity — normally a {@code MultipleBagFetchException} (join) or N+1/subselect
 * statements. With {@link MultisetFetch} both are loaded in the owner's own statement.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Author {

    @Id
    @GeneratedValue
    private Long id;

    private String name;

    /** an ordered List: the index column rides inside the record and drives the aggregate's ORDER BY */
    @OneToMany(mappedBy = "author", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderColumn(name = "position")
    @MultisetFetch
    private List<Book> books = new ArrayList<>();

    /** a bag with {@code @OrderBy}: applied inside the aggregate (client-side on MySQL) */
    @OneToMany(mappedBy = "author", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("stars desc nulls last, text")
    @MultisetFetch
    private List<Review> reviews = new ArrayList<>();

    public Author(String name) {
        this.name = name;
    }

    public Author addBook(String title, int pages) {
        books.add(new Book(this, title, pages));
        return this;
    }

    public Author addReview(String text, Integer stars) {
        reviews.add(new Review(this, text, stars));
        return this;
    }
}
