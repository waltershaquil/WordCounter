-- DEVELOPMENT SUPPORT ONLY.
-- Read by LocalAuthenticationProvider, active only when access.auth.provider=local (dev profile).
-- DO NOT run this migration in production.

CREATE TABLE IF NOT EXISTS LocalCredential (
    ObjectGuid   CHAR(36)     NOT NULL PRIMARY KEY,
    Username     VARCHAR(100) NOT NULL,
    PasswordHash VARCHAR(255) NOT NULL,
    DisplayName  VARCHAR(255) NOT NULL,
    Email        VARCHAR(255) NOT NULL,
    Department   VARCHAR(255) NULL,
    Title        VARCHAR(255) NULL,
    CONSTRAINT uq_localcredential_username UNIQUE (Username)
) ENGINE = InnoDB;

-- Password for both accounts is: password123  (BCrypt cost 12)
INSERT IGNORE INTO LocalCredential
    (ObjectGuid, Username, PasswordHash, DisplayName, Email, Department, Title)
VALUES
    ('8f14e45f-ceea-467a-9c1d-0b6f0a2b3c4d', 'admin.dev',
     '$2a$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewKyF9/8Kq2gWq3e',
     'Dev Administrator', 'admin.dev@example.com', 'IT', 'Platform Administrator'),
    ('3a7bd3e2-9f04-4c71-b2a8-1d5e6f7a8b9c', 'user.dev',
     '$2a$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewKyF9/8Kq2gWq3e',
     'Dev Collaborator', 'user.dev@example.com', 'Retail', 'Relationship Manager');
