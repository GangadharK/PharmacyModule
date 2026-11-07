-- =============================================================================
-- V3 — case-insensitive usernames without citext
--
-- citext gave PostgreSQL case-insensitive username comparison, but it reports to
-- JDBC as Types#OTHER, so Hibernate could not map a String onto it and
-- ddl-auto: validate refused to start the application:
--
--   Schema validation: wrong column type encountered in column [username]
--   in table [users]; found [citext (Types#OTHER)],
--   but expecting [varchar(100) (Types#VARCHAR)]
--
-- Working around it would have meant a custom Hibernate type or a dialect-specific
-- columnDefinition in shared entity code. Neither is worth it, and the second
-- would have re-broken the moment a second engine existed.
--
-- The behaviour is preserved, in a portable way: a plain varchar(100) column
-- plus a UNIQUE index on lower(username). Case-insensitive uniqueness is then a
-- property of the index rather than of the column type, so a lookup for
-- 'Aiswarya' still cannot match 'aiswarya' — a pharmacist cannot end up with two
-- accounts differing only in case — while the column stays an ordinary varchar
-- that Hibernate, psql, and every future tool understand.
--
-- UserRepository queries with lower(username) = lower(:username) so this index
-- is actually used. A derived findByUsernameIgnoreCase query is equally correct
-- but Hibernate implements it with upper(), which would not match this index and
-- would fall back to a sequential scan of users on every login attempt.
--
-- The index keeps the name uk_users_username so constraint names stay stable
-- against V1, which declared the citext-backed constraint of the same name.
--
-- Depends on: V2.
-- =============================================================================

ALTER TABLE users
    ALTER COLUMN username TYPE varchar(100);

-- Drop the old uniqueness before replacing it, otherwise it lingers alongside
-- the new index and rejects nothing the new one would not.
ALTER TABLE users
    DROP CONSTRAINT uk_users_username;

CREATE UNIQUE INDEX uk_users_username ON users (lower(username));
