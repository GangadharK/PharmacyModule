package com.potlatech.pharmacy.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import com.potlatech.pharmacy.dto.catalogue.MedicineResponse;
import com.potlatech.pharmacy.entity.Manufacturer;
import com.potlatech.pharmacy.entity.Medicine;
import com.potlatech.pharmacy.repository.ManufacturerRepository;
import com.potlatech.pharmacy.repository.MedicineRepository;
import com.potlatech.pharmacy.service.MedicineService;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code activeOnly} list filter, tested against a real PostgreSQL database
 * and through the real service.
 *
 * <p>This exists because the filter was <strong>inverted</strong> and every other
 * test still passed. The service read
 * {@code activeOnly == null || activeOnly ? null : eq("isActive", activeOnly)},
 * which dropped the predicate whenever the flag was {@code true} and so returned
 * deactivated rows for a request that explicitly asked for active ones.
 *
 * <p>The contract these tests pin down:
 *
 * <ul>
 *   <li>{@code activeOnly=true}  → active rows only</li>
 *   <li>{@code activeOnly=false} → deactivated rows only</li>
 *   <li>{@code activeOnly=null}  → no predicate, so both</li>
 * </ul>
 *
 * <p>The {@code null} case is not a convenience. The catalogue list screens offer
 * Active / Deactivated / <strong>All</strong>, and a {@code boolean} request
 * parameter with {@code defaultValue = "true"} cannot express the third: the
 * controller substitutes {@code true} for an absent parameter, so "All" silently
 * behaved like "Active". A missing optional filter now means "no restriction",
 * which is what every other filter in this codebase already did.
 *
 * A unit test with a mocked {@code CriteriaBuilder} cannot catch this: the mock
 * accepts whatever predicate it is handed and never evaluates it, so the test
 * asserts only that <em>something</em> was passed. The inversion is a question
 * about which rows come back, which needs rows.
 *
 * <p>Mocked-repository tests are also why it survived review — the expression
 * reads as a null-guard, and it is a null-guard, just attached to the wrong side
 * of the ternary.
 *
 * <p>Fixtures use a per-run random token and the assertions count only the
 * token's own rows, so this does not require an empty database.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Transactional
// @DataJpaTest only scans repositories and entities, so the service under test
// has to be imported explicitly. It has no collaborators beyond the two
// repositories, so there is nothing else to bring in.
@Import(MedicineService.class)
class ActiveOnlyFilterTest {

    @Autowired
    private MedicineService service;

    @Autowired
    private MedicineRepository medicines;

    @Autowired
    private ManufacturerRepository manufacturers;

    private final String token = "zz" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);

    @Test
    @DisplayName("activeOnly=true returns active rows and excludes deactivated ones")
    void activeOnlyTrueExcludesDeactivated() {
        seed(token + "on", true);
        seed(token + "off", false);

        var page = service.list(token, Boolean.TRUE, null, null, null, PageRequest.of(0, 50));

        assertThat(page.content())
                .extracting(MedicineResponse::genericName)
                .containsExactly(token + "on");
    }

    @Test
    @DisplayName("activeOnly=false returns only deactivated rows")
    void activeOnlyFalseReturnsOnlyDeactivated() {
        seed(token + "on", true);
        seed(token + "off", false);

        var page = service.list(token, Boolean.FALSE, null, null, null, PageRequest.of(0, 50));

        assertThat(page.content())
                .extracting(MedicineResponse::genericName)
                .containsExactly(token + "off");
    }

    @Test
    @DisplayName("a null activeOnly applies no filter, so the list screen's All state returns both")
    void nullActiveOnlyReturnsBoth() {
        seed(token + "on", true);
        seed(token + "off", false);

        var page = service.list(token, null, null, null, null, PageRequest.of(0, 50));

        assertThat(page.content())
                .extracting(MedicineResponse::genericName)
                .containsExactlyInAnyOrder(token + "on", token + "off");
    }

    @Test
    @DisplayName("the flag narrows within a search, it does not widen it")
    void activeOnlyComposesWithSearch() {
        seed(token + "alpha", true);
        seed(token + "beta", false);

        // A term that matches nothing must stay empty regardless of the flag -
        // the guard against a filter that widens the result set.
        var page = service.list(token + "gamma", Boolean.TRUE, null, null, null, PageRequest.of(0, 50));

        assertThat(page.content()).isEmpty();
    }

    /**
     * IDs are assigned here, not by Hibernate.
     *
     * AGENTS.md forbids {@code GenerationType.UUID} so the application generates
     * the id, which lets a ledger row reference a batch in the same transaction.
     * The consequence for a test fixture is that {@code save()} on a new entity
     * fails with "identifier must be manually assigned" - the ID has to be set
     * explicitly, exactly as the services do it.
     */
    private void seed(String genericName, boolean active) {
        Manufacturer manufacturer = new Manufacturer();
        manufacturer.setId(UUID.randomUUID());
        // Unique per call: uk_manufacturer_name is case-insensitively unique and
        // every test seeds more than one medicine, so a fixed name collides.
        manufacturer.setName(token + " mfr " + genericName);
        manufacturer.setCountry("LK");
        manufacturers.save(manufacturer);

        Medicine medicine = new Medicine();
        medicine.setId(UUID.randomUUID());
        medicine.setGenericName(genericName);
        medicine.setStrength("500 mg");
        medicine.setDosageForm(Medicine.DosageForm.TABLET);
        medicine.setScheduleClass(Medicine.ScheduleClass.OTC);
        medicine.setManufacturerId(manufacturer.getId());
        medicine.setReorderLevel(0);
        medicine.setIsActive(active);
        medicine.setSearchBlob(genericName.toLowerCase());
        medicines.save(medicine);
    }
}
