-- Creates the two tables of Unit 09. Depends on V1 (users, for counted_by and
-- posted_by) and V5 (inventory_batch, for the per-batch lines).
--
-- Why a cycle count needs tables of its own, when POST /inventory/{id}/adjust
-- would move the same stock in one line: an adjust records that stock changed,
-- not that anybody verified it. These tables exist so a variance carries a
-- counter, a date, and the figure the system claimed at the time -- see
-- specs/09-alerts-and-cycle-count.md §4.

CREATE TABLE cycle_count (
    id            UUID PRIMARY KEY,
    reference_no  VARCHAR(32)  NOT NULL,
    status        VARCHAR(16)  NOT NULL,
    location      VARCHAR(32)  NOT NULL,
    counted_by    UUID         NOT NULL REFERENCES users (id),
    notes         VARCHAR(500),
    created_at    TIMESTAMP    NOT NULL,
    posted_at     TIMESTAMP,
    posted_by     UUID         REFERENCES users (id),

    CONSTRAINT cycle_count_status_ck CHECK (status IN ('OPEN', 'POSTED')),
    -- posted_at and posted_by are all-or-nothing: a half-posted count is not a
    -- state this table is allowed to describe.
    CONSTRAINT cycle_count_posted_ck CHECK (
        (status = 'OPEN'  AND posted_at IS NULL     AND posted_by IS NULL)
     OR (status = 'POSTED' AND posted_at IS NOT NULL AND posted_by IS NOT NULL)
    )
);

-- The reference number is how a count is cited on a report, so it has to be
-- unique; the sequence is assigned in the service, per location-year.
CREATE UNIQUE INDEX ux_cycle_count_reference_no ON cycle_count (reference_no);

-- The list screen reads open sessions, newest first, per location.
CREATE INDEX ix_cycle_count_status_created ON cycle_count (status, created_at DESC);

CREATE TABLE cycle_count_line (
    id               UUID PRIMARY KEY,
    cycle_count_id   UUID    NOT NULL REFERENCES cycle_count (id) ON DELETE CASCADE,
    batch_id         UUID    NOT NULL REFERENCES inventory_batch (id),

    -- Snapshot, not a live read. Computed at post time, a line counted at 12
    -- against a system that had since dropped to 9 would post a +3 variance for
    -- a shelf nobody touched. See spec §4.1.
    system_quantity  INTEGER NOT NULL,
    -- Null until a human enters a figure, which is how an uncounted line is
    -- told apart from a counted zero. NOT NULL DEFAULT 0 would lose that.
    counted_quantity INTEGER,
    -- Set at post, not stored before: variance is derived state.
    variance         INTEGER,

    CONSTRAINT cycle_count_line_qty_ck CHECK (system_quantity >= 0 AND counted_quantity >= 0)
);

-- One line per batch per count; re-counting the same batch is a correction, and
-- PATCH overwrites it rather than appending a second line.
CREATE UNIQUE INDEX ux_cycle_count_line_batch ON cycle_count_line (cycle_count_id, batch_id);

CREATE INDEX ix_cycle_count_line_count ON cycle_count_line (cycle_count_id);
