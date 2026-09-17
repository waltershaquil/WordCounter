-- Access (E-Warrior App) — initial schema
-- No password columns: authentication is delegated to Active Directory.

CREATE TABLE Collaborator (
    CollaboratorID       BIGINT AUTO_INCREMENT PRIMARY KEY,
    AdObjectGuid         CHAR(36)     NOT NULL,
    AdUserPrincipalName  VARCHAR(255) NULL,
    Name                 VARCHAR(255) NOT NULL,
    Department           VARCHAR(255) NULL,
    Role                 VARCHAR(255) NULL,
    PhoneNumber          VARCHAR(50)  NULL,
    Email                VARCHAR(255) NOT NULL,
    IsProductAdmin       BOOLEAN      NOT NULL DEFAULT FALSE,
    IsCollaboratorAdmin  BOOLEAN      NOT NULL DEFAULT FALSE,
    IsActive             BOOLEAN      NOT NULL DEFAULT TRUE,
    CardPath             VARCHAR(512) NULL,
    CardIssueDate        DATETIME     NULL,
    QrCodePath           VARCHAR(512) NULL,
    Scans                INT          NOT NULL DEFAULT 0,
    CreatedBy            BIGINT       NULL,
    CreatedDate          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_collaborator_ad_guid UNIQUE (AdObjectGuid),
    CONSTRAINT uq_collaborator_email   UNIQUE (Email),
    CONSTRAINT fk_collaborator_creator FOREIGN KEY (CreatedBy) REFERENCES Collaborator (CollaboratorID)
) ENGINE = InnoDB;

CREATE TABLE Product (
    ProductID     BIGINT AUTO_INCREMENT PRIMARY KEY,
    Title         VARCHAR(255) NOT NULL,
    Description   TEXT         NULL,
    Category      VARCHAR(255) NOT NULL,
    DocumentPath  VARCHAR(512) NULL,
    SubmittedBy   BIGINT       NOT NULL,
    ApprovedBy    BIGINT       NULL,
    State         VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    IsRetired     BOOLEAN      NOT NULL DEFAULT FALSE,
    CreatedDate   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_product_submitter FOREIGN KEY (SubmittedBy) REFERENCES Collaborator (CollaboratorID),
    CONSTRAINT fk_product_approver  FOREIGN KEY (ApprovedBy)  REFERENCES Collaborator (CollaboratorID),
    CONSTRAINT ck_product_state CHECK (State IN ('DRAFT','SUBMITTED','APPROVED','REJECTED'))
) ENGINE = InnoDB;

CREATE INDEX ix_product_category ON Product (Category, IsRetired);
CREATE INDEX ix_product_state    ON Product (State, IsRetired);

-- Single row (ID always 1): the rendered "Share Products" image.
CREATE TABLE Booklet (
    ID           TINYINT      NOT NULL PRIMARY KEY,
    BookletPath  VARCHAR(512) NOT NULL,
    RenderedBy   BIGINT       NOT NULL,
    RenderedDate DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_booklet_single CHECK (ID = 1),
    CONSTRAINT fk_booklet_renderer FOREIGN KEY (RenderedBy) REFERENCES Collaborator (CollaboratorID)
) ENGINE = InnoDB;

-- Revoked JWT ids. Purged by a scheduled job once expired.
CREATE TABLE RevokedToken (
    Jti       CHAR(36) NOT NULL PRIMARY KEY,
    ExpiresAt DATETIME NOT NULL
) ENGINE = InnoDB;

CREATE INDEX ix_revokedtoken_expiry ON RevokedToken (ExpiresAt);

-- Append-only audit trail. No UPDATE or DELETE is ever issued against this table.
CREATE TABLE AuditLog (
    AuditID     BIGINT AUTO_INCREMENT PRIMARY KEY,
    OccurredAt  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ActorID     BIGINT       NULL,
    ActorName   VARCHAR(255) NULL,
    Action      VARCHAR(64)  NOT NULL,
    EntityType  VARCHAR(64)  NOT NULL,
    EntityID    VARCHAR(64)  NULL,
    Detail      TEXT         NULL,
    IpAddress   VARCHAR(45)  NULL
) ENGINE = InnoDB;

CREATE INDEX ix_auditlog_occurred ON AuditLog (OccurredAt);
CREATE INDEX ix_auditlog_entity   ON AuditLog (EntityType, EntityID);
