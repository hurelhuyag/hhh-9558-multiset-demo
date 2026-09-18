package demo.elementcollection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.hurlee.hibernate.multiset.MultisetFetch;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.OrderColumn;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Three {@code @ElementCollection}s, all multiset-fetched in the customer's own statement. */
@Entity
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Customer {

    @Id
    @GeneratedValue
    private Long id;

    private String name;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "customer_phone")
    @Column(name = "number")
    @MultisetFetch
    private Set<String> phoneNumbers = new LinkedHashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "customer_address")
    @OrderColumn(name = "position")
    @MultisetFetch
    private List<Address> addresses = new ArrayList<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "customer_attribute")
    @MapKeyColumn(name = "attr_key")
    @Column(name = "attr_value")
    @MultisetFetch
    private Map<String, String> attributes = new LinkedHashMap<>();

    public Customer(String name) {
        this.name = name;
    }
}
