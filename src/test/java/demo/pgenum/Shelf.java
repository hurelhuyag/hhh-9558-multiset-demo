package demo.pgenum;

import java.util.ArrayList;
import java.util.List;

import dev.hurlee.hibernate.multiset.MultisetFetch;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Shelf {

    @Id
    @GeneratedValue
    private Long id;

    private String label;

    @OneToMany(mappedBy = "shelf", cascade = CascadeType.ALL, fetch = FetchType.EAGER)
    @MultisetFetch
    private List<Volume> volumes = new ArrayList<>();

    public Shelf(String label) {
        this.label = label;
    }

    public Shelf add(String title, Genre genre) {
        volumes.add(new Volume(this, title, genre));
        return this;
    }
}
