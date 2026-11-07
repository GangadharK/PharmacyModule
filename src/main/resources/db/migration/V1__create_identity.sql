-- =============================================================================
-- V1 — Identity & Audit Actor (PostgreSQL 16)
--
-- Creates the two tables Unit 02 needs: users, and the server-side record of
-- issued refresh tokens.
--
-- Schema: context/MDD.md section 3.1. Type mapping: section 3.9.
--
-- Depends on: nothing. This is the first migration.
--
-- POSTGRESQL IS THE ONLY ENGINE (decision 22). An earlier version of this file
-- had a MySQL twin under db/migration/mysql/, selected by a spring profile.
-- Maintaining both was more expensive than it was worth — the two engines
-- reported the same columns as different JDBC types, so migrations were being
-- contorted to satisfy a parity test rather than to satisfy the database. The
-- MySQL tree and profile have been removed.
--
-- Deliberately contains NO user rows. See "Seeding" below.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- users
--
-- username is a plain varchar. Case-insensitive uniqueness is a property of a
-- lower(username) unique index added in V3, not of the column type: citext was
-- tried here first and reports to JDBC as Types#OTHER, which made ddl-auto:
-- validate refuse to start the application.
-- -----------------------------------------------------------------------------
CREATE TABLE users (
    id            uuid           NOT NULL,
    username      varchar(100)   NOT NULL,   -- uniqueness is case-insensitive via V3
    password_hash varchar(255)   NOT NULL,   -- BCrypt, never a plaintext password
    full_name     varchar(255)   NOT NULL,
    role          varchar(30)    NOT NULL,
    is_active     boolean        NOT NULL DEFAULT TRUE,
    created_at    timestamp(3)   NOT NULL DEFAULT now(),
    last_login_at timestamp(3)   NULL,

    CONSTRAINT pk_users            PRIMARY KEY (id),
    CONSTRAINT uk_users_username   UNIQUE (username),
    -- CHECK is enforced on PostgreSQL, so an invalid role fails at the database
    -- rather than relying on the application to have checked first.
    CONSTRAINT chk_users_role      CHECK (role IN
        ('SUPER_ADMIN','PHARMACY_MANAGER','PHARMACIST','CASHIER'))
);

COMMENT ON COLUMN users.password_hash IS 'BCrypt hash. Never logged, never serialised, excluded from toString().';

-- -----------------------------------------------------------------------------
-- refresh_token
--
-- Server-side and revocable, which is what makes logout immediate. The access
-- token is stateless and short-lived, so a role change takes effect within its
-- 15-minute lifetime; this table is what makes session revocation immediate.
--
-- The token itself is never stored. Only its SHA-256 hex, so a database dump
-- does not hand out live sessions.
--
-- token_hash is varchar(64) here and in V2. It was originally CHAR(64), which
-- is fine on MySQL but reports to Hibernate as bpchar and fails validation.
-- -----------------------------------------------------------------------------
CREATE TABLE refresh_token (
    id           uuid         NOT NULL,
    user_id      uuid         NOT NULL,
    token_hash   char(64)     NOT NULL,      -- SHA-256 hex; becomes varchar in V2
    expires_at   timestamp(3) NOT NULL,
    revoked_at   timestamp(3) NULL,          -- set on logout or on rotation
    replaced_by  uuid         NULL,          -- rotation chain: what this became
    created_at   timestamp(3) NOT NULL DEFAULT now(),
    user_agent   varchar(255) NULL,
    ip_address   varchar(45)  NULL,          -- 45 = max IPv6 length

    CONSTRAINT pk_refresh_token        PRIMARY KEY (id),
    CONSTRAINT uk_refresh_token_hash   UNIQUE (token_hash),
    CONSTRAINT fk_refresh_user         FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE
);

-- Lookup paths: by user when revoking all of a user's sessions, by expiry when
-- sweeping dead rows. Both are on the hot path for the auth filter.
CREATE INDEX idx_refresh_user   ON refresh_token (user_id);
CREATE INDEX idx_refresh_expiry ON refresh_token (expires_at);

-- =============================================================================
-- Seeding — deliberately NOT here
--
-- The Unit 02 spec asks for four seeded users, one per role, with BCrypt hashes
-- in the migration. A Flyway migration runs against the real database, so that
-- instruction would create a SUPER_ADMIN account with a published password, and
-- it would do so silently on every deploy. A known-password administrator is a
-- standing remote-takeover, not a "known trade-off".
--
-- Seeding therefore lives in IdentitySeeder, gated on app.seed.enabled, which
-- the dev profile sets true and production leaves false. Same env-gated pattern
-- as app.cookie.secure (decision 17).
--
-- Change this if the spec is amended, but do not put credentials in a migration.
-- MigrationGuardTest asserts that no hash ever appears in one.
-- =============================================================================
