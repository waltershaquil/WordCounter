# Access (E-Warrior App) — Project Bundle

Everything for the Access backend: documentation, planning, and the code.

---

## 01-documentation

| File | What it is |
|---|---|
| `access-api-documentation.docx` | API reference — every endpoint, who can call it, and request/response examples |
| `access-functional-rundown.docx` | How each flow works layer by layer, and why the design is shaped the way it is |
| `*.md` | The same two documents in Markdown, for editing or version control |

---

## 02-planning

| File | Audience | Delivery date |
|---|---|---|
| `access-development-plan.docx` | Management | Formal plan — scope, status, dependencies, risks |
| `access-timeline-pmo.xlsx` | PMO | 27 October 2026 (committed) |
| `access-timeline-internal.xlsx` | You | 30 September 2026 (internal stretch target) |

Each timeline workbook has two sheets: **Timeline** (features and dates) and **Feature Details** (what each item covers, in plain language).

The internal timeline deliberately excludes Active Directory integration, automated testing, security review and deployment — those live only in the committed plan. It is not a smaller version of the same scope.

---

## 03-backend

The Spring Boot project. Package root `com.ac.mz`, 14 Java files.

```
controller/   ApiControllers, FileController, HomeController
services/     Services          all business logic
helpers/      Helpers           permission checks, audit, state machine
model/        Models, Dtos      domain records / API shapes
dao/          Daos              all SQL, no mappers
mapper/       RowMappers        every row mapper, own package
utility/      Utilities         JWT, file storage, QR, text
config/       AppConfig         properties, seed admin
websecurity/  WebSecurity       JWT filter, security config
auth/         AuthProviders     local (dev) + Active Directory (prod)
exception/    Exceptions        types + global handler
```

See `03-backend/ewarrior/README.md` for setup, the dev login, and the gotchas worth reading before you start.

It has not been compiled — Maven wasn't available where it was written. Run `mvn clean compile` first.

---

## Before anything else

Three things depend on other people and have a lead time measured in weeks. Start them now, in parallel with development:

1. **Active Directory details** from IT — LDAPS endpoint and certificate, a service account for directory search, the search base, and the expected username format. Also ask whether a test AD exists.
2. **Public reachability of `/c/{id}`** — the QR card page must open from outside the bank network. That needs infrastructure provisioning, a public TLS certificate, and an infosec review. This review can run longer than the build itself.
3. **Card and booklet design sign-off** from the business.

The backend can be built without any of them — development-mode login covers item 1 — but none of them can be skipped before go-live.
