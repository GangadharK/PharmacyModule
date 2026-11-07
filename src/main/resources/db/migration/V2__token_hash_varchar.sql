-- =============================================================================
-- V2 — token_hash CHAR(64) to VARCHAR(64)
--
-- V1 declared token_hash as CHAR(64), which is what MDD 3.1 specified. Hibernate
-- maps a Java String to varchar(64), and ddl-auto: validate compares that
-- expectation against what the engine reports.
--
-- The problem was only visible while MySQL was still supported: MySQL reported
-- the column as CHAR and validation passed, while the PostgreSQL twin reported
-- bpchar — the internal name for CHARACTER(n) — and validation FAILED. So the
-- application started cleanly on MySQL and refused to start on PostgreSQL, the
-- development engine. Same migration, same intent, different dialect reporting.
-- This is the clearest single argument for decision 22.
--
-- Why VARCHAR: the alternative was @Column(columnDefinition = "char(64)") on
-- shared entity code, hardcoding one engine's DDL into a class that runs against
-- both. Fixed-width CHAR buys nothing for a SHA-256 hex digest, which is always
-- exactly 64 characters and so is never space-padded or truncated. varchar(64)
-- constrains the length just as well and maps to a plain String.
--
-- The UNIQUE index on token_hash is unaffected by the type change; PostgreSQL
-- preserves indexes across ALTER COLUMN ... TYPE.
--
-- Depends on: V1.
-- =============================================================================

ALTER TABLE refresh_token
    ALTER COLUMN token_hash TYPE varchar(64);
