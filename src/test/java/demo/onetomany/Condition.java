package demo.onetomany;

import jakarta.persistence.EnumeratedValue;

/** JPA 3.2: stored as the {@code @EnumeratedValue} field, here a custom code */
public enum Condition {
    NEW(10), USED(20), DAMAGED(30);

    @EnumeratedValue
    private final int code;

    Condition(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}
