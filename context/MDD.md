# Technical Design Document (MDD)
## Hospital-Specific Pharmacy Management System (PMS)

**Version:** 2.0
**Date:** September 2026
**Status:** Active — reconciled with implementation sign-off

> **Change log v1.0 → v2.0 → v2.1**
> - **Database: PostgreSQL only** (decision 22, Sept 2026). v2.0 had selected MySQL 8.0.16+ as the production engine and carried a parallel Flyway tree for it. Maintaining two engines proved more expensive than it was worth — the same column was reported as a different JDBC type by each, so migrations were being contorted to satisfy a parity test rather than to satisfy the database. One engine, one migration tree.
> - **Spring Boot: 3.x → 4.0.8.** Matches the parent `practi` HMS service. All 4.x breaking changes apply.
> - **Java: 17 → 21.**
> - **v1 scope narrowed** to three features: Master Catalogue, Batch Inventory + FEFO, Purchase Entry + OCR. See §8.
> - **HMS prescription integration is a stubbed Feign client** in v1; the upstream endpoint does not yet exist in `practi`.
> - Filled in previously undefined `users` and `supplier` tables (v1 referenced them via FK but never defined them).

---

## 1. Open-Source Technology Stack

| Layer | Technology | Justification |
| :--- | :--- | :--- |
| **Frontend** | React 19, Vite, TypeScript, Tailwind CSS, shadcn/ui (Radix), Zustand, React Hook Form + Zod, AG Grid Community | Mirrors the reference project's proven frontend stack (see §9). Zustand over React Query — see §9.4. |
| **Backend** | Java 21, Spring Boot 4.0.8 | Robust, enterprise-grade, aligned with the parent HMS service. |
| **Database** | **PostgreSQL 16** | The only engine (decision 22). Also what the parent `practi` HMS runs on, so the two services share one operational skill set. |
| **Search** | `pg_trgm` (fuzzy) + `tsvector`/GIN (full-text) + `soundex()` | In-engine, no separate search server. See §6. |
| **Migrations** | Flyway | Versioned, reviewable schema history. Replaces `ddl-auto` for anything beyond local prototyping. |
| **Caching** | Redis (via Spring Data Redis) | Caching catalogue lookups and FEFO query results. Optional — app degrades gracefully if Redis is down. |
| **OCR Engine** | Tesseract OCR (via **Tess4J** Java wrapper) | Free, open-source OCR for extracting text from supplier invoice images. |
| **PDF Generation** | OpenPDF | Free, open-source invoice and report generation. |
| **Notifications** | Novu (open-source) or WhatsApp Cloud API | Email/SMS/WhatsApp templates without vendor lock-in. Deferred past v1. |
| **API Integration** | Spring Cloud OpenFeign | Type-safe, declarative REST client for fetching data from the main HMS. |
| **API Docs** | springdoc-openapi 3.x + Swagger UI | Already in the scaffold `pom.xml`. |

### 1.1 Dependencies removed from the v1 stack

| Dropped | Reason |
| :--- | :--- |
| `spring-boot-starter-batch` | Present in the scaffold but no batch job is specified in the FDD. Removed to keep the dependency surface honest. |
| `spring-boot-starter-amqp` | No async broker requirement in the FDD. Removed. |

### 1.2 Dependencies to add

| Add | Reason | Unit |
| :--- | :--- | :--- |
| `spring-boot-starter-validation` | Bean Validation on all request DTOs. | 01 |
| `spring-boot-starter-data-redis` | Catalogue + FEFO caching. | 01 |
| `spring-boot-flyway` + `flyway-core` + `flyway-database-postgresql` | Versioned migrations. All three are needed — see §3.11. | 01 |
| `spring-cloud-starter-openfeign` | HMS prescription client. | 10 |
| `net.sourceforge.tess4j:tess4j` | OCR pipeline. | 10 |
| `com.github.librepdf:openpdf` | Invoice PDF. | Deferred (billing not in v1). |
| `org.postgresql:postgresql` | JDBC driver. | 01 |

## 2. High-Level System Architecture

```text
[ React Frontend  :5163 ]
       │ (HTTPS/REST, JWT Bearer)
       ▼
[ Spring Boot Application (PMS) :8081 ]
       ├──▶ [ PostgreSQL 16 :5432/pharmacy ]  (Primary Data Store + trigram/FTS search)
       ├──▶ [ Redis :6379 ]              (Caching, optional — degrades gracefully)
       ├──▶ [ Local File System ]         (Store Invoice Images & Generated PDFs)
       └──(OpenFeign)──▶ [ practi HMS API :8080 ]  ← STUBBED in v1, see §5.3
```

`practi` (the HMS) runs on port 8080 against PostgreSQL db `practice`. `Pharmacy` runs on port **8081** against PostgreSQL db `pharmacy`. Both are PostgreSQL but they remain **separate databases** — the PMS never joins against HMS tables directly, only through the Feign client. This is what makes the PMS runnable standalone (FDD §1).

## 3. Core Database Schema (vendor-neutral logical model)

> Sections 3.1–3.8 are the **logical model**: columns, keys, constraints and indexes, written in a neutral type notation. They are the source of truth. **Section 3.9 maps every type to its PostgreSQL form**, and that mapping is the authority for writing migrations.
>
> The DDL lives in a single Flyway tree, `src/main/resources/db/migration/`.

### 3.0 Conventions

| Rule | Value | Reason |
| :--- | :--- | :--- |
| Primary key | `UUID`, native `uuid` | Hibernate maps `java.util.UUID` directly. No converter, no `columnDefinition`. |
| FK column type | `UUID`, always `UUID` | Must match the referenced PK type exactly, or the FK is rejected. |
| Money | `DECIMAL(10,2)` → `numeric(10,2)` | Never `FLOAT`/`DOUBLE`. |
| Timestamps | `TIMESTAMP(3)` → `timestamp(3)`, **set from Java** | Millisecond precision for audit ordering. Not timezone-aware — `timestamp without time zone` — so the session runs UTC. |
| Soft delete | `deleted_at TIMESTAMP(3) NULL` on master data only | Master data is referenced historically by stock transactions. |
| Case-insensitive text | `varchar` + `UNIQUE` index on `lower(col)` | Not the `citext` extension: it reports as `Types#OTHER` and breaks `ddl-auto: validate`. Queries must use `lower(col) = lower(:param)`. |
| DDL location | `db/migration/V__*.sql` (Flyway) | Never `ddl-auto=update` outside local scratch. |

> **`CHECK` constraints are enforced** by PostgreSQL, so `quantity_available >= 0` genuinely holds. This was the one semantic that needed a version floor under MySQL and is now unconditional.
>
> `ddl-auto: validate` is set, so a migration and the entity must agree exactly. A mismatch fails at boot, not at first use — which is why every change is verified by starting the application against an empty database.

### 3.1 Identity & Audit Actor

```sql
CREATE TABLE users (
    id              UUID   NOT NULL,
    username        VARCHAR(100) NOT NULL,
    password_hash   VARCHAR(255) NOT NULL,          -- BCrypt
    full_name       VARCHAR(255) NOT NULL,
    role            VARCHAR(30)  NOT NULL,          -- SUPER_ADMIN, PHARMACY_MANAGER,
                                                    -- PHARMACIST, CASHIER
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    last_login_at   TIMESTAMP(3)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_username (username),
    CONSTRAINT chk_users_role CHECK (role IN
        ('SUPER_ADMIN','PHARMACY_MANAGER','PHARMACIST','CASHIER'))
);

-- Note: FDD §2 lists Nurse/Ward Staff as a role, but they raise requisitions
-- *inside* the HMS and never authenticate to the PMS. Deliberately excluded.

CREATE TABLE refresh_token (
    id           UUID   NOT NULL,
    user_id      UUID   NOT NULL,
    token_hash   CHAR(64)     NOT NULL,            -- SHA-256 hex of the opaque token
    expires_at   TIMESTAMP(3)  NOT NULL,
    revoked_at   TIMESTAMP(3)  NULL,                -- set on logout or on rotation
    replaced_by  UUID   NULL,                -- rotation chain: what this became
    created_at   TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    user_agent   VARCHAR(255) NULL,
    ip_address   VARCHAR(45)  NULL,                -- 45 = max IPv6 length
    PRIMARY KEY (id),
    UNIQUE KEY uk_refresh_token_hash (token_hash),
    KEY idx_refresh_user (user_id),
    KEY idx_refresh_expiry (expires_at),
    CONSTRAINT fk_refresh_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE
);
```

**Why the refresh token is opaque and stored hashed.** A JWT refresh token is self-validating and unrevokable — whoever holds it can use it until it expires, and there is no way to invalidate it on logout. An opaque random token, stored as a SHA-256 hash so a database dump does not yield usable credentials, is revocable. `revoked_at` handles logout; `replaced_by` chains each rotation so reuse of an already-rotated token can be detected and the whole family revoked.

The refresh token travels in an **HttpOnly, SameSite=Strict, Secure cookie**. The access token stays in memory only, per `ui-context.md` §8 — never `localStorage`, never `sessionStorage`.

`refresh_token` is **append-only in practice**: rows are inserted on issue and updated only to set `revoked_at`/`replaced_by`. They are never hard-deleted, because the rotation chain is the audit trail. A periodic cleanup job may delete rows expired more than 90 days ago; that is the only exception.
```

### 3.2 Master Catalogue — Medicines

```sql
CREATE TABLE manufacturer (
    id              UUID   NOT NULL,
    name            VARCHAR(255) NOT NULL,
    license_number  VARCHAR(100) NULL,
    country         VARCHAR(100) NULL,
    created_at      TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_manufacturer_name (name)
);

CREATE TABLE medicine_catalogue (
    id                    UUID   NOT NULL,
    generic_name          VARCHAR(255) NOT NULL,
    brand_name            VARCHAR(255) NULL,
    strength              VARCHAR(100) NULL,       -- '500 mg'
    dosage_form           VARCHAR(50)  NOT NULL,   -- TABLET, SYRUP, INJECTION, CAPSULE, DROPS, CREAM
    schedule_class        VARCHAR(10)  NOT NULL DEFAULT 'H',  -- OTC, H, H1
    requires_prescription BOOLEAN      NOT NULL DEFAULT FALSE,
    manufacturer_id       UUID   NULL,
    reorder_level         INT          NOT NULL DEFAULT 10,
    is_active             BOOLEAN      NOT NULL DEFAULT TRUE,
    -- Denormalised lowercase haystack. Maintained by the service layer on every
    -- write; never written by clients. Powers the FULLTEXT index in §6.
    search_blob           VARCHAR(510) NOT NULL,
    soundex_code          VARCHAR(20)  GENERATED ALWAYS AS (SOUNDEX(search_blob)) STORED,
    created_at            TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at            TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                            ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_med_mfr (manufacturer_id),
    FULLTEXT KEY ft_med_search (search_blob),
    CONSTRAINT fk_med_mfr FOREIGN KEY (manufacturer_id)
        REFERENCES manufacturer (id),
    CONSTRAINT chk_med_form CHECK (dosage_form IN
        ('TABLET','SYRUP','INJECTION','CAPSULE','DROPS','CREAM','OINTMENT','INHALER')),
    CONSTRAINT chk_med_schedule CHECK (schedule_class IN ('OTC','H','H1'))
);
```

`brand_name` is nullable: generics-only entries are common. `schedule_class` encodes the FDD §3.5 "Schedule H/H1 Register" requirement — the register becomes a `WHERE schedule_class <> 'OTC'` filtered view of dispensing transactions rather than a separate pipeline.

### 3.3 Master Catalogue — Non-Medical Items

FDD §3.1 requires consumables and equipment to live in a *separate but linked* catalogue. Kept as a distinct table rather than a nullable `medicine_id` on one table — an item never has a batch-expiry lifecycle difference but does differ in that it has no `schedule_class`, and keeping them apart makes the FEFO index on `inventory_batch` unambiguous.

```sql
CREATE TABLE non_medical_item (
    id            UUID   NOT NULL,
    name          VARCHAR(255) NOT NULL,
    category      VARCHAR(50)  NOT NULL,   -- CONSUMABLE, EQUIPMENT
    unit          VARCHAR(30)  NOT NULL,   -- PIECE, BOX, PAIR
    reorder_level INT          NOT NULL DEFAULT 10,
    is_active     BOOLEAN      NOT NULL DEFAULT TRUE,
    search_blob   VARCHAR(510) NOT NULL,
    soundex_code  VARCHAR(20)  GENERATED ALWAYS AS (SOUNDEX(search_blob)) STORED,
    created_at    TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                  ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    FULLTEXT KEY ft_item_search (search_blob),
    CONSTRAINT chk_item_category CHECK (category IN ('CONSUMABLE','EQUIPMENT'))
);
```

### 3.4 Suppliers

```sql
CREATE TABLE supplier (
    id               UUID   NOT NULL,
    name             VARCHAR(255) NOT NULL,
    drug_license_no  VARCHAR(100) NULL,   -- FDD §3.1 regulatory license numbers
    contact_person   VARCHAR(255) NULL,
    phone            VARCHAR(20)  NULL,
    email            VARCHAR(255) NULL,
    address          VARCHAR(500) NULL,
    gst_number       VARCHAR(30)  NULL,
    is_active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at       TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                     ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_supplier_name (name)
);
```

### 3.5 Inventory Batch — the core of FEFO

```sql
CREATE TABLE inventory_batch (
    id                 UUID   NOT NULL,
    medicine_id        UUID   NULL,   -- exactly one of medicine_id / item_id
    item_id            UUID   NULL,
    supplier_id        UUID   NULL,
    batch_number       VARCHAR(100) NOT NULL,
    manufacturing_date DATE         NULL,
    expiry_date        DATE         NOT NULL,
    quantity_received  INT          NOT NULL DEFAULT 0,
    quantity_available INT          NOT NULL DEFAULT 0,
    cost_price         DECIMAL(10,2) NOT NULL,
    selling_price      DECIMAL(10,2) NOT NULL,
    location           VARCHAR(30)  NOT NULL DEFAULT 'MAIN_PHARMACY',
    status             VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at         TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at         TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                       ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_fefo_search (medicine_id, status, expiry_date, location),
    KEY idx_item_fefo   (item_id, status, expiry_date, location),
    KEY idx_near_expiry (expiry_date, status),
    KEY idx_batch_lookup (batch_number),
    CONSTRAINT fk_batch_medicine FOREIGN KEY (medicine_id)
        REFERENCES medicine_catalogue (id),
    CONSTRAINT fk_batch_item FOREIGN KEY (item_id)
        REFERENCES non_medical_item (id),
    CONSTRAINT fk_batch_supplier FOREIGN KEY (supplier_id)
        REFERENCES supplier (id),
    CONSTRAINT chk_batch_qty CHECK (quantity_available >= 0),
    CONSTRAINT chk_batch_polymorphic CHECK (
        (medicine_id IS NOT NULL AND item_id IS NULL) OR
        (medicine_id IS NULL AND item_id IS NOT NULL)),
    CONSTRAINT chk_batch_status CHECK (status IN
        ('ACTIVE','QUARANTINED','EXPIRED','DEPLETED')),
    CONSTRAINT chk_batch_location CHECK (location IN
        ('MAIN_PHARMACY','OPD_COUNTER','EMERGENCY','ICU'))
);
```

**The FEFO index uses a partial index.** PostgreSQL supports these, so the predicate is enforced by the index rather than left as a residual filter:

```sql
CREATE INDEX idx_fefo_search ON inventory_batch (medicine_id, expiry_date ASC)
WHERE status = 'ACTIVE' AND quantity_available > 0;
```

This is a real improvement over what the MySQL-era schema settled for. That plan had to use a plain composite index `(medicine_id, status, expiry_date, location)` and apply `quantity_available > 0 AND status = 'ACTIVE'` as a residual predicate, because MySQL cannot express a partial index — so depleted and quarantined rows were physically interleaved with live ones and the FEFO scan had to filter them row by row. Here the index contains only rows that can actually be dispensed, so the FEFO loop in §4.1 is a single index range scan over candidates in ascending expiry order, with no filtering and no in-memory sort.

`location` is deliberately **not** in the index: the FEFO query filters on it but does not order by it, and including it would widen the key without helping the ordering.

`status = 'DEPLETED'` is a **derived** value: the service layer sets it when `quantity_available` reaches 0. It exists so that alert and valuation queries do not have to scan zero-quantity rows.

### 3.6 Stock Transaction — the audit trail

```sql
CREATE TABLE stock_transaction (
    id                UUID   NOT NULL,
    batch_id          UUID   NOT NULL,
    transaction_type  VARCHAR(30)  NOT NULL,
    quantity_changed  INT          NOT NULL,   -- signed: +in, -out
    reference_type    VARCHAR(30)  NULL,       -- PURCHASE_ORDER, INVOICE, RETURN, CYCLE_COUNT
    reference_id      UUID   NULL,
    note              VARCHAR(500) NULL,
    performed_by      UUID   NULL,       -- NULL for system-initiated expiry sweeps
    created_at        TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_txn_batch (batch_id, created_at),
    KEY idx_txn_ref   (reference_type, reference_id),
    KEY idx_txn_time  (created_at),
    CONSTRAINT fk_txn_batch FOREIGN KEY (batch_id)
        REFERENCES inventory_batch (id),
    CONSTRAINT fk_txn_user FOREIGN KEY (performed_by)
        REFERENCES users (id),
    CONSTRAINT chk_txn_type CHECK (transaction_type IN
        ('PURCHASE_IN','SALE_OUT','RETURN_RESTOCK','DAMAGE_WRITE_OFF',
         'EXPIRY_WRITE_OFF','TRANSFER_OUT','TRANSFER_IN','CYCLE_COUNT_ADJUST',
         'QUARANTINE','RELEASE'))
);
```

`quantity_changed` is **signed**, not an unsigned magnitude. This is the single most important audit property in the system: MDD §5 states *"Every UPDATE or DELETE on `inventory_batch` must be logged in `stock_transaction`. Direct SQL updates are forbidden."* A signed delta means the ledger can be reconciled against the balance — `SUM(quantity_changed) GROUP BY batch_id` must equal `inventory_batch.quantity_available` for every batch, and that reconciliation is a single query (see §7.2). An unsigned column would force every consumer to re-derive the direction from the type enum.

**Invariants enforced at the schema level:**
1. `SUM(stock_transaction.quantity_changed) == inventory_batch.quantity_available` per batch.
2. `quantity_available >= 0` — enforced by `CHECK`. This is what makes concurrent dispensing safe without pessimistic locking: two transactions both reading 5 and both trying to take 5, the second one's `UPDATE ... WHERE quantity_available >= 5` matches zero rows and is rolled back.
3. No `DELETE` on `inventory_batch` — only `status` transitions to `DEPLETED`/`EXPIRED`.

### 3.7 Purchase Order

Covers both manual entry (FDD §3.3) and the OCR draft (MDD §4.2). One table, distinguished by `status`.

```sql
CREATE TABLE purchase_order (
    id                 UUID   NOT NULL,
    po_number          VARCHAR(50)  NOT NULL,
    supplier_id        UUID   NOT NULL,
    status             VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    source             VARCHAR(20)  NOT NULL DEFAULT 'MANUAL',  -- MANUAL, OCR
    supplier_invoice_no VARCHAR(100) NULL,
    invoice_image_path VARCHAR(500) NULL,       -- OCR source upload
    ocr_raw_text       MEDIUMTEXT   NULL,       -- Tesseract output, for re-parse
    subtotal           DECIMAL(12,2) NOT NULL DEFAULT 0,
    tax_amount         DECIMAL(12,2) NOT NULL DEFAULT 0,
    grand_total        DECIMAL(12,2) NOT NULL DEFAULT 0,
    approved_by        UUID   NULL,
    approved_at        TIMESTAMP(3)  NULL,
    created_by         UUID   NOT NULL,
    created_at         TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at         TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                       ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_po_number (po_number),
    KEY idx_po_status (status, created_at),
    CONSTRAINT fk_po_supplier FOREIGN KEY (supplier_id)
        REFERENCES supplier (id),
    CONSTRAINT fk_po_approver FOREIGN KEY (approved_by)
        REFERENCES users (id),
    CONSTRAINT fk_po_creator FOREIGN KEY (created_by)
        REFERENCES users (id),
    CONSTRAINT chk_po_status CHECK (status IN
        ('DRAFT','DRAFT_OCR','PENDING_APPROVAL','APPROVED','CANCELLED')),
    CONSTRAINT chk_po_source CHECK (source IN ('MANUAL','OCR'))
);

CREATE TABLE purchase_order_item (
    id                 UUID   NOT NULL,
    purchase_order_id  UUID   NOT NULL,
    line_no            INT          NOT NULL,
    medicine_id        UUID   NULL,
    item_id            UUID   NULL,
    batch_number       VARCHAR(100) NULL,   -- NULL when OCR could not read it
    manufacturing_date DATE         NULL,
    expiry_date        DATE         NULL,
    quantity           INT          NOT NULL,
    cost_price         DECIMAL(10,2) NOT NULL,
    selling_price      DECIMAL(10,2) NOT NULL,
    -- 0..1. Below 0.6 the UI flags the line for mandatory human review
    -- before approval. This is the human-in-the-loop guard from MDD §4.2.
    ocr_confidence     DECIMAL(4,3) NULL,
    is_corrected       BOOLEAN      NOT NULL DEFAULT FALSE,  -- set by pharmacist
    PRIMARY KEY (id),
    KEY idx_poi_po (purchase_order_id),
    CONSTRAINT fk_poi_po FOREIGN KEY (purchase_order_id)
        REFERENCES purchase_order (id) ON DELETE CASCADE,
    CONSTRAINT fk_poi_medicine FOREIGN KEY (medicine_id)
        REFERENCES medicine_catalogue (id),
    CONSTRAINT fk_poi_item FOREIGN KEY (item_id)
        REFERENCES non_medical_item (id),
    CONSTRAINT chk_poi_qty CHECK (quantity > 0)
);
```

**State machine** (enforced in the service layer. No `CHECK` constraint can express a transition, on any engine, because a constraint sees one row and a transition is a relationship between two:

```text
DRAFT ─────approve────▶ PENDING_APPROVAL ─────approve────▶ APPROVED  (creates inventory_batch rows)
  │                          │                                   │
  └──cancel──▶ CANCELLED ◀───┘                                   └──▶ APPROVED (terminal)
DRAFT_OCR ──(pharmacist edits)──▶ PENDING_APPROVAL
```

Only `APPROVED` writes to `inventory_batch`. This is the human-in-the-loop guarantee from MDD §4.2: OCR never mutates stock directly.

### 3.8 Invoices (schema only — billing is deferred past v1)

Retained in the MDD because the FEFO allocation output in §4.1 is designed to be consumed by it, and because the FDD mandates the schema. **No billing unit is planned in v1** (see §8).

```sql
CREATE TABLE invoice (
    id                UUID   NOT NULL,
    invoice_number    VARCHAR(50)  NOT NULL,
    patient_uhid      VARCHAR(100) NULL,   -- from HMS
    appointment_id    VARCHAR(100) NULL,   -- from HMS
    billing_type      VARCHAR(20)  NOT NULL,   -- OPD_CASH, IPD_LEDGER
    sub_total         DECIMAL(12,2) NOT NULL DEFAULT 0,
    discount_amount   DECIMAL(12,2) NOT NULL DEFAULT 0,
    tax_amount        DECIMAL(12,2) NOT NULL DEFAULT 0,
    grand_total       DECIMAL(12,2) NOT NULL DEFAULT 0,
    payment_mode      VARCHAR(20)  NULL,   -- CASH, CARD, UPI
    created_by        UUID   NOT NULL,
    created_at        TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_invoice_number (invoice_number),
    KEY idx_invoice_uhid (patient_uhid),
    CONSTRAINT fk_invoice_user FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT chk_invoice_type CHECK (billing_type IN ('OPD_CASH','IPD_LEDGER'))
);

CREATE TABLE invoice_item (
    id                 UUID   NOT NULL,
    invoice_id         UUID   NOT NULL,
    medicine_id        UUID   NULL,
    item_id            UUID   NULL,
    batch_id           UUID   NOT NULL,   -- FEFO already resolved
    quantity           INT          NOT NULL,
    unit_price         DECIMAL(10,2) NOT NULL,
    line_total         DECIMAL(12,2) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_invitem_invoice (invoice_id),
    KEY idx_invitem_batch (batch_id),
    CONSTRAINT fk_invitem_invoice FOREIGN KEY (invoice_id)
        REFERENCES invoice (id) ON DELETE CASCADE,
    CONSTRAINT fk_invitem_batch FOREIGN KEY (batch_id)
        REFERENCES inventory_batch (id)
);
```

`invoice_item.batch_id` is mandatory, not nullable. FEFO can split one requested line across several batches (MDD §4.1), so **one invoice line may produce several `invoice_item` rows**, each pinned to the batch it actually came from. That is what makes the stock ledger reconcilable against revenue.

### 3.9 Physical types (authoritative for writing migrations)

**PostgreSQL 16 is the only engine** (decision 22). Sections 3.1–3.8 are written
as a vendor-neutral logical model; this table is the single place that maps it to
real DDL.

| Logical | PostgreSQL 16 | Notes |
| :--- | :--- | :--- |
| `UUID` | `uuid` | Generate with `gen_random_uuid()`. Hibernate maps `java.util.UUID` natively — no converter, no `columnDefinition`. |
| `VARCHAR(n)` | `varchar(n)` | — |
| `BOOLEAN` | `boolean` | — |
| `DECIMAL(10,2)` | `numeric(10,2)` | Money. Never `Double`, never `float`. |
| `TIMESTAMP(3)` | `timestamp(3)` | **Not timezone-aware.** `timestamp` is an alias for `timestamp without time zone`; only `timestamptz` differs, and is deliberately not used. A `LocalDateTime` written in one zone and read in another is a *different instant of wall-clock time*, silently. The dev profile sets `hibernate.jdbc.time_zone=UTC`. |
| `DATE` | `date` | Expiry and manufacturing dates. |
| `TEXT` | `text` | — |
| Fixed-width string | `varchar(n)` | **Not `char(n)`.** V1 used `CHAR(64)` for `token_hash`; it reported to Hibernate as `bpchar` and `ddl-auto: validate` refused to start. Fixed to `varchar(64)` by V2. `CHAR` buys nothing for a value of known length. |
| `DEFAULT CURRENT_TIMESTAMP(3)` | `DEFAULT now()` | Prefer setting from Java so behaviour does not depend on the column definition. |
| Auto-update timestamp | — | **Do not add one.** Application code owns `updated_at`. |
| Case-insensitive compare | `varchar` + `UNIQUE` index on `lower(col)` | **Not the `citext` extension.** `citext` reports to JDBC as `Types#OTHER`, so Hibernate cannot map a `String` onto it and validation fails. Queries must use `lower(col) = lower(:param)` so the index is used. |
| Enum-ish column | `varchar` + `CHECK` | Deliberately not a native `ENUM`: adding a value is a schema change, and `CHECK` is clearer about what is allowed. |
| `Soundex` | `soundex()` | Returns `''` on no match. `MedicineMatcher` must handle that. Unit 10. |
| Full-text search | `tsvector` + GIN, `plainto_tsquery` / `websearch_to_tsquery` | Unit 03. |
| Row lock for FEFO | `SELECT … FOR UPDATE` | Required. `AGENTS.md` rule 3. |

### 3.10 Migration layout and the schema gate

One tree:

```
src/main/resources/db/migration/
├── V1__create_identity.sql
├── V2__token_hash_varchar.sql
└── V3__username_case_insensitive.sql
```

`spring.flyway.locations` points at `classpath:db/migration`. Migrations are
append-only: never edit an applied one, write a new one.

**The real gate is booting the app, not reading the SQL.** `ddl-auto: validate`
makes Hibernate compare every entity mapping against the live schema, so a
correctness mistake in a migration stops the application from starting rather
than surfacing later as wrong data. A text-level test cannot do this, which is
worth remembering given that a text-level parity test existed here until
decision 22 and never found either of the two real defects — those were found by
booting against each engine.

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

A successful start means Flyway applied the chain *and* validation accepted the
entities against it.

`MigrationGuardTest` covers what a boot cannot: that migration versions are
contiguous, that every migration carries a header stating its dependencies, and
that **no migration contains a credential** (decision 20 — seeding a
`SUPER_ADMIN` with a published password in a migration creates a standing
remote-takeover on every deploy). That last check is mutation-verified: inserting
a BCrypt hash into a migration makes it fail. A guard that cannot fail is not a
guard.

### 3.11 Boot-time failure modes worth knowing

| Symptom | Cause | Fix |
| :--- | :--- | :--- |
| App boots, no Flyway log line ever appears, no schema is created | `flyway-core` is on the classpath but the Spring Boot 4 auto-configuration module is not. Boot 4 moved auto-configuration out of `spring-boot-autoconfigure` into one module per technology, so Flyway registers nothing at all. | Add `org.springframework.boot:spring-boot-flyway`. This one does not fail loudly, which is what makes it expensive. |
| `Schema validation: wrong column type ... found [bpchar] but expecting [varchar(64)]` | `CHAR(n)` on PostgreSQL. | Use `varchar(n)`. |
| `Schema validation: wrong column type ... found [citext (Types#OTHER)]` | The `citext` extension. | Use `varchar` plus a `lower()` unique index. |
| `Validate failed: Detected failed migration to version N` | A multi-statement migration failed partway, leaving the schema and `flyway_schema_history` disagreeing. | Fix the statement, then `flyway repair`. With no real data, dropping and recreating the database to re-run the chain is faster and proves more. |
| `permission denied to create database` | Recreating the dev database as the `pharmacy` role, which has no `CREATEDB`. | Use a superuser role to create it, then grant ownership to `pharmacy`. |

---

## 4. Key Technical Workflows

### 4.1 The FEFO (First Expired, First Out) Allocation Algorithm

This is the core algorithm of the system and the only genuinely non-trivial piece of logic in v1.

```text
Input:  medicineId, requestedQuantity, location
Output: List<Allocation>  (batchId, batchNumber, expiryDate, quantity, unitPrice)
        — or an exception if totalAvailable < requestedQuantity

1.  SELECT id, batch_number, expiry_date, quantity_available, selling_price
    FROM inventory_batch
    WHERE medicine_id = ? AND location = ?
      AND status = 'ACTIVE' AND quantity_available > 0
    ORDER BY expiry_date ASC
    FOR UPDATE;                        -- pessimistic lock, see note below
       │
2.  Walk the result set, accumulating allocations until
    requestedQuantity is satisfied. Short-circuit and return the
    remaining shortfall when the walk exhausts the result set.
       │
3.  The caller (in a @Transactional method) applies each allocation:
       UPDATE inventory_batch
          SET quantity_available = quantity_available - :qty
        WHERE id = :batchId AND quantity_available >= :qty;
       INSERT INTO stock_transaction (... 'SALE_OUT', -:qty ...);
    If any UPDATE reports 0 rows affected → throw → the whole
    transaction rolls back, so a partial dispense is impossible.
```

**Why `FOR UPDATE`.** The v1 draft relied on the `CHECK (quantity_available >= 0)` constraint alone. That is insufficient on any engine: two concurrent dispensing transactions can both read `quantity_available = 5`, both attempt to take 5, and the second's `UPDATE` is serialised behind the first's only at write time — by which point both have already decided they can fulfil the request. The `CHECK` then fires for one of them, but the failure arrives as a constraint violation after the work was done, and read-then-decide has already committed the application to a decision that is now wrong. `SELECT ... FOR UPDATE` takes an exclusive row lock on the scanned range up front, serialising the two transactions at the **read**, so the second transaction reads the post-dispense quantity and never attempts an impossible allocation.

This is the correct trade for a pharmacy: dispensing is low-frequency (tens of rows per second at most) and correctness of stock is non-negotiable, so pessimistic locking is right here and optimistic retry would be wrong.

**Allocation is a pure function.** `FefoAllocator.allocate(...)` performs no writes and touches no Spring beans beyond the repository read. It is a plain function over a sorted list, which is what makes the exhaustive edge-case tests in unit 06 cheap to write:

| Case | Expected |
| :--- | :--- |
| Requested 0 | Empty list, no error |
| Single batch, sufficient stock | One allocation, full quantity |
| Single batch, insufficient stock | `InsufficientStockException` carrying the shortfall |
| Requested exceeds total across all batches | Exception listing the shortfall and total available |
| Request across 2 batches | Two allocations split at the batch boundary |
| Request lands exactly on a batch boundary | First batch emptied, **no** zero-quantity allocation emitted |
| Zero/negative requested | `IllegalArgumentException` at the boundary |
| Expired batch sorted first | Never allocated — `status <> 'ACTIVE'` filters it out |
| Quarantined batch | Never allocated |
| Ties on `expiry_date` | Broken by `batch_number` for determinism |
| Location filter | Only batches in the requested location are considered |

### 4.2 OCR-Assisted Purchase Entry Pipeline

```text
Upload:   React POSTs the supplier invoice image to
          /api/v1/purchase-orders/ocr-upload  as multipart/form-data.

Store:    Backend writes the file to the configured upload directory
          (local FS in dev, MinIO in prod) and records the path on
          purchase_order.invoice_image_path.

Extract:  Tess4J/Tesseract returns raw text.

Parse:    InvoiceTextParser (pure, unit-testable, no Spring beans) scans
          the text with regexes for:
            • dates      DD/MM/YYYY | DD-MM-YYYY | MMM yyyy
            • quantities  integers adjacent to a unit or a rate
            • rates      ₹/$ prefixed or decimal-followed-by-Rs
            • names      token runs, fuzzy-matched against
                         medicine_catalogue.search_blob
          Each extracted field carries a 0..1 confidence derived from
          how well the regex matched and how close the fuzzy match was.

Draft:    Rows below the 0.6 confidence threshold are saved with
          ocr_confidence < 0.6 and NULL where nothing was readable.
          A purchase_order is created with status = DRAFT_OCR.
          NOTHING is written to inventory_batch.

Review:   The pharmacist opens the draft, corrects misreads
          ("Paracitamol" → "Paracetamol" sets is_corrected = TRUE), and
          clicks Approve → status = PENDING_APPROVAL.

Approve:  On approval the service creates one inventory_batch per
          line and one PURCHASE_IN stock_transaction per batch,
          all inside a single @Transactional method. Partial approval
          of a PO is not permitted — it is all-or-nothing.
```

**Fuzzy matching without a trigram index.** `InvoiceTextParser` resolves a candidate medicine name by, in order:
1. Exact match on `search_blob` — confidence 1.0.
2. `MATCH(search_blob) AGAINST (? IN BOOLEAN MODE)` with a trailing wildcard (`paracitamol*`) for prefix hits — confidence 0.9.
3. `SOUNDEX(search_blob) = SOUNDEX(?)` — catches genuine typos like *Paracitamol/Paracetamol*. Confidence 0.7.
4. No match → the line is kept with a NULL `medicine_id` and forced human review.

Soundex is a poor phonetic algorithm in the general case, but for a closed vocabulary of a few thousand drug names it is sufficient and costs nothing. This is the direct substitute for the `pg_trgm` capability the v1 draft assumed.

### 4.3 HMS Prescription Fetch Integration (STUBBED in v1)

```java
@FeignClient(name = "hms-service", url = "${hms.api.url}")
public interface HmsClient {
    @GetMapping("/api/v1/appointments/{id}/prescription")
    PrescriptionDTO getPrescription(@PathVariable("id") String id);
}
```

> **v1 status: STUB.** `practi` currently exposes only User, Doctor, Appointment, and Image controllers — **`GET /api/v1/appointments/{id}/prescription` does not exist**. Building against a live client would block v1 on upstream work. So v1 ships a `MockHmsClient` `@Primary` bean (activated by `hms.mode=mock`, the default) that returns fixture prescriptions, behind the same interface. When `practi` exposes the endpoint, flipping `hms.mode=live` activates the real client with **zero changes to calling code**. Since billing is out of v1 scope (§8), no unit in v1 calls `HmsClient` at all — the interface and stub are laid down so the seam exists and the contract is pinned, ready for the billing unit.
>
> The Feign contract is treated as an **open question** in `progress-tracker.md` until `practi` publishes its real DTO shape. The DTO here is a best guess from FDD §3.4 and is expected to change.

### 4.4 Stock Expiry Sweep

A `@Scheduled` job that transitions batches past their expiry date to `status = 'EXPIRED'` and writes an `EXPIRY_WRITE_OFF` transaction per batch, with `performed_by = NULL` (system-initiated). Runs hourly. Required because the FEFO index filters on `status = 'ACTIVE'` — a batch whose expiry date has silently passed would otherwise still be dispensed if the sweep had not run. The `status` column and the `expiry_date` column are therefore deliberately redundant.

## 5. Security & Compliance

```text
Authentication: JWT (JSON Web Tokens), 15-minute access-token expiry.
                Refresh tokens in HttpOnly, Secure, SameSite=Strict cookies.

RBAC:            Spring Security @PreAuthorize on controllers.
                hasAuthority('PHARMACIST')  → dispensing, cycle count, returns
                hasAuthority('CASHIER')     → invoice creation only. Cannot see
                                              cost_price, cannot adjust stock,
                                              cannot approve returns.
                hasAuthority('PHARMACY_MANAGER') → PO approval, write-off approval,
                                              sub-location transfers, margin reports
                hasAuthority('SUPER_ADMIN')  → supplier/manufacturer/user admin
                Passwords stored as BCrypt hashes. Never logged, never returned
                in any API response.

Audit logging:    Every change to inventory_batch is accompanied by a
                  stock_transaction row written in the same transaction.
                  Direct SQL updates are forbidden — all mutations go through
                  InventoryService. Enforced in unit 05 by a repository-level
                  guard (the batch repository exposes no public setters; the
                  only writer is InventoryService.adjust()).

Data privacy:     Patient names and UHIDs are stored for billing linkage only.
                  No clinical diagnosis data is stored in the PMS database —
                  prescriptions are fetched on demand from the HMS and
                  discarded after the bill is created.
```

## 6. Search Strategy

PostgreSQL supplies all three mechanisms in-engine, so this is a short section
where it used to be a workaround for an engine that lacked trigram support.

| Need | Mechanism |
| :--- | :--- |
| Typo tolerance on known names | `pg_trgm` with `similarity()` and `word_similarity()`, GIN index on the searchable columns |
| Relevance-ranked multi-word search | `tsvector` + GIN, queried with `plainto_tsquery` / `websearch_to_tsquery` |
| Autocomplete / prefix search | `LIKE 'term%'`, or a `pg_trgm` index in its prefix-friendly form |

The search cascade itself — exact, then boolean prefix, then soundex — is
specified in `specs/03-master-catalogue/03d-search-cascade.md` and is unchanged
by the engine switch. `pg_trgm` is a first-class extension rather than a
substitute for something else, which is the main simplification decision 22
bought.

Two things to decide when Unit 03 implements this, both of which the MySQL plan
did not have to face:

- **`pg_trgm` needs the extension installed**, so `CREATE EXTENSION IF NOT EXISTS
  pg_trgm` belongs in the migration that first uses it, and the migration role
  needs rights to create it. This is the `citext` lesson from V3 applied in
  advance.
- **`similarity()` thresholds are data-dependent.** A threshold that works for
  500 seeded medicines will not necessarily work for 50,000. Tune against real
  catalogue data, not against a fixture.

`soundex()` remains in the plan as the last-resort tier for single-token lookups
where a trigram index is too broad. It returns `''` on no match, and
`MedicineMatcher` must handle that. See §3.9.

## 7. Reporting & Compliance

### 7.1 Schedule H/H1 Register (FDD §3.5)

Not a separate pipeline. Derived from `stock_transaction` joined to `medicine_catalogue`, filtered on `schedule_class <> 'OTC'`. Every dispensing event already carries `performed_by` and `created_at`, which are exactly the fields a statutory register requires. Materialised on demand, cached in Redis for 5 minutes.

### 7.2 Ledger Reconciliation Query

The integrity check described in §3.6, run on demand from the admin screen:

```sql
SELECT b.id, b.batch_number, b.quantity_available AS book_qty,
       COALESCE(SUM(t.quantity_changed), 0) AS ledger_qty
  FROM inventory_batch b
  LEFT JOIN stock_transaction t ON t.batch_id = b.id
 GROUP BY b.id, b.batch_number, b.quantity_available
HAVING book_qty <> ledger_qty;
```

**Must return zero rows.** A non-empty result means a code path wrote to `inventory_batch` without a matching transaction — exactly the failure mode MDD §5 forbids. This query is the acceptance test for the audit invariant.

## 8. v1 Scope

Signed off with the user. **In scope:**

| # | Feature | Source | Unit |
| :--- | :--- | :--- | :--- |
| 1 | Medicine + non-medical catalogue, suppliers, manufacturers | FDD §3.1 | 03 (backend), 04 (UI) |
| 2 | Batch inventory, stock ledger, **FEFO engine**, alerts, cycle count | FDD §3.2, MDD §4.1 | 05, 06, 09 (backend), 07 (UI) |
| 3 | Purchase entry, **OCR-assisted entry** | FDD §3.3, MDD §4.2 | 08, 10 (backend), 11 (UI) |

Unit numbering follows `context/specs/00-build-plan.md`. Each backend feature ships with its own UI unit immediately after, per sign-off decision 5. Unit 09 (alerts and cycle count) sits between the inventory and purchasing backends because its queries depend on the batch and ledger tables but nothing else waits on it.

**Explicitly out of v1 scope** — deferred, schema designed but not implemented:

| Feature | Source | Why deferred |
| :--- | :--- | :--- |
| OPD billing & dispensing | FDD §3.4 | Depends on the HMS prescription endpoint, which does not exist. The FEFO engine it consumes *is* built in v1, so billing is additive later. |
| IPD billing / ward requisitions | FDD §3.4 | Same HMS dependency. |
| Returns processing | FDD §3.4 | Needs `SALE_OUT` history to return against. |
| Full reporting suite (short book, valuation, margin) | FDD §3.5 | Only the Schedule H/H1 register and the reconciliation query are in v1. |
| PDF generation (OpenPDF) | MDD §1 | No invoice flow to render yet. |
| Notifications (Novu/WhatsApp) | MDD §1 | No alert-delivery requirement in the FDD beyond dashboard widgets. |

**Deferral is not deletion.** The schema for `invoice`/`invoice_item` (§3.8) is migrated in v1 so that later units are additive, but no code writes to those tables in v1.

---

## 9. Relationship to the SCMS Reference Project

`../SCMS` (PharmaChain) is a Spring Boot supply-chain system covering inventory, ledger, catalogue and compliance. It is a **domain cousin** of this module, not a template to clone. This section records exactly what is borrowed and what is deliberately rejected, so the reasoning survives after the SCMS repo is forgotten.

### 9.1 What SCMS is

| Aspect | SCMS reality |
| :--- | :--- |
| Shape | **Modular monolith** — one deployable, but internally split into `catalog`, `inventory`, `ledger`, `transaction`, `auth`, `compliance`, `audit` PostgreSQL schemas, with hard rules against cross-schema joins |
| Tenancy | Multi-tenant SaaS. `tenant_id` on all 30 entities, `TenantContext` (`@RequestScope`), Postgres RLS session variables, a `TenantInterceptor` |
| Auth | Keycloak-issued JWT, validated as an OAuth2 Resource Server (realm + container required) |
| Async | RabbitMQ events between modules via Spring `ApplicationEventPublisher` → `@EventListener` |
| Money | `Double` |
| Migrations | Hand-run `.sql` in a sibling directory; Hibernate `ddl-auto: update` on top; **Flyway is documented but not wired** |
| Tests | **Zero.** No test file exists anywhere in the repository |
| Frontend | React 18 + Vite + TS + Tailwind + shadcn/Radix + AG Grid + Zustand + RHF/Zod, at `apps/web-internal` |

### 9.2 Adopted from SCMS

These are genuinely good and are carried over:

| Concern | Convention adopted |
| :--- | :--- |
| Entity style | `@Data @Builder @NoArgsConstructor @AllArgsConstructor` in that order; `import jakarta.persistence.*`; enums as nested types with `@Enumerated(EnumType.STRING)`; plain `UUID` FK columns rather than `@ManyToOne`; manual `@PreUpdate` auditing instead of `AuditingEntityListener` |
| Repository style | `@Repository` + `extends JpaRepository<T, UUID>`; JPQL text blocks (`"""`) for non-trivial queries; no `nativeQuery` |
| DTO style | Plain Lombok **classes** (not records), manual mapping via a `static fromEntity(...)` factory on the DTO itself |
| Controller style | `@RestController` + `@RequestMapping("/api/v1/<module>")` + `@RequiredArgsConstructor`; springdoc `@Tag` / `@Operation`; `// ==================== Section ====================` dividers; `201` + `URI.create(...)` on create; `ResponseEntity<Page<T>>` for lists |
| Service style | `@Slf4j @Service @RequiredArgsConstructor`; `@Transactional(readOnly = true)` on reads, plain `@Transactional` on writes; `orElseThrow(() -> new ...)` as the not-found idiom |
| Error shape | `{timestamp, status, error, message}` |
| FEFO ordering rule | `ORDER BY expiryDate ASC, receivedAt ASC`, filtered to active batches with quantity remaining |
| Frontend layout | `src/{components/{layout,ui},features/<f>/{pages,components},lib,pages,store,types}`; `MainLayout` as the auth gate; all protected routes nested under `/app`; one named export per line in a feature barrel with `pages/` before `components/` |
| Frontend UI kit | shadcn/ui on Radix with HSL channel-triplet tokens in `index.css` wrapped by `hsl(var(--x))` in `tailwind.config.js`; `@/` path alias; `cva` variant maps; `cn()` via `clsx` + `tailwind-merge` |
| Frontend data pattern | A hand-rolled `ApiClient` class in `lib/api.ts`; Zustand stores for auth and UI state; AG Grid themed via a wrapping `div.ag-theme-alpine` plus a CSS-variable remap block |

### 9.3 Rejected from SCMS, and why

These are SCMS shortcomings. Carrying them over would be copying a bug.

| SCMS pattern | Problem | Pharmacy decision |
| :--- | :--- | :--- |
| Modular-monolith schema split (`catalog.*`, `inventory.*`, …) and the "no cross-schema join" rule | Ceremony without a boundary to defend. PMS is **client-facing and standalone** — one deployable, one schema, no async broker, no message bus. | **Single `pharmacy` schema, flat layered packages.** The internal-module boundary rule is dropped. |
| Multi-tenancy: `tenant_id` everywhere, `TenantContext`, RLS, `TenantInterceptor` | A column and a filter on every query, to serve a use case (one hospital) the FDD never describes. | **No tenancy.** A slim `CurrentUserContext` is kept, but only because `stock_transaction.performed_by` needs an actor for the audit trail. |
| `Double` for money | Binary floating point cannot represent `0.1`. Rounding errors on a pharmacy ledger are unacceptable. | **`BigDecimal` / `DECIMAL(10,2)`.** Never `float`/`double` for any monetary value. |
| FEFO with no locking | `issueStock` reads batches, then writes, relying on the transaction alone. Under concurrency two dispensations can both read the same balance and interleave their writes. | **`SELECT … FOR UPDATE`** on the FEFO query. Pessimistic is correct here: dispensing is low-frequency and stock correctness is non-negotiable. |
| Inconsistent `@PreAuthorize` casing (`super_admin` vs `SUPER_ADMIN`) worked around by a custom `JwtAuthenticationConverter` that emits case variants | A workaround for sloppiness, and it silently grants extra authorities. | **One casing, `UPPER_CASE`,** matching the FDD §2 role names. No case-normalisation hack. |
| Keycloak + realm JSON + container | A 6-container dependency to log a user in. FDD §1 requires the PMS to run **standalone**. | **Self-issued JWT** (jjwt) against a local `users` table, exactly as FDD §5 specifies. |
| Zero tests | Nothing is verified. The FEFO algorithm — the highest-risk code in the system — is entirely unexercised. | **Real unit tests** for the FEFO allocator and the OCR parser, both written as pure functions so they need no Spring context or database. |
| Hand-run SQL migrations plus `ddl-auto: update` | Schema drift; no way to know what a given database actually contains. | **Flyway**, versioned and applied on startup. `ddl-auto: validate` so Hibernate fails loudly on mismatch instead of silently mutating. |
| Raw `fetch` with a token scraped from `sessionStorage`, bypassing the API client | Inconsistent, and bypasses shared error handling. | **All calls go through `ApiClient`.** One error path. |
| `Double`-typed `any` parameters in cell handlers; `any` grid events | Untyped escape hatches defeat the compiler. | **`tsconfig` keeps `strict`, `noUnusedLocals`, `noUnusedParameters`.** No `any` in new code. |
| A design `.md` file committed inside `src/main/java` | Documentation inside the source tree. | **Docs live in `context/`.** Nothing but `.java` under `src/main/java`. |
| `ToastContainer` never mounted, so `toast.success()` silently does nothing | A broken feature that looks implemented. | **Toasts are wired and verified** as part of the frontend shell unit. |

### 9.4 Net architectural position

```text
SCMS                              Pharmacy (PMS)
──────────────────────────────    ──────────────────────────────
Modular monolith                 Single layered service
7 Postgres schemas               1 flat schema
Multi-tenant + RLS               Single hospital
Keycloak + RabbitMQ + MinIO      Standalone; JWT + local files
Double money                     BigDecimal
Unlocked FEFO                    FEFO under SELECT … FOR UPDATE
Zero tests                       Unit tests on the critical paths
Hand-run SQL                     Flyway
```

Pharmacy borrows SCMS's *craft* — entity and DTO idioms, JPQL text blocks, the controller and service shapes, and the entire frontend skeleton — while discarding its *architecture*, which was sized for a multi-tenant SaaS product rather than a hospital pharmacy counter.
