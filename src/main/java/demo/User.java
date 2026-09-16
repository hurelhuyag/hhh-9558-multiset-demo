package demo;

import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Formula;
import org.hibernate.annotations.Parameter;
import org.hibernate.annotations.Type;

/**
 * A user with TWO bag collections. Join-fetching both at once is refused by Hibernate
 * (MultipleBagFetchException); doing it anyway would yield phones x emails rows per user.
 * <p>
 * The {@code @Formula} attributes emulate the MULTISET operator: each bag comes back as one
 * {@code array_agg(ROW(...))} value in the projection, decoded from the binary wire format by
 * {@link RecordArrayType}, so the whole graph loads in ONE statement with exactly one row per user.
 */
@Entity
@Table(name = "app_user")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    @Id
    @GeneratedValue
    private Long id;

    private String name;

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Phone> phones = new ArrayList<>();

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Email> emails = new ArrayList<>();

    /** MULTISET(SELECT id, number FROM phone WHERE user_id = id). */
    @Formula("(select array_agg(row(p.id, p.number) order by p.id) from phone p where p.user_id = id)")
    @Type(value = RecordArrayType.class, parameters = @Parameter(name = RecordArrayType.RECORD_PARAM, value = "demo.User$PhoneView"))
    @Setter(AccessLevel.NONE)
    private List<PhoneView> phoneMultiset;

    /** MULTISET(SELECT id, address FROM email WHERE user_id = id). */
    @Formula("(select array_agg(row(e.id, e.address) order by e.id) from email e where e.user_id = id)")
    @Type(value = RecordArrayType.class, parameters = @Parameter(name = RecordArrayType.RECORD_PARAM, value = "demo.User$EmailView"))
    @Setter(AccessLevel.NONE)
    private List<EmailView> emailMultiset;

    public User(String name) {
        this.name = name;
    }

    public User addPhone(String number) {
        phones.add(new Phone(this, number));
        return this;
    }

    public User addEmail(String address) {
        emails.add(new Email(this, address));
        return this;
    }

    /** Read-only projection of a phone row; components match ROW(p.id, p.number) positionally. */
    public record PhoneView(Long id, String number) {
    }

    /** Read-only projection of an email row; components match ROW(e.id, e.address) positionally. */
    public record EmailView(Long id, String address) {
    }
}
