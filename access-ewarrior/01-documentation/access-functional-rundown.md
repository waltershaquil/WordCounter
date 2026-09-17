# Access (E-Warrior App) — Functional Design Rundown

**Stack:** Spring Boot + JdbcTemplate (MySQL), self-hosted on internal servers

Companion to the API reference — how each flow works, layer by layer, and why the design is shaped the way it is.

---

## 1. Layer Structure

```
HTTP request
   ↓
[ Filter ]        JwtAuthenticationFilter — validates token, populates SecurityContext
   ↓
[ Controller ]    Binds request, returns a DTO. No business logic.
   ↓
[ Service ]       Business rules, authorization decisions, orchestration.
   ↓
[ Repository ]    JdbcTemplate SQL. No business logic, no DTO knowledge.
   ↓
MySQL
```

**Packages**

```
com.accessbank.ewarrior
├── config/          SecurityConfig, AccessProperties, SeedAdminRunner
├── security/        JwtAuthenticationFilter, JwtService, TokenDenylistRepository,
│                     CurrentUser, SecurityContextHelper
├── auth/            AuthenticationProvider, LocalAuthenticationProvider (dev),
│                     ActiveDirectoryAuthenticationProvider (prod), AuthService
├── storage/         FileStorageService
├── audit/           AuditAction, AuditService, AuditQueryController
├── collaborator/    Collaborator + repository/service/controller
├── card/            CardService, CardController
├── qr/              QrCodeService, QrCodeController
├── product/         Product + repository/service/controller
├── booklet/         BookletService, BookletController
└── publiccard/      PublicCardController — the unauthenticated /c/{id} route
```

**Three tables, not eight.** Card and QR fields live on `Collaborator`; approval state lives on `Product`; the booklet is one row. There is no `Cards`, `QRcodes`, `Categories`, `List`, `ProductList`, or `ActiveList` table. Nothing in the system has a one-to-many relationship that needed splitting out.

**Models are plain records; there is no JPA.** A `RowMapper` in each repository maps columns to fields explicitly. One mapper constant per table, reused by every query in that repository — that convention is what keeps the class and the table in agreement without an `@Entity` annotation doing it automatically.

---

## 2. Authentication

### 2.1 Account creation — linking an AD account

No self-registration, no passwords. A collab admin links an existing AD account to a collaborator record; that link *is* the grant of access.

| Step | What happens |
|---|---|
| 1 | Caller must hold `IsCollaboratorAdmin` or `IsProductAdmin`, else `403` |
| 2 | If either admin flag is being set on the new record, caller must additionally hold `IsProductAdmin` |
| 3 | `existsByAdObjectGuid(guid)` → `409` if already linked |
| 4 | Confirm the GUID still resolves in AD → `404` if not |
| 5 | `INSERT INTO Collaborator (..., AdObjectGuid, IsActive = true, CreatedBy)` |

**Why `AdObjectGuid` and not the username.** `sAMAccountName` and `userPrincipalName` both change when someone's name changes — a marriage, a corrected spelling. Match on either and the collaborator silently cannot log in while the row still looks correct. `objectGUID` is immutable for the life of the account. `AdUserPrincipalName` is cached for display only and is never used to match.

**Why step 2 exists.** Without it a collab admin could edit their own record and set `IsProductAdmin = true` — privilege escalation through the one door account-management endpoints leave open by default.

### 2.2 Login — bind against AD

| Step | What happens |
|---|---|
| 1 | LDAPS bind to the domain controller with the submitted credentials |
| 2 | Bind fails → `401`. The password is discarded — never logged, cached, or stored |
| 3 | Read `objectGUID` from the directory entry |
| 4 | `SELECT * FROM Collaborator WHERE AdObjectGuid = ?` |
| 5 | No row → `403` (valid employee, not enrolled in the platform) |
| 6 | `IsActive = false` → `401` |
| 7 | Issue JWT with claims `sub`, `email`, `isProductAdmin`, `isCollaboratorAdmin`, `jti`, `iat`, `exp` |

**What AD owns now:** password policy, complexity, expiry, lockout, reset. No password column, no BCrypt, no reset flow anywhere in this codebase.

**What it costs:** login depends on the domain controller being reachable. Short connection timeouts, a connection pool rather than per-request binds, LDAPS not LDAP — a plain bind sends the password in cleartext, which is a finding even on an internal network.

### 2.3 Every request afterwards

`JwtAuthenticationFilter` verifies the signature and expiry, checks the `jti` against `RevokedTokens`, then puts a `CurrentUser` (holding both admin flags) into the `SecurityContext`. Any failure leaves the request anonymous; the filter never writes a response itself.

Services then call `security.requireProductAdmin()`, `.requireCollaboratorAdmin()`, or `.requireSelfOrCollaboratorAdmin(id)` rather than re-parsing anything.

### 2.4 Logout

JWTs are stateless, so the server cannot un-issue one. Logout writes the token's `jti` and `exp` into `RevokedTokens`; a nightly job deletes expired rows so the table stays small.

---

## 3. The Card

**The server does not render the card.** The front end builds it as a div and converts it to a PNG (`html2canvas` or `dom-to-image`), then uploads the blob. This keeps image libraries and fonts off the backend.

`CardPath` and `CardIssueDate` are columns on `Collaborator` — one card per person, so a separate table would exist only to hold a foreign key back to a row it can never have twice.

### Upload — the ordering is the whole trick

1. Validate magic bytes (PNG or JPEG). Not the extension, not the declared `Content-Type` — both are client-controlled.
2. Write the new file to `cards/{collaboratorId}_{timestamp}.png`. **Before any database work.**
3. Read the existing `CardPath`, if any.
4. `UPDATE Collaborator SET CardPath = ?, CardIssueDate = NOW()`.
5. **After** the update succeeds, delete the old file.

If step 2 fails, nothing in the database changed. If step 5 fails, you have an orphaned file — wasted bytes, nothing broken. The reverse order gives you a row pointing at a file that doesn't exist, which is a `500` for a real user and needs manual repair.

> The schema has no `CardContentType` column, so the content type is derived from the stored file extension on the way out. Keep the extension meaningful when generating the filename — it is now load-bearing.

### Download

Authorization check, then `FileStorageService.resolve(path)`: join to the storage root, canonicalise, and assert the result is still inside the root before opening. Then stream it.

**The path never leaves the server.** Clients address cards by collaborator ID. An endpoint that opened a client-supplied path would be a directory traversal.

### Invalidation

Any admin update touching `Name`, `Department`, `Role` or `PhoneNumber` clears `CardPath` and deletes the file, because the rendered image was built from the old values. `DELETE /api/collaborators/{id}/card` does the same on demand — useful after a branding change, where the data is fine but every card is stale.

---

## 4. The QR Code

Generated **server-side**, unlike the card — ZXing is small and has no font dependencies.

1. Build the payload URL: `{base-url}/c/{collaboratorId}`.
2. `QRCodeWriter.encode(url, QR_CODE, 512, 512)` → PNG bytes.
3. Write to `qrcodes/{collaboratorId}_{timestamp}.png`, store the path, delete the old file after.

**Why the QR encodes a URL and not the contact data.** If it held the vCard directly, a printed code would be frozen forever — a phone number change would mean reissuing every one. A URL stays valid because the page behind it always reflects the current row.

### The scan counter

`Scans` lives on `Collaborator` and is incremented by the public route, not by the QR endpoints:

```sql
UPDATE Collaborator SET Scans = Scans + 1 WHERE CollaboratorID = ?
```

One atomic statement, so concurrent scans never lose a count — not a read-modify-write in Java.

### The public page

`/c/{id}` is **unauthenticated and reachable from outside the bank network** — the point is that a client can scan it. That makes it the only public surface in the system, so:

- **It must check `IsActive`.** AD disablement stops a leaver logging in, but this route never talks to AD. Without the check, a departed employee's card stays online indefinitely.
- **It must be rate-limited by IP** at the reverse proxy — it is the one route where an outsider can drive load and inflate a counter.
- **It returns only what belongs on a business card.** Never `AdObjectGuid`, never an admin flag.

---

## 5. Products

### 5.1 Category is a text column

`Product.Category` is `TEXT`, not a foreign key to a category table. `GET /api/products/categories` returns `SELECT DISTINCT Category` — the values currently in use, not a managed list.

The trade-off worth knowing: nothing stops "Savings", "savings" and "Saving" from becoming three separate categories in the app's UI. Two cheap mitigations, worth doing at the start rather than after a cleanup:

- Normalise on write — store what the admin typed, but match and group case-insensitively.
- Have the admin UI offer the existing distinct values as suggestions rather than a free text box, so the common path reuses an exact string.

Display order is alphabetical, since there is no `DisplayOrder` column. If the business wants "Savings" above "Insurance" regardless of alphabet, that needs either a column or a hardcoded order list.

### 5.2 Listing

One query, grouped in the service rather than the client:

```sql
SELECT ProductID, Title, Description, Category, State,
       (DocumentPath IS NOT NULL) AS hasDocument
FROM Product
WHERE IsRetired = false
ORDER BY Category, Title
```

The service folds flat rows into nested category objects. Documents are never inlined — that is a separate endpoint, so a list response stays small.

### 5.3 Documents

One PDF per product, stored the same way as the card: file on disk, path in the row.

1. Validate magic bytes start with `%PDF-`. A renamed executable passes an extension check, and this file gets handed to every collaborator's device.
2. Write to `documents/{productId}_{uuid}.pdf` — the server generates the name.
3. Store the path; delete the previous file after.

> There is no `DocumentFilename` column, so the download name is derived from the product title, slugified. Sanitise whatever goes into `Content-Disposition` — a title containing a quote or newline breaks the header, and header injection is why that matters rather than cosmetics.

Product PDFs are meaningfully larger than card images, so give them their own size limit rather than one global multipart cap.

---

## 6. The Approval Workflow

There is no list or batch entity. Each product carries its own state:

```
DRAFT → SUBMITTED → APPROVED
                  ↘ REJECTED
```

**Submit** — caller must be `SubmittedBy`; must currently be `DRAFT`.

**Approve** — caller must hold `IsProductAdmin` **and must not be `SubmittedBy`**:

```java
if (!security.current().productAdmin())
    throw new ForbiddenException("Product administrator rights required");
if (product.submittedBy().equals(security.currentId()))
    throw new ForbiddenException("Cannot approve your own submission");
```

Without that second check the approval step is decoration — the submitter could wave through their own product, and for a bank that is precisely the control an audit asks about.

Approval sets `ApprovedBy` on the row **and** writes an audit entry. The column answers "who approved this, right now" in a single query; the audit log answers "what happened to this product over time", including rejections and re-submissions that the column overwrites. They serve different questions, so having both is not redundancy.

**Editing** is restricted to `DRAFT`. An approved or in-flight product is changed by rejecting or retiring it, not by silently editing content underneath an approval someone already gave.

**"The active list"** is a query, not a table:

```sql
SELECT * FROM Product WHERE State = 'APPROVED' AND IsRetired = false
```

**Retiring** is a soft delete (`IsRetired = true`). A hard delete would break the `SubmittedBy`/`ApprovedBy` foreign keys and erase the record of something the bank once published.

---

## 7. Share Products — the Booklet

The app has a **Share Products** button directly below **Share QR Code**. Both fetch a stored image and hand it to the device share sheet; neither generates anything at share time.

`Booklet` is a single-row table (`ID` always 1). An admin renders the current approved product set client-side — same html-to-image pattern as the card — and uploads it. Every collaborator then shares a byte-identical file.

```
POST /api/booklet  →  write new file, overwrite the row, delete old file after
GET  /api/booklet  →  stream the current file
```

**This keeps no version history.** Each render replaces the last, so there is no record of what the booklet looked like last quarter. That is a deliberate simplification of the earlier per-batch design — worth confirming it is acceptable, since adding history later is a schema change rather than a config change.

**The failure case to design out:** a product gets approved and nobody re-renders the booklet. The app then shows a current product list and a stale share image. Nothing in the schema prevents this. Two options: prompt the admin to re-render on the approve screen, or have `GET /api/booklet` compare `RenderedDate` against the most recent product state change and return a `stale: true` flag the UI can act on.

---

## 8. Account Management — Two Admin Levels

| Action | Who |
|---|---|
| Find an AD account, create a collaborator record | collab admin |
| Edit name, department, role, phone | collab admin |
| Grant or revoke either admin flag | **product admin only** |
| Deactivate / reactivate | collab admin |
| Re-point the AD link | collab admin |
| Approve or reject a product | **product admin only** |
| Update own phone number | the collaborator |

The split matters in both directions: a collab admin can onboard staff and issue cards all day without ever being able to approve a product or promote someone. Account administration and product approval are different trust levels, and a bank is exactly where that distinction gets audited.

### Deactivation, not deletion

`IsActive = false`. Checked at login, on the public `/c/{id}` page, and in the collaborator list (filtered by default). Rows are never deleted — a hard delete breaks the `CreatedBy`, `SubmittedBy` and `ApprovedBy` foreign keys and destroys the audit trail.

An admin cannot deactivate their own account, and cannot deactivate the last active product admin. Either would risk locking the system out with no recovery short of a manual `UPDATE` against production.

### Bootstrapping

The schema migration seeds one row with an `AdObjectGuid` from an environment variable, both admin flags true. That person signs in with normal domain credentials and links everyone else.

No "create the first admin if none exist" endpoint — it becomes exploitable the moment an admin record is deactivated, and it tends to survive into production.

---

## 9. The Audit Trail

One append-only table. No `UPDATE`, no `DELETE` — that is what makes it an audit trail rather than a log.

Call it from the **service method**, after the action succeeded:

```java
audit.record(AuditAction.PRODUCT_APPROVED, "PRODUCT", productId, null);
```

Not from the controller, which does not know what actually changed. Not before the action, or you record things that then roll back.

`AuditService` captures the actor, timestamp and IP automatically from the security context. It runs in its own transaction (`REQUIRES_NEW`) so an audit write survives a rolled-back business transaction — a failed attempt is often the more interesting record. Audit failures are logged at ERROR but never break the user's request.

Keep the action list short. An audit log that records everything gets read by nobody. The test: would someone plausibly ask "who did that, and when?"

---

## 10. Cross-Cutting Concerns

**File storage.** One config property for the root; every stored path is relative to it. Separate size limits for card images, QR codes, product PDFs and the booklet. Validate magic bytes on every upload. **Back up the storage directory on the database's own schedule** — a database-only restore leaves every card, QR code, document and the booklet broken, and that gets discovered during a restore rather than before one.

**Ordering, not transactions, for files.** The filesystem is not transactional. Write new files before the database update, delete old files after it commits.

**Multiple app instances need a shared mount** for the storage root. A card written on instance A is a `404` on instance B otherwise.

**Self-invoked `@Transactional` does nothing.** Calling an annotated method from another method in the same class bypasses the Spring proxy and runs without a transaction, silently. A quiet and common bug.

**Never log the password.** It exists only as a parameter to the LDAP bind. Watch exception handlers especially — one that logs a full request object will leak it.

**Two roles, checked in two places.** `IsCollaboratorAdmin` gates account management; `IsProductAdmin` gates admin-flag changes and product approval. A method that should require the stricter flag but checks the looser one is a real risk with two levels instead of one — worth re-reading every `require*Admin()` call during review, not just when writing it.

---

## 11. End-to-End: A Collaborator's First Login

1. Admin searches AD: `GET /api/directory/search?q=conceicao`.
2. Admin creates the record: `POST /api/collaborators`. Access is granted at this moment — nothing to send the person.
3. App opens → no token → login screen.
4. `POST /api/auth/login` with domain credentials → LDAPS bind → GUID matched → JWT returned.
5. `GET /api/collaborators/me` → profile data.
6. Client renders the card as a div, converts it to PNG.
7. `POST /api/collaborators/me/card` → file written, `CardPath` set.
8. `POST /api/collaborators/qr` → QR generated, `QrCodePath` set.
9. `GET /api/collaborators/qr?collaboratorId=<self>` → client displays it.
10. Someone scans it → `/c/{id}` → `Scans` incremented → card streamed from disk.
11. Browsing products → `GET /api/products/active`, then `GET /api/products/{id}/document`.
12. Tapping **Share Products** → `GET /api/booklet` → stored image handed to the share sheet.

Steps 1–9 happen once. Later opens fetch the stored card and QR rather than regenerating.
