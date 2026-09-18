package demo.manytomany;

import java.util.LinkedHashSet;
import java.util.Set;

import dev.hurlee.hibernate.multiset.MultisetFetch;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Many-to-many through a join table: the multiset subquery joins {@code role_permission} to {@code Permission}. */
@Entity
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Role {

    @Id
    @GeneratedValue
    private Long id;

    private String name;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "role_permission")
    @MultisetFetch
    private Set<Permission> permissions = new LinkedHashSet<>();

    public Role(String name) {
        this.name = name;
    }

    public Role grant(Permission permission) {
        permissions.add(permission);
        return this;
    }
}
