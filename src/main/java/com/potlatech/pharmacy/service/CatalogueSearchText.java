package com.potlatech.pharmacy.service;

import java.util.Locale;

/**
 * Builds the lowercase haystack that powers catalogue search.
 *
 * <p>Pure logic: no Spring annotations, no repository, no injected clock.
 * Constructed with {@code new} in its test, per {@code AGENTS.md}.
 *
 * <p>Exists because trigram similarity cannot be indexed across a concatenation
 * of separate columns. {@code similarity(generic_name, 'paracetamoll')} can use
 * an index on {@code generic_name}; {@code similarity(lower(generic_name || ' ' ||
 * coalesce(brand_name, '')), ...)} cannot use an index on either column, so the
 * catalogue search degrades to a sequential scan of every medicine on every
 * keystroke. Flattening the text once on write keeps the index usable.
 *
 * <p>Order matters and is not alphabetical. A pharmacist searching
 * {@code "para"} is typing the start of the <em>generic</em> name, so the
 * generic name leads the blob and a prefix match lands on it. Trigram
 * similarity is symmetric, but a {@code LIKE 'term%'} prefix tier is not.
 */
public final class CatalogueSearchText {

    /**
     * {@code medicine_catalogue.search_blob} and {@code non_medical_item.search_blob}
     * are {@code VARCHAR(510)}. Kept in sync with {@code V4__master_catalogue.sql};
     * a mismatch here would surface as a value-too-long error on the insert that
     * follows a legitimate long catalogue entry.
     */
    public static final int MAX_LENGTH = 510;

    private static final String SEPARATOR = " ";

    private CatalogueSearchText() {
    }

    /**
     * Builds the haystack for a medicine.
     *
     * <p>Null and blank parts are dropped rather than rendered as empty tokens:
     * a trailing separator would let {@code 'para '} match a prefix the user
     * did not type. Parts are lower-cased with {@link Locale#ROOT} — using the
     * default locale would map {@code 'I'} to a dotless {@code ı} in Turkish,
     * making the same catalogue row unfindable on a Turkish-locale server.
     *
     * <p>{@code brandName} is included because a pharmacist may search either
     * name, and the brand is often the only word on the pack they recall.
     *
     * @return the flattened haystack, never null and never blank for a valid
     *         medicine
     */
    public static String forMedicine(String genericName, String brandName, String strength,
                                     String dosageForm) {
        return join(genericName, brandName, strength, dosageForm);
    }

    /**
     * Builds the haystack for a non-medical item.
     *
     * <p>Category and unit are included because "gloves" and "nitrile gloves box"
     * are both things a pharmacist types, and neither is the literal name.
     */
    public static String forItem(String name, String category, String unit) {
        return join(name, category, unit);
    }

    private static String join(String... parts) {
        StringBuilder blob = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            if (!blob.isEmpty()) {
                blob.append(SEPARATOR);
            }
            blob.append(part.trim().toLowerCase(Locale.ROOT));
        }

        String text = blob.toString();
        if (text.length() <= MAX_LENGTH) {
            return text;
        }
        // Truncate on a token boundary rather than mid-word: a blob cut inside
        // "amoxic" produces a trigram set that matches nothing a user types,
        // which is worse than a shorter blob that matches the tail.
        int cut = text.lastIndexOf(' ', MAX_LENGTH);
        if (cut <= 0) {
            // A single token longer than the column: cut it, since it is the
            // name and must be searchable even if its tail is lost.
            return text.substring(0, MAX_LENGTH);
        }
        return text.substring(0, cut);
    }
}
