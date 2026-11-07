package com.potlatech.pharmacy.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.potlatech.pharmacy.service.CatalogueSearchText;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CatalogueSearchText} — the denormalised search haystack.
 *
 * <p>Pure-function tests, no Spring context. The interesting cases are the ones
 * that silently produce an unsearchable row rather than an error: a blob cut
 * mid-word, a trailing separator, and the Turkish-locale {@code I} trap.
 */
class CatalogueSearchTextTest {

    @Test
    @DisplayName("joins the searchable parts with single spaces, lower-cased")
    void joinsParts() {
        assertThat(CatalogueSearchText.forMedicine("Paracetamol", "Crocin", "500 mg", "TABLET"))
                .isEqualTo("paracetamol crocin 500 mg tablet");
    }

    @Test
    @DisplayName("drops null and blank parts instead of leaving empty tokens")
    void dropsBlankParts() {
        // brandName is null for a generics-only entry - the common case.
        assertThat(CatalogueSearchText.forMedicine("Paracetamol", null, null, "TABLET"))
                .isEqualTo("paracetamol tablet");

        assertThat(CatalogueSearchText.forMedicine("Paracetamol", "   ", "", "TABLET"))
                .isEqualTo("paracetamol tablet");
    }

    @Test
    @DisplayName("leaves no trailing or doubled separator")
    void noStraySeparators() {
        String blob = CatalogueSearchText.forMedicine("  Amoxicillin  ", "  ", "500 mg", " SYRUP ");

        assertThat(blob).isEqualTo("amoxicillin 500 mg syrup");
        assertThat(blob).doesNotContain("  ").doesNotEndWith(" ");
    }

    @Test
    @DisplayName("generic name leads, so a prefix search lands on it")
    void genericNameComesFirst() {
        // A pharmacist typing "para" means the generic, not the brand.
        assertThat(CatalogueSearchText.forMedicine("Paracetamol", "Crocin", "500 mg", "TABLET"))
                .startsWith("paracetamol ");
    }

    @Test
    @DisplayName("lower-cases with a fixed locale, so a Turkish server still finds 'IBUPROFEN'")
    void localeIndependentLowerCasing() {
        // The default-locale trap: in tr-TR, "I".toLowerCase() is a dotless i,
        // so 'IBUPROFEN' would be stored as 'ıbuprofen' and never match a search.
        java.util.Locale previous = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.of("tr", "TR"));

            assertThat(CatalogueSearchText.forMedicine("IBUPROFEN", null, null, "TABLET"))
                    .isEqualTo("ibuprofen tablet");
        } finally {
            java.util.Locale.setDefault(previous);
        }
    }

    @Test
    @DisplayName("truncates on a token boundary, never mid-word")
    void truncatesOnTokenBoundary() {
        String longStrength = "x".repeat(600);

        String blob = CatalogueSearchText.forMedicine("Metronidazole", "Flagyl", longStrength, "TABLET");

        assertThat(blob.length()).isLessThanOrEqualTo(CatalogueSearchText.MAX_LENGTH);
        // The tail that had to go is the oversized token; the searchable head
        // survives intact, which is the whole point of cutting on a boundary.
        // The cut lands *on* the space, so the blob ends at "flagyl" and carries
        // no trailing separator.
        assertThat(blob).startsWith("metronidazole flagyl");
        assertThat(blob).doesNotEndWith(" ");
        assertThat(blob).doesNotContain("xx");
    }

    @Test
    @DisplayName("a single token longer than the column is still searchable at its head")
    void singleOversizedTokenIsHardCut() {
        String name = "a".repeat(600);

        String blob = CatalogueSearchText.forItem(name, "CONSUMABLE", "BOX");

        assertThat(blob).hasSize(CatalogueSearchText.MAX_LENGTH);
        assertThat(blob).isEqualTo("a".repeat(CatalogueSearchText.MAX_LENGTH));
    }

    @Test
    @DisplayName("item blobs include category and unit, which is how they are searched")
    void itemIncludesCategoryAndUnit() {
        assertThat(CatalogueSearchText.forItem("Nitrile Gloves", "CONSUMABLE", "BOX"))
                .isEqualTo("nitrile gloves consumable box");
    }

    @ParameterizedTest(name = "dosage form {0} is searchable")
    @CsvSource({"TABLET", "SYRUP", "INJECTION", "CAPSULE", "DROPS", "CREAM", "OINTMENT", "INHALER"})
    void everyDosageFormIsSearchable(String form) {
        String blob = CatalogueSearchText.forMedicine("Test", null, null, form);

        assertThat(blob).isEqualTo("test " + form.toLowerCase(java.util.Locale.ROOT));
    }
}
