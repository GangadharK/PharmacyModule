package com.potlatech.pharmacy.common;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Composes {@link Specification}s without nesting lambdas.
 *
 * <p><strong>Why this exists.</strong> The obvious way to write an optional
 * filter is a JPQL query full of {@code :x is null or ...} guards. It looks fine
 * and is a trap: when the parameter is null the driver sends an untyped null,
 * PostgreSQL cannot infer a type, and it guesses {@code bytea}. The query then
 * fails at runtime with {@code function lower(bytea) does not exist} — not on
 * the null case you were thinking about, but on the non-null one, because
 * {@code concat('%', :search, '%')} inherits the bad type.
 *
 * <p>That failure is silent until the endpoint is called without a filter, which
 * is the common case, so it survives review and reaches production. Building the
 * predicate in Java instead means Hibernate binds every value with its known Java
 * type and the problem cannot arise.
 *
 * <pre>{@code
 * Page<User> page = users.findAll(
 *         Specifications.allOf(
 *                 Specifications.likeLower("username", search),
 *                 Specifications.likeLower("fullName", search),
 *                 Specifications.eq("role", role),
 *                 Specifications.eq("isActive", active)),
 *         pageable);
 * }</pre>
 *
 * <p>Each helper returns {@code null} when its value is null or blank, and
 * {@link #allOf} drops the nulls, so a filter that was not supplied simply
 * contributes no predicate.
 */
public final class Specifications {

    private Specifications() {
    }

    /**
     * @return a spec matching {@code attribute} case-insensitively on a
     *         contains, or {@code null} if {@code value} is null or blank.
     */
    public static <T> Specification<T> likeLower(String attribute, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String pattern = "%" + value.trim().toLowerCase(java.util.Locale.ROOT) + "%";
        return (root, query, cb) ->
                cb.like(cb.lower(root.get(attribute).as(String.class)), pattern);
    }

    /**
     * @return a spec requiring exact equality, or {@code null} if {@code value}
     *         is null. Used for optional enum and boolean filters.
     */
    public static <T> Specification<T> eq(String attribute, Object value) {
        if (value == null) {
            return null;
        }
        return (root, query, cb) -> cb.equal(root.get(attribute), value);
    }

    /**
     * @return a spec requiring a non-blank value, or {@code null} if absent.
     */
    public static <T> Specification<T> notBlank(String attribute, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return (root, query, cb) -> cb.and(
                cb.isNotNull(root.get(attribute)),
                cb.notEqual(cb.lower(root.<String>get(attribute)), ""));
    }

    /**
     * Combines specs, ignoring nulls.
     *
     * @return {@code null} when every input is null, which Spring Data treats as
     *         "no restriction" — the correct result, since a request that
     *         supplied no filters should return everything.
     */
    @SafeVarargs
    public static <T> Specification<T> allOf(Specification<T>... specs) {
        List<Specification<T>> present = new ArrayList<>();
        for (Specification<T> spec : specs) {
            if (spec != null) {
                present.add(spec);
            }
        }
        if (present.isEmpty()) {
            return null;
        }
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>(present.size());
            for (Specification<T> spec : present) {
                Predicate predicate = spec.toPredicate(root, query, cb);
                if (predicate != null) {
                    predicates.add(predicate);
                }
            }
            return predicates.size() == 1
                    ? predicates.get(0)
                    : cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    /** Combines specs that are already in a collection, ignoring nulls. */
    public static <T> Specification<T> allOf(Collection<Specification<T>> specs) {
        return allOf(specs.toArray(new Specification[0]));
    }

    /**
     * Combines specs with OR, ignoring nulls.
     *
     * <p>For a text box that should match <em>several</em> columns — a user
     * searching "newph" expects the account whose username is {@code newpharm}
     * to appear, even though their full name "New Pharmacist" does not contain
     * that substring. Using {@link #allOf} for a multi-column text search is a
     * silent bug: it works for queries that happen to match every column at once
     * and returns nothing for the rest, so it looks like a flaky search rather
     * than a wrong boolean.
     *
     * <p>Combine this with {@code allOf} for the usual shape: OR across the text
     * columns, AND against the other filters.
     *
     * <pre>{@code
     * Specifications.allOf(
     *         Specifications.anyOf(
     *                 Specifications.likeLower("username", search),
     *                 Specifications.likeLower("fullName", search)),
     *         Specifications.eq("role", role))
     * }</pre>
     *
     * @return {@code null} when every input is null
     */
    @SafeVarargs
    public static <T> Specification<T> anyOf(Specification<T>... specs) {
        List<Specification<T>> present = new ArrayList<>();
        for (Specification<T> spec : specs) {
            if (spec != null) {
                present.add(spec);
            }
        }
        if (present.isEmpty()) {
            return null;
        }
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>(present.size());
            for (Specification<T> spec : present) {
                Predicate predicate = spec.toPredicate(root, query, cb);
                if (predicate != null) {
                    predicates.add(predicate);
                }
            }
            return predicates.size() == 1
                    ? predicates.get(0)
                    : cb.or(predicates.toArray(Predicate[]::new));
        };
    }

    /**
     * Convenience for the common "free text across N columns, then narrow by
     * other filters" shape.
     */
    public static <T> Specification<T> textOr(String search, String... attributes) {
        if (search == null || search.isBlank()) {
            return null;
        }
        Specification<T>[] specs = new Specification[attributes.length];
        for (int i = 0; i < attributes.length; i++) {
            specs[i] = likeLower(attributes[i], search);
        }
        return anyOf(specs);
    }
}
