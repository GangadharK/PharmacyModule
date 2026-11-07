package com.potlatech.pharmacy.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import com.potlatech.pharmacy.common.Specifications;
import com.potlatech.pharmacy.entity.User;
import com.potlatech.pharmacy.repository.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link Specifications} — the optional-filter helpers, tested against a real
 * PostgreSQL database.
 *
 * <p>A real database is the entire point. Both bugs this class was written to
 * prevent are invisible to a unit test: a mock {@code CriteriaBuilder} accepts
 * whatever nonsense the code hands it, and an embedded H2 does not reproduce
 * PostgreSQL's type inference. Hence {@code replace = Replace.NONE} — the
 * default for {@code @DataJpaTest} is to swap in an embedded database, which
 * would silently make the first test below pass for the wrong reason.
 *
 * <ul>
 *   <li><strong>The untyped-null failure.</strong> A JPQL query guarding with
 *       {@code :search is null or …} binds an untyped null, PostgreSQL infers
 *       {@code bytea}, and the query dies with {@code function lower(bytea) does
 *       not exist} — on the request that supplies <em>no</em> filter, which is
 *       the common one.</li>
 *   <li><strong>The OR/AND confusion.</strong> A text filter across two columns
 *       joined with AND only works when the term happens to appear in both. It
 *       reads as correct, tests green on convenient fixtures, and returns
 *       nothing for real queries.</li>
 * </ul>
 *
 * <p>Two things keep this honest against a <em>shared</em> database:
 * <ul>
 *   <li>every test is {@code @Transactional}, so seeded rows roll back;</li>
 *   <li>fixtures use a per-run random token and count assertions use a baseline
 *       delta, so the tests do not care whether the developer's database already
 *       holds seeded users. A test that only passes against an empty database is
 *       a test that gets skipped rather than fixed the next time someone runs it
 *       locally.</li>
 * </ul>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Transactional
class SpecificationsTest {

    @Autowired
    private UserRepository users;

    /**
     * Unique per run, so fixtures never collide with seeded or pre-existing
     * rows. Also used as the search term, which lets these tests assert exact
     * result sets without assuming the table starts empty.
     */
    private final String token = "zz" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 10);

    @Test
    @DisplayName("no filters returns every user")
    void noFiltersReturnsAll() {
        long before = countAll();
        seed(token + "a", "Alpha One", User.Role.CASHIER);
        seed(token + "b", "Beta Two", User.Role.PHARMACIST);
        seed(token + "c", "Gamma Three", User.Role.PHARMACY_MANAGER);

        // The case that used to fail with 'function lower(bytea) does not exist':
        // all three filters absent, so no predicate may reference them at all.
        assertThat(countAll()).isEqualTo(before + 3);

        Page<User> page = users.findAll(
                Specifications.allOf(
                        Specifications.textOr(null, "username", "fullName"),
                        Specifications.eq("role", null),
                        Specifications.eq("isActive", null)),
                PageRequest.of(0, 200));

        assertThat(page.getTotalElements()).isEqualTo(before + 3);
    }

    @Test
    @DisplayName("blank filters are treated as absent, not as an empty match")
    void blankIsTreatedAsAbsent() {
        long before = countAll();
        seed(token + "d", "Delta Four", User.Role.CASHIER);

        Page<User> page = users.findAll(
                Specifications.allOf(
                        Specifications.textOr("", "username", "fullName"),
                        Specifications.textOr("   ", "username")),
                PageRequest.of(0, 200));

        // Clearing the search box must bring the full list back, not an empty
        // table - a blank box is "no filter", not "match the empty string".
        assertThat(page.getTotalElements()).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("a text filter matches EITHER column, not only columns that all match")
    void textFilterIsOrAcrossColumns() {
        seed(token + "match", "Completely Unrelated Name", User.Role.PHARMACIST);
        seed(token + "other", "Also Unrelated", User.Role.PHARMACIST);

        Page<User> page = users.findAll(
                Specifications.textOr(token + "match", "username", "fullName"),
                PageRequest.of(0, 200));

        // The term appears in the username but NOT in the full name. ANDing the
        // two columns returns nothing here - the bug this guards.
        assertThat(page.getContent())
                .extracting(User::getUsername)
                .containsExactly(token + "match");
    }

    @Test
    @DisplayName("a text filter is combined with other filters using AND")
    void textAndNarrowFiltersCombine() {
        seed(token + "one", "Shared Surname", User.Role.PHARMACIST);
        seed(token + "two", "Shared Surname", User.Role.CASHIER);

        Page<User> page = users.findAll(
                Specifications.allOf(
                        Specifications.textOr(token, "username", "fullName"),
                        Specifications.eq("role", User.Role.PHARMACIST)),
                PageRequest.of(0, 200));

        // Both rows match the text; the role narrows it to one.
        assertThat(page.getContent())
                .extracting(User::getUsername)
                .containsExactly(token + "one");
    }

    @Test
    @DisplayName("text search is case-insensitive")
    void textSearchIsCaseInsensitive() {
        seed(token + "case", "Duty " + token, User.Role.PHARMACIST);

        assertThat(find(token.toUpperCase()).getTotalElements()).isEqualTo(1);
        assertThat(find(token).getTotalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("a text filter that matches nothing returns an empty page, not an error")
    void nonMatchingTextReturnsEmpty() {
        assertThat(find(token + "nothing-matches-this").getTotalElements()).isZero();
    }

    private Page<User> find(String search) {
        return users.findAll(Specifications.textOr(search, "username", "fullName"),
                PageRequest.of(0, 200));
    }

    private long countAll() {
        return users.findAll(PageRequest.of(0, 1)).getTotalElements();
    }

    private void seed(String username, String fullName, User.Role role) {
        users.save(User.builder()
                .id(java.util.UUID.randomUUID())
                .username(username)
                .passwordHash("not-a-real-hash")
                .fullName(fullName)
                .role(role)
                .isActive(true)
                .build());
    }
}
