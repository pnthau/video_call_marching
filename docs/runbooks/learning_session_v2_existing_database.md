# LearningSession V2 — Existing Database Baseline Runbook

## Scope

Use this runbook only for an existing database that predates Flyway and has no
`flyway_schema_history` table. Never begin on the development or production
database itself. Rehearse the complete procedure on a restored copy first.

## 1. Preconditions and stop conditions

Record the database host alias, schema name, MySQL version, application commit,
and operator. Do not record passwords or tokens.

Stop immediately if any of the following is true:

- A restorable backup has not been verified.
- The target database or schema name is ambiguous.
- `flyway_schema_history` exists but its state is unexplained.
- The current schema cannot be proven compatible with V1.
- Participant foreign keys are invalid or a session contains the same user twice.
- Lifecycle status/reason values are outside the documented legacy set.
- Presence data already exists but its origin is unknown.
- A Flyway checksum, validation, or migration error occurs.

## 2. Backup and restore rehearsal

Use an account with only the privileges required for backup and migration. Keep
the password in a credential store or temporary environment variable, never in
the command line or shell history.

```powershell
mysqldump --single-transaction --routines --triggers `
  --user=$env:MYSQL_USERNAME `
  --databases <existing_database> `
  --result-file=<absolute_backup_path>
```

Calculate and record a checksum for the backup. Restore it into a new,
explicitly named verification database and compare table and row counts.

```powershell
mysql --user=$env:MYSQL_USERNAME <verification_database> < <absolute_backup_path>
```

Do not proceed until the restore rehearsal succeeds.

## 3. Determine the baseline version

Compare the restored schema with `V1__initial_schema.sql` using
`INFORMATION_SCHEMA.COLUMNS`, `TABLE_CONSTRAINTS`, and `STATISTICS`.

At minimum verify:

- `users`, `learning_sessions`, `tag_categories`, `tags`, `peer_ratings`, and
  `social_accounts` exist with V1-compatible columns.
- `learning_sessions.channel_name` is unique.
- Both participant columns reference `users.id`.
- Legacy lifecycle columns required by V3 are still present.

Baseline version 1 is allowed only when the restored schema is semantically V1.
Do not choose a baseline version merely to skip a failing migration.

## 4. Legacy data preflight

Export aggregate counts without PII:

- Sessions grouped by status and completion reason.
- Missing or invalid join/leave timestamps.
- Self-sessions and broken participant references.
- Legacy `ENDED` rows by reason and calculated overlap evidence.
- Existing presence rows and duplicate open intervals.

Resolve or explicitly approve every anomaly before migration.

## 5. Baseline and migrate the restored copy

Baseline is an explicit one-time operator action on the restored copy. Supply
the flags only for that run; never add `baseline-on-migrate=true` to committed
default configuration.

```powershell
.\gradlew.bat bootRun --args="--spring.datasource.url=<verification-jdbc-url> --spring.flyway.baseline-on-migrate=true --spring.flyway.baseline-version=1 --spring.jpa.hibernate.ddl-auto=validate"
```

Expected result:

- Flyway records baseline version 1.
- V2 and V3 apply successfully.
- Hibernate schema validation succeeds.
- No legacy `ENDED` status remains.
- Terminal sessions have no open presence interval.
- Before/after counts are explainable.

Restart without the baseline flags. Flyway must report the schema is current and
Hibernate validation must still pass.

## 6. Apply to the existing database

Schedule downtime, stop all application instances and schedulers, take a fresh
backup, repeat the preflight, then execute the same proven baseline/migration
procedure. Abort on any difference from the rehearsal.

## 7. Rollback

V3 changes lifecycle status/reason and is lossy. Do not attempt to reverse it
with compensating updates or `flyway repair`.

Rollback procedure:

1. Stop the application.
2. Preserve the failed database for investigation.
3. Restore the pre-migration backup into a new database.
4. Verify schema, row counts, foreign keys, and representative reads.
5. Point the application back only after restore verification succeeds.

`flyway repair` is not a rollback mechanism and must not be used to conceal a
checksum or failed migration.
