# Access (E-Warrior App) — API Reference

**Stack:** Spring Boot + JdbcTemplate (MySQL)
**Base URL:** `/api` (except the public card page, at `/c/{id}`)
**Auth:** `Authorization: Bearer <token>` on every endpoint except `POST /api/auth/login` and `GET /c/{id}`

**Roles:** `collab admin` = `IsCollaboratorAdmin`. `product admin` = `IsProductAdmin`. "Owner" = the collaborator the record belongs to.

---

## 1. Endpoint Summary

### Authentication
| Method | Path | Who | Purpose |
|---|---|---|---|
| POST | `/api/auth/login` | public | Authenticate against AD, receive JWT |
| POST | `/api/auth/logout` | authenticated | Revoke the current token |

### Collaborators
| Method | Path | Who | Purpose |
|---|---|---|---|
| GET | `/api/directory/search?q=` | collab admin | Search AD for an account to link |
| POST | `/api/collaborators` | collab admin | Create a record linked to an AD account |
| GET | `/api/collaborators` | collab admin | List collaborators |
| GET | `/api/collaborators/{id}` | collab admin | Fetch one collaborator |
| PUT | `/api/collaborators/{id}` | collab admin | Update details / admin flags |
| PATCH | `/api/collaborators/{id}/status` | collab admin | Deactivate or reactivate |
| PUT | `/api/collaborators/{id}/ad-link` | collab admin | Re-point at a different AD account |
| GET | `/api/collaborators/me` | authenticated | Own profile (populates the card) |
| PUT | `/api/collaborators/me` | owner | Update own phone number |

### Card
| Method | Path | Who | Purpose |
|---|---|---|---|
| POST | `/api/collaborators/me/card` | owner | Upload the rendered card image |
| GET | `/api/collaborators/card?collaboratorId=` | owner or collab admin | Download the card image |
| GET | `/api/collaborators/card/info?collaboratorId=` | owner or collab admin | Card metadata only |
| DELETE | `/api/collaborators/{id}/card` | collab admin | Delete the card, forcing a re-render |

### QR Code
| Method | Path | Who | Purpose |
|---|---|---|---|
| POST | `/api/collaborators/qr` | owner | Generate and store own QR code |
| GET | `/api/collaborators/qr?collaboratorId=` | owner or collab admin | Download the QR image |
| GET | `/c/{collaboratorId}` | **public** | QR target — card page, increments `Scans` |
| GET | `/c/{collaboratorId}/image` | **public** | Card image for that page |

### Products
| Method | Path | Who | Purpose |
|---|---|---|---|
| POST | `/api/products` | collab admin | Create a product (`DRAFT`) |
| GET | `/api/products?category=&state=` | authenticated | List products, grouped by category |
| GET | `/api/products/{id}` | authenticated | Fetch one product |
| PUT | `/api/products/{id}` | submitter (own `DRAFT`) or product admin | Update a product |
| DELETE | `/api/products/{id}` | product admin | Retire a product |
| GET | `/api/products/categories` | authenticated | Distinct category names in use |
| POST | `/api/products/{id}/document` | collab admin | Upload the product PDF |
| GET | `/api/products/{id}/document` | authenticated | Download the product PDF |
| DELETE | `/api/products/{id}/document` | collab admin | Remove the product PDF |

### Approval & Booklet
| Method | Path | Who | Purpose |
|---|---|---|---|
| POST | `/api/products/{id}/submit` | submitter | `DRAFT` → `SUBMITTED` |
| POST | `/api/products/{id}/approve` | product admin, not the submitter | `SUBMITTED` → `APPROVED` |
| POST | `/api/products/{id}/reject` | product admin, not the submitter | `SUBMITTED` → `REJECTED` |
| GET | `/api/products/active` | authenticated | All `APPROVED`, non-retired products |
| POST | `/api/booklet` | collab admin | Store a rendered booklet image |
| GET | `/api/booklet` | authenticated | Download the booklet image (Share Products) |

### Audit
| Method | Path | Who | Purpose |
|---|---|---|---|
| GET | `/api/audit?entityType=&entityId=&limit=` | product admin | Read the audit trail |

---

## 2. Authentication

### POST `/api/auth/login`

**Request**
```json
{
  "username": "wconceicao",
  "password": "string"
}
```

**Response `200`**
```json
{
  "token": "eyJhbGciOi...",
  "expiresIn": 3600,
  "collaboratorId": 101,
  "isProductAdmin": false,
  "isCollaboratorAdmin": false
}
```

**Errors:** `401` invalid credentials or inactive account · `403` valid AD account but no linked collaborator record

### POST `/api/auth/logout`

No body. **Response `204`.**

---

## 3. Collaborators

### GET `/api/directory/search?q=conceicao`

**Response `200`**
```json
[
  {
    "adObjectGuid": "8f14e45f-ceea-467a-9c1d-0b6f0a2b3c4d",
    "username": "wconceicao",
    "displayName": "Walter da Conceição",
    "email": "walter@example.com",
    "department": "IT",
    "title": "DevOps Engineer"
  }
]
```

### POST `/api/collaborators`

**Request**
```json
{
  "adObjectGuid": "8f14e45f-ceea-467a-9c1d-0b6f0a2b3c4d",
  "name": "Walter da Conceição",
  "email": "walter@example.com",
  "department": "IT",
  "role": "DevOps Engineer",
  "phoneNumber": "+258 84 000 0000",
  "isProductAdmin": false,
  "isCollaboratorAdmin": false
}
```

**Response `201`**
```json
{
  "collaboratorId": 101,
  "adObjectGuid": "8f14e45f-ceea-467a-9c1d-0b6f0a2b3c4d",
  "name": "Walter da Conceição",
  "email": "walter@example.com",
  "department": "IT",
  "role": "DevOps Engineer",
  "phoneNumber": "+258 84 000 0000",
  "isProductAdmin": false,
  "isCollaboratorAdmin": false,
  "isActive": true,
  "hasCard": false,
  "hasQrCode": false,
  "scans": 0,
  "createdBy": 100,
  "createdDate": "2026-09-15T10:00:00Z"
}
```

**Errors:** `409` AD account or email already registered · `403` caller is setting an admin flag without being a product admin

### GET `/api/collaborators?includeInactive=false`

**Response `200`**
```json
[
  {
    "collaboratorId": 101,
    "name": "Walter da Conceição",
    "department": "IT",
    "role": "DevOps Engineer",
    "email": "walter@example.com",
    "isProductAdmin": false,
    "isCollaboratorAdmin": false,
    "isActive": true,
    "hasCard": true,
    "scans": 12
  }
]
```

### GET `/api/collaborators/{id}`

Same object shape as the `POST` response above.

### PUT `/api/collaborators/{id}`

All fields optional — only what is sent is changed.

**Request**
```json
{
  "name": "Walter da Conceição",
  "department": "Operations",
  "role": "Head of DevOps",
  "phoneNumber": "+258 84 111 1111",
  "isCollaboratorAdmin": true
}
```

**Response `200`** — the updated collaborator object.

**Errors:** `403` changing either admin flag without being a product admin

> Changing `Name`, `Department`, `Role` or `PhoneNumber` clears `CardPath` and deletes the card file, so the client re-renders on next open.

### PATCH `/api/collaborators/{id}/status`

**Request**
```json
{ "isActive": false }
```

**Response `200`**
```json
{ "collaboratorId": 101, "isActive": false }
```

**Errors:** `403` cannot deactivate your own account, or the last active product admin

### PUT `/api/collaborators/{id}/ad-link`

**Request**
```json
{ "adObjectGuid": "3a7bd3e2-9f04-4c71-b2a8-1d5e6f7a8b9c" }
```

**Response `200`** — the updated collaborator object.

**Errors:** `409` that AD account is already linked elsewhere

### GET `/api/collaborators/me`

**Response `200`**
```json
{
  "collaboratorId": 101,
  "name": "Walter da Conceição",
  "department": "IT",
  "role": "DevOps Engineer",
  "phoneNumber": "+258 84 000 0000",
  "email": "walter@example.com",
  "isProductAdmin": false,
  "isCollaboratorAdmin": false,
  "createdDate": "2026-09-15T10:00:00Z"
}
```

### PUT `/api/collaborators/me`

**Request**
```json
{ "phoneNumber": "+258 84 111 1111" }
```

**Response `200`** — the updated profile.

---

## 4. Card

The app renders the card as a div and converts it to a PNG before upload. The server stores and serves the file.

### POST `/api/collaborators/me/card`

`multipart/form-data`, one part named `file`.

```
POST /api/collaborators/me/card
Content-Type: multipart/form-data; boundary=...
Authorization: Bearer <token>

file: <binary PNG>
```

**Response `200`**
```json
{
  "collaboratorId": 101,
  "issueDate": "2026-09-15T10:05:00Z",
  "cardUrl": "/api/collaborators/card?collaboratorId=101"
}
```

**Errors:** `413` too large · `415` not a PNG or JPEG (checked by magic bytes)

### GET `/api/collaborators/card?collaboratorId=101`

Returns the image itself, not JSON.

**Response `200`**
```
Content-Type: image/png
Content-Disposition: inline; filename="card-101.png"
Cache-Control: private, max-age=300

<binary>
```

**Errors:** `403` not your card and not a collab admin · `404` no card rendered yet

### GET `/api/collaborators/card/info?collaboratorId=101`

**Response `200`**
```json
{
  "collaboratorId": 101,
  "issueDate": "2026-09-15T10:05:00Z",
  "exists": true
}
```

### DELETE `/api/collaborators/{id}/card`

**Response `204`.**

---

## 5. QR Code

### POST `/api/collaborators/qr`

No body. Generates a QR encoding `{base-url}/c/{collaboratorId}`.

**Response `201`**
```json
{
  "collaboratorId": 101,
  "scans": 0,
  "qrUrl": "/api/collaborators/qr?collaboratorId=101"
}
```

### GET `/api/collaborators/qr?collaboratorId=101`

**Response `200`**
```
Content-Type: image/png

<binary>
```

**Errors:** `403` not your QR and not a collab admin · `404` not generated yet

### GET `/c/{collaboratorId}` — public

Increments `Scans`. No authentication.

**Response `200`**
```json
{
  "name": "Walter da Conceição",
  "department": "IT",
  "role": "DevOps Engineer",
  "phoneNumber": "+258 84 000 0000",
  "email": "walter@example.com",
  "cardImageUrl": "/c/101/image"
}
```

**Errors:** `404` not found, or the collaborator is inactive

### GET `/c/{collaboratorId}/image` — public

Returns the card image. `Content-Type: image/png`.

---

## 6. Products

### POST `/api/products`

**Request**
```json
{
  "title": "Access Savings Account",
  "description": "A savings account with monthly interest.",
  "category": "Savings"
}
```

**Response `201`**
```json
{
  "productId": 7,
  "title": "Access Savings Account",
  "description": "A savings account with monthly interest.",
  "category": "Savings",
  "state": "DRAFT",
  "submittedBy": 101,
  "approvedBy": null,
  "hasDocument": false,
  "createdDate": "2026-09-15T11:00:00Z"
}
```

### GET `/api/products?category=Savings&state=APPROVED`

Both params optional. Results are grouped by category.

**Response `200`**
```json
{
  "categories": [
    {
      "category": "Savings",
      "products": [
        {
          "productId": 7,
          "title": "Access Savings Account",
          "description": "A savings account with monthly interest.",
          "state": "APPROVED",
          "hasDocument": true
        }
      ]
    }
  ]
}
```

### GET `/api/products/{id}`

**Response `200`** — same shape as the `POST` response.

### PUT `/api/products/{id}`

**Request**
```json
{
  "title": "Access Savings Account Plus",
  "description": "Updated description.",
  "category": "Savings"
}
```

**Response `200`** — the updated product.

**Errors:** `409` product is not in `DRAFT` · `403` not the submitter and not a product admin

### DELETE `/api/products/{id}`

Soft delete — sets `IsRetired = true`. **Response `204`.**

### GET `/api/products/categories`

**Response `200`**
```json
["Savings", "Loans", "Cards", "Insurance"]
```

> `Category` is a text column on `Product`, so this returns the distinct values currently in use rather than a managed list. Normalise on write — store what the admin typed, match case-insensitively — or the list drifts into "Savings", "savings" and "Saving" as separate entries.

### POST `/api/products/{id}/document`

`multipart/form-data`, one part named `file`.

**Response `200`**
```json
{
  "productId": 7,
  "hasDocument": true
}
```

**Errors:** `415` not a PDF (magic bytes `%PDF-`) · `413` too large

### GET `/api/products/{id}/document`

**Response `200`**
```
Content-Type: application/pdf
Content-Disposition: attachment; filename="access-savings-account.pdf"

<binary>
```

> The schema stores no original filename, so the download name is derived from the product title (slugified). If the uploaded filename needs to survive, that needs a column.

### DELETE `/api/products/{id}/document`

**Response `204`.**

---

## 7. Approval & Booklet

### POST `/api/products/{id}/submit`

No body. `DRAFT` → `SUBMITTED`.

**Response `200`**
```json
{ "productId": 7, "state": "SUBMITTED" }
```

**Errors:** `403` not the submitter · `409` not currently `DRAFT`

### POST `/api/products/{id}/approve`

No body. `SUBMITTED` → `APPROVED`, sets `ApprovedBy`.

**Response `200`**
```json
{
  "productId": 7,
  "state": "APPROVED",
  "approvedBy": 100
}
```

**Errors:** `403` not a product admin, or you are the submitter · `409` not currently `SUBMITTED`

### POST `/api/products/{id}/reject`

No body. `SUBMITTED` → `REJECTED`.

**Response `200`**
```json
{ "productId": 7, "state": "REJECTED" }
```

### GET `/api/products/active`

Everything with `State = 'APPROVED'` and `IsRetired = false`, grouped by category. Same response shape as `GET /api/products`.

### POST `/api/booklet`

`multipart/form-data`, one part named `file`. Replaces the single stored booklet image.

**Response `200`**
```json
{
  "renderedBy": 101,
  "renderedDate": "2026-09-15T12:00:00Z"
}
```

### GET `/api/booklet`

What the **Share Products** button calls.

**Response `200`**
```
Content-Type: image/png

<binary>
```

**Errors:** `404` nothing rendered yet

---

## 8. Audit

### GET `/api/audit?entityType=PRODUCT&entityId=7&limit=100`

**Response `200`**
```json
[
  {
    "auditId": 4412,
    "occurredAt": "2026-09-15T11:30:00Z",
    "actorId": 100,
    "actorName": "Ana Mabote",
    "action": "PRODUCT_APPROVED",
    "entityType": "PRODUCT",
    "entityId": "7",
    "detail": null,
    "ipAddress": "10.20.1.44"
  }
]
```

---

## 9. Conventions

- **Content-Type:** `application/json`, except uploads (`multipart/form-data`) and downloads (`image/png`, `application/pdf`).
- **Dates:** ISO-8601 UTC.
- **Files:** cards, QR codes, product PDFs and the booklet are files on disk. The database stores a relative path; the path never appears in a request or response.

**Error body** (all errors):
```json
{
  "timestamp": "2026-09-15T10:00:00Z",
  "status": 409,
  "error": "Conflict",
  "message": "Product is not in DRAFT state",
  "path": "/api/products/7"
}
```
