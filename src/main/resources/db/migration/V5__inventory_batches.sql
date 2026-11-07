-- =============================================================================
-- V5 — inventory batches and the append-only stock ledger
--
-- Creates the two tables of Unit 05. Depends on V1 (users, for performed_by)
-- and V4 (medicine_catalogue, non_medical_item, supplier — the three parents a
-- batch may point at).
--
-- Read MDD.md §3.5-3.6 for the logical model. This file is the PostgreSQL 16
-- translation and the source of truth for what actually runs. Three
-- translations are worth stating outright:
--
-- 1. ON UPDATE CURRENT_TIMESTAMP(3) is MySQL. The service owns updated_at
--    (code-standards.md §2.7), so there is deliberately no trigger: a trigger
--    would be a second, invisible writer of a column the service already sets,
--    and the two would disagree the moment a test injects a fixed clock.
--
-- 2. The FEFO index is PARTIAL, which MySQL cannot express. MDD.md §3.5
--    explains why this matters: the MySQL-era plan had to use a plain composite
--    index and apply "status = 'ACTIVE' AND quantity_available > 0" as a
--    residual predicate, which left depleted and quarantined rows physically
--    interleaved with live ones so the FEFO scan filtered row by row. Here the
--    index contains only rows that can be dispensed, so Unit 06's allocation
--    loop is a single range scan in ascending expiry order with no filter and
--    no in-memory sort.
--
-- 3. location is deliberately NOT in the FEFO index. It is filtered on but not
--    ordered by, so including it would widen the key without helping the
--    ordering.
--
-- The two constraints that make concurrent dispensing safe are both here rather
-- than in Java, because Java cannot enforce them against a second writer:
--
--   chk_batch_qty       quantity_available >= 0
--   (the conditional UPDATE in the repository, not expressible as a constraint)
--
-- quantity_changed on stock_transaction is SIGNED, and that is the single most
-- important audit property in the system: it makes reconciliation one SUM
-- (MDD.md §7.2) instead of forcing every consumer to re-derive direction from
-- the type enum. An unsigned magnitude would also have made a sign error
-- invisible, since abs(delta) looks right on both an in and an out.
--
-- performed_by is nullable for exactly one caller: the expiry sweep, which is a
-- legitimate anonymous system action. CurrentUserContext rejects rather than
-- defaults when there is no authenticated user, so a null in this column can
-- only ever have been written deliberately.
-- =============================================================================

CREATE TABLE inventory_batch (
    id                 UUID         NOT NULL,
    medicine_id        UUID,
    item_id            UUID,
    supplier_id        UUID,
    batch_number       VARCHAR(100) NOT NULL,
    manufacturing_date DATE,
    expiry_date        DATE         NOT NULL,
    quantity_received  INT          NOT NULL DEFAULT 0,
    quantity_available INT          NOT NULL DEFAULT 0,
    cost_price         NUMERIC(10,2) NOT NULL,
    selling_price      NUMERIC(10,2) NOT NULL,
    location           VARCHAR(30)  NOT NULL DEFAULT 'MAIN_PHARMACY',
    status             VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at         TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at         TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    CONSTRAINT pk_inventory_batch PRIMARY KEY (id),

    -- Exactly one parent. A batch of "something" with no product behind it
    -- cannot be dispensed, allocated, or reported on, and a CHECK is the only
    -- place that can say so without trusting every future writer.
    CONSTRAINT chk_batch_polymorphic CHECK (
        (medicine_id IS NOT NULL AND item_id IS NULL) OR
        (medicine_id IS NULL AND item_id IS NOT NULL)),

    CONSTRAINT chk_batch_qty CHECK (quantity_available >= 0),
    CONSTRAINT chk_batch_qty_received CHECK (quantity_received >= 0),

    -- A batch that expired before it was manufactured is a data-entry error, and
    -- it would sort first under FEFO and be dispensed immediately. Only checked
    -- when manufacturing_date is present, since it is nullable.
    CONSTRAINT chk_batch_dates CHECK (
        manufacturing_date IS NULL OR expiry_date >= manufacturing_date),

    CONSTRAINT chk_batch_status CHECK (status IN
        ('ACTIVE', 'QUARANTINED', 'EXPIRED', 'DEPLETED')),

    CONSTRAINT chk_batch_location CHECK (location IN
        ('MAIN_PHARMACY', 'OPD_COUNTER', 'EMERGENCY', 'ICU')),

    CONSTRAINT fk_batch_medicine FOREIGN KEY (medicine_id)
        REFERENCES medicine_catalogue (id) ON DELETE RESTRICT,
    CONSTRAINT fk_batch_item FOREIGN KEY (item_id)
        REFERENCES non_medical_item (id) ON DELETE RESTRICT,
    CONSTRAINT fk_batch_supplier FOREIGN KEY (supplier_id)
        REFERENCES supplier (id) ON DELETE SET NULL
);

-- The FEFO index. Partial, so it holds only dispensable rows (see note 2 above).
-- Ascending expiry is explicit: it is the allocation order, and leaving the
-- default implicit would make the index's purpose a matter of luck.
CREATE INDEX idx_fefo_search ON inventory_batch (medicine_id, expiry_date ASC)
    WHERE status = 'ACTIVE' AND quantity_available > 0;

-- The same for non-medical items, which have their own FEFO queries.
CREATE INDEX idx_item_fefo ON inventory_batch (item_id, expiry_date ASC)
    WHERE status = 'ACTIVE' AND quantity_available > 0;

-- Near-expiry reporting (Unit 09) scans by date across all products.
CREATE INDEX idx_near_expiry ON inventory_batch (expiry_date, status);

-- Batch-number lookup when receiving goods. Deliberately NOT unique: the same
-- batch number from two suppliers is not a conflict, and the same number for two
-- different products is not either. A unique index here would reject legitimate
-- deliveries and force a workaround nobody would remember the reason for.
CREATE INDEX idx_batch_lookup ON inventory_batch (batch_number);

-- ============================================================================
-- stock_transaction — append-only. No UPDATE, no DELETE, from any code path.
-- ============================================================================

CREATE TABLE stock_transaction (
    id               UUID         NOT NULL,
    batch_id         UUID         NOT NULL,
    transaction_type VARCHAR(30)  NOT NULL,
    -- Signed. +in, -out. See the header note.
    quantity_changed INT          NOT NULL,
    reference_type   VARCHAR(30),
    reference_id     UUID,
    note             VARCHAR(500),
    -- NULL means a system action. The only writer that may do that is the
    -- expiry sweep; CurrentUserContext rejects an anonymous human action.
    performed_by     UUID,
    created_at       TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    CONSTRAINT pk_stock_transaction PRIMARY KEY (id),

    CONSTRAINT chk_txn_type CHECK (transaction_type IN
        ('PURCHASE_IN', 'SALE_OUT', 'RETURN_RESTOCK', 'DAMAGE_WRITE_OFF',
         'EXPIRY_WRITE_OFF', 'TRANSFER_OUT', 'TRANSFER_IN', 'CYCLE_COUNT_ADJUST',
         'QUARANTINE', 'RELEASE')),

    -- A row with no type is not an audit record, it is noise that reconciles to
    -- nothing. Zero is allowed on purpose: quarantine and release change state
    -- and not quantity, and the zero row is what makes them answerable later.
    CONSTRAINT chk_txn_changed CHECK (quantity_changed <> 0
                                       OR transaction_type IN ('QUARANTINE',
                                                              'RELEASE',
                                                              'EXPIRY_WRITE_OFF')),

    -- reference_type and reference_id are a pair. One without the other cannot
    -- be followed to anything.
    CONSTRAINT chk_txn_reference CHECK (
        (reference_type IS NULL AND reference_id IS NULL) OR
        (reference_type IS NOT NULL AND reference_id IS NOT NULL)),

    CONSTRAINT chk_txn_ref_type CHECK (reference_type IS NULL OR reference_type IN
        ('PURCHASE_ORDER', 'INVOICE', 'RETURN', 'CYCLE_COUNT')),

    CONSTRAINT fk_txn_batch FOREIGN KEY (batch_id)
        REFERENCES inventory_batch (id) ON DELETE RESTRICT,
    CONSTRAINT fk_txn_user FOREIGN KEY (performed_by)
        REFERENCES users (id) ON DELETE SET NULL
);

-- A batch's own history, in order.
CREATE INDEX idx_txn_batch ON stock_transaction (batch_id, created_at);

-- "Which ledger rows did this purchase order write?" — Unit 08, and the reason
-- reference_id is a first-class column rather than folded into note text.
CREATE INDEX idx_txn_ref ON stock_transaction (reference_type, reference_id);

-- The expiry sweep's working set, and the reconciliation query's.
CREATE INDEX idx_txn_time ON stock_transaction (created_at);
