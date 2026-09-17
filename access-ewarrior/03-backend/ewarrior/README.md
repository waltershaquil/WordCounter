# Access (E-Warrior App) — Backend

Spring Boot + JdbcTemplate + MySQL. Package root `com.ac.mz`.

---

## Structure

```
com.ac.mz
├── AccessApplication.java        entry point
├── config/      AppConfig            properties + seed admin runner
├── websecurity/ WebSecurity          CurrentUser, JWT filter, SecurityConfig
├── auth/        AuthProviders        AuthProvider + local (dev) + Active Directory (prod)
├── controller/  ApiControllers       every JSON endpoint
│               FileController       every upload/download (card, QR, PDF, booklet)
│               HomeController       the public /c/{id} card page
├── services/    Services             all business logic
├── helpers/     Helpers              SecurityHelper, AuditHelper, ProductStateHelper
├── model/       Models               domain records (one per table)
│               Dtos                 request and response shapes
├── dao/         Daos                 all SQL — no mappers here
├── mapper/      RowMappers           every RowMapper, in its own package
├── utility/     Utilities            JwtUtil, FileStorageUtil, QrCodeUtil, TextUtil
└── exception/   Exceptions           exception types + the global handler
```

**14 files.** Related classes are grouped into one file as static nested classes — all DAOs in `Daos.java`, all services in `Services.java`, and so on. Spring's component scanning picks up annotated nested classes exactly as if they were separate files, so `@Repository`, `@Service` and `@RestController` work unchanged.

**Row mappers are not in the DAO.** They live in `com.ac.mz.mapper.RowMappers` and the DAO references them by name (`RowMappers.COLLABORATOR`). Since there is no JPA here, that mapper *is* the link between a column and a Java field; keeping one per table, in one place, is what stops two queries against the same table drifting apart.

**Why `Models` and `Dtos` are separate.** A Model is whatever the row contains; a DTO is what we are willing to expose. That separation is what guarantees `AdObjectGuid` never reaches the public card response — the DTO has no field for it.

---

## Running it locally

Java 17, Maven, MySQL 8.

```bash
# 1. Database
mysql -u root -p -e "CREATE DATABASE ewarrior CHARACTER SET utf8mb4;"
mysql -u root -p -e "CREATE USER 'ewarrior'@'localhost' IDENTIFIED BY 'devpassword';"
mysql -u root -p -e "GRANT ALL ON ewarrior.* TO 'ewarrior'@'localhost';"

# 2. Storage directory
sudo mkdir -p /var/access/{cards,qrcodes,documents,booklet}
sudo chown -R $USER /var/access

# 3. Run — dev profile uses local test accounts, no Active Directory needed
export DB_PASSWORD=devpassword
export JWT_SECRET=a-development-secret-at-least-32-characters-long
export STORAGE_ROOT=/var/access
export SEED_ADMIN_GUID=8f14e45f-ceea-467a-9c1d-0b6f0a2b3c4d

mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

Flyway creates the schema on first start. `SeedAdminRunner` creates the first admin from `SEED_ADMIN_GUID`, matching the `admin.dev` row in the dev credentials table.

**Dev login:** `admin.dev` / `password123`

```bash
curl -X POST localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin.dev","password":"password123"}'
```

---

## Authentication

`AuthProviders.AuthProvider` is an interface with two implementations, selected by `access.auth.provider`:

| Profile | Implementation | Credentials from |
|---|---|---|
| `dev` | `LocalAuthProvider` | `LocalCredential` table (BCrypt) |
| `prod` | `ActiveDirectoryAuthProvider` | LDAPS bind against a domain controller |

Nothing downstream knows which is active. When IT supplies the directory details, fill in the two `TODO(infra)` comments in the AD provider and switch the profile.

**`V2__dev_local_credentials.sql` must never run in production.** Delete it, or point the prod Flyway location somewhere that excludes it. A password table in production is exactly what the AD decision was meant to avoid.

---

## Schema

Three tables plus two supporting ones.

- **Collaborator** — card and QR fields live here (`CardPath`, `QrCodePath`, `Scans`), since each collaborator has exactly one of each.
- **Product** — carries its own `SubmittedBy`, `ApprovedBy` and `State`. There is no list or batch entity: "the active list" is `WHERE State = 'APPROVED' AND IsRetired = FALSE`.
- **Booklet** — one row (`ID` always 1) holding the rendered Share Products image.
- **AuditLog** — append-only. **RevokedToken** — logout support.

**Two admin levels:** `IsCollaboratorAdmin` manages accounts; `IsProductAdmin` does that *and* approves products *and* grants admin rights. Only a product admin can hand out either flag — otherwise a collaborator admin could edit their own record and promote themselves.

---

## Things that will bite you

**File ordering.** Write new files *before* the database update, delete old files *after* it succeeds. An orphaned file wastes bytes; a row pointing at a missing file is a 500. `Services.CardService.upload` shows the pattern with comments.

**Self-invoked `@Transactional` does nothing.** Calling an annotated method from another method in the same class bypasses the Spring proxy and runs without a transaction, silently.

**Multiple app instances need a shared mount** for `STORAGE_ROOT`. A card written on instance A is a 404 on instance B otherwise.

**Back up `/var/access` with the database, on the same schedule.** A database-only restore leaves every card, QR code, document and the booklet broken — discovered during a restore, not before one.

**`/c/{id}` is public and reachable externally.** It checks `IsActive` itself, needs IP rate limiting at the proxy, and must never return the AD GUID or an admin flag.

**Never log the password.** It exists only as a parameter to the LDAP bind. Watch exception handlers — one that logs a full request object will leak it.

**Category is free text.** `Product.Category` has no lookup table, so "Savings" and "savings" can drift apart. The DAO groups case-insensitively and the service merges on lowercase, but the admin UI should suggest existing values rather than offering a bare text box.

**No booklet version history.** Each render replaces the last. Nothing forces a re-render after a product is approved either, so the product list and the share image can silently disagree — worth deciding how to handle before launch.

---

## Environment variables

| Variable | Required | Notes |
|---|---|---|
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` | yes | |
| `JWT_SECRET` | yes | min 32 chars, different per environment |
| `STORAGE_ROOT` | yes | shared mount if multi-instance |
| `SEED_ADMIN_GUID` | first deploy | objectGUID of the initial admin |
| `PUBLIC_CARD_BASE_URL` | yes | externally reachable host the QR encodes |
| `LDAP_URL`, `LDAP_BASE`, `LDAP_BIND_USER`, `LDAP_BIND_PASSWORD` | prod | from infra |
| `AD_DOMAIN`, `AD_SEARCH_BASE` | prod | from infra |
