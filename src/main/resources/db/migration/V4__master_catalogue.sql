-- =============================================================================
-- V4 — master catalogue: manufacturers, medicines, non-medical items, suppliers
--
-- Creates the four reference tables of Unit 03. Depends on V1 (users, for the
-- audit convention) and V3 (the lower(name) functional-unique-index pattern,
-- reused here so "Cipla" and "CIPLA" cannot become two manufacturers).
--
-- Three dialect notes, because MDD.md §3.2–3.4 was written while MySQL was
-- still the target and this migration is the PostgreSQL translation of it.
--
-- 1. UNIQUE KEY / KEY / FULLTEXT KEY are MySQL spellings with no PostgreSQL
--    equivalent. Uniques become constraint or functional indexes, ordinary
--    indexes become CREATE INDEX, and FULLTEXT becomes a GIN trigram index.
--    MDD.md has been corrected to match; this migration is the source of truth
--    for what actually runs.
--
-- 2. soundex() does not exist in PostgreSQL core. It arrives with the
--    fuzzystrmatch extension, so that extension is installed here rather than
--    assumed. Both fuzzystrmatch and pg_trgm are TRUSTED extensions, so the
--    database owner can install them without superuser rights — which matters
--    because this project's pharmacy role deliberately lacks CREATEDB.
--    See MDD.md §6 for why both are needed: pg_trgm for the typo-tolerant and
--    prefix tiers, fuzzystrmatch only for the last-resort single-token tier.
--
-- 3. ON UPDATE CURRENT_TIMESTAMP(3) is MySQL. The application sets updated_at
--    in the service layer, per code-standards.md, so there is deliberately no
--    trigger here: a trigger would be a second, invisible writer of a column
--    the service already owns, and the two would disagree the moment a
--    service-side clock is injected in a test.
--
-- search_blob is a denormalised lowercase haystack maintained by the service
-- layer on every write and never accepted from a client. The generated
-- soundex_code is derived from it inside the database, which is why it is
-- declared GENERATED ALWAYS: a client cannot set a column that must agree with
-- its source.
-- =============================================================================

-- Tier 3 of the search cascade (MDD.md §6). Installed before first use, not on
-- a later migration that happens to need it.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- Backs soundex_code below. Returns '' on no match; MedicineMatcher must
-- handle that rather than treating '' as a hit.
CREATE EXTENSION IF NOT EXISTS fuzzystrmatch;


-- -----------------------------------------------------------------------------
-- manufacturer
-- -----------------------------------------------------------------------------
CREATE TABLE manufacturer (
    id             UUID         NOT NULL,
    name           VARCHAR(255) NOT NULL,
    license_number VARCHAR(100),
    country        VARCHAR(100),
    created_at     TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT pk_manufacturer PRIMARY KEY (id)
);

-- A manufacturer is identified by name, and a pharmacist types it however they
-- like. The functional index is what collapses 'Cipla' and 'CIPLA' into one
-- row; a plain UNIQUE on name would not, and the duplicate would surface as two
-- catalogue entries at dispense time. Same reasoning as V3.
CREATE UNIQUE INDEX uk_manufacturer_name ON manufacturer (lower(name));

-- Catalogue lookups filter by name prefix; the trigram index serves that and
-- the fuzzy tier together.
CREATE INDEX idx_manufacturer_name_trgm ON manufacturer USING GIN (name gin_trgm_ops);


-- -----------------------------------------------------------------------------
-- medicine_catalogue
--
-- brand_name is nullable: generics-only entries are the common case in a
-- generics-heavy market, and forcing a brand would mean storing a placeholder
-- string in a NOT NULL column.
-- -----------------------------------------------------------------------------
CREATE TABLE medicine_catalogue (
    id                    UUID         NOT NULL,
    generic_name          VARCHAR(255) NOT NULL,
    brand_name            VARCHAR(255),
    strength              VARCHAR(100),
    dosage_form           VARCHAR(50)  NOT NULL,
    schedule_class        VARCHAR(10)  NOT NULL DEFAULT 'H',
    requires_prescription BOOLEAN      NOT NULL DEFAULT FALSE,
    manufacturer_id       UUID,
    reorder_level         INT          NOT NULL DEFAULT 10,
    is_active             BOOLEAN      NOT NULL DEFAULT TRUE,
    search_blob           VARCHAR(510) NOT NULL,
    soundex_code          VARCHAR(20)  GENERATED ALWAYS AS (soundex(search_blob)) STORED,
    created_at            TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at            TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT pk_medicine_catalogue PRIMARY KEY (id),
    CONSTRAINT fk_med_mfr FOREIGN KEY (manufacturer_id)
        REFERENCES manufacturer (id) ON DELETE SET NULL,
    CONSTRAINT chk_med_form CHECK (dosage_form IN
        ('TABLET', 'SYRUP', 'INJECTION', 'CAPSULE', 'DROPS', 'CREAM', 'OINTMENT', 'INHALER')),
    CONSTRAINT chk_med_schedule CHECK (schedule_class IN ('OTC', 'H', 'H1')),
    CONSTRAINT chk_med_reorder CHECK (reorder_level >= 0)
);

-- ON DELETE SET NULL rather than CASCADE: removing a manufacturer must not
-- delete the medicines dispensed under it. The FDD Schedule H/H1 register is
-- derived from dispensing history, so those rows are audit evidence, not
-- catalogue trivia.

-- Name is not unique. Paracetamol 500mg tablet and Paracetamol 500mg syrup are
-- different rows with the same generic name, and a unique index here would make
-- the second one un-insertable. The (generic_name, dosage_form, strength)
-- shape is disambiguated by the catalogue listing, not by a constraint.
CREATE INDEX idx_med_generic ON medicine_catalogue (lower(generic_name));
CREATE INDEX idx_med_mfr ON medicine_catalogue (manufacturer_id);

-- Typo tolerance and prefix autocomplete over the denormalised haystack.
CREATE INDEX idx_med_search_trgm ON medicine_catalogue USING GIN (search_blob gin_trgm_ops);
CREATE INDEX idx_med_soundex ON medicine_catalogue (soundex_code);


-- -----------------------------------------------------------------------------
-- non_medical_item
--
-- Kept separate from medicine_catalogue rather than a nullable medicine_id on
-- one table: a surgical glove has no schedule_class and no expiry-driven
-- dispensing rule, and merging them would force every FEFO query to carry a
-- discriminator. MDD.md §3.3.
-- -----------------------------------------------------------------------------
CREATE TABLE non_medical_item (
    id            UUID         NOT NULL,
    name          VARCHAR(255) NOT NULL,
    category      VARCHAR(50)  NOT NULL,
    unit          VARCHAR(30)  NOT NULL,
    reorder_level INT          NOT NULL DEFAULT 10,
    is_active     BOOLEAN      NOT NULL DEFAULT TRUE,
    search_blob   VARCHAR(510) NOT NULL,
    soundex_code  VARCHAR(20)  GENERATED ALWAYS AS (soundex(search_blob)) STORED,
    created_at    TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT pk_non_medical_item PRIMARY KEY (id),
    CONSTRAINT chk_item_category CHECK (category IN ('CONSUMABLE', 'EQUIPMENT')),
    CONSTRAINT chk_item_unit CHECK (unit IN ('PIECE', 'BOX', 'PACK', 'PAIR', 'SET')),
    CONSTRAINT chk_item_reorder CHECK (reorder_level >= 0)
);

CREATE INDEX idx_item_name_trgm ON non_medical_item USING GIN (name gin_trgm_ops);
CREATE INDEX idx_item_search_trgm ON non_medical_item USING GIN (search_blob gin_trgm_ops);


-- -----------------------------------------------------------------------------
-- supplier
-- -----------------------------------------------------------------------------
CREATE TABLE supplier (
    id             UUID         NOT NULL,
    name           VARCHAR(255) NOT NULL,
    drug_license_no VARCHAR(100),
    contact_person VARCHAR(255),
    phone          VARCHAR(20),
    email          VARCHAR(255),
    address        VARCHAR(500),
    gst_number     VARCHAR(30),
    is_active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at     TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT pk_supplier PRIMARY KEY (id)
);

-- One supplier per name. A pharmacy buys from a given supplier through one
-- contact; a second row with the same name is a data-entry slip, and two
-- supplier rows for one counterparty split the purchase-order history.
CREATE UNIQUE INDEX uk_supplier_name ON supplier (lower(name));
CREATE INDEX idx_supplier_name_trgm ON supplier USING GIN (name gin_trgm_ops);
