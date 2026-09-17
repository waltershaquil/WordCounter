package com.ac.mz.dao;

import com.ac.mz.mapper.RowMappers;
import com.ac.mz.model.Models;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Every database access object, in one file.
 *
 * SQL lives here and nothing else does — no business rules, no DTOs, no authorization.
 * Row mappers are NOT defined here; they live in com.ac.mz.mapper.RowMappers and are
 * referenced by name, so the same mapping is shared by every query against a table.
 *
 * Each DAO is a static nested class annotated @Repository, so Spring picks them all up
 * from component scanning exactly as if they were separate files.
 */
public final class Daos {

    private Daos() {}

    // ------------------------------------------------------------------ Collaborator

    @Repository
    public static class CollaboratorDao {

        private final JdbcTemplate jdbc;

        public CollaboratorDao(JdbcTemplate jdbc) { this.jdbc = jdbc; }

        public Optional<Models.Collaborator> findById(Long id) {
            return jdbc.query("SELECT * FROM Collaborator WHERE CollaboratorID = ?",
                    RowMappers.COLLABORATOR, id).stream().findFirst();
        }

        public Optional<Models.Collaborator> findByAdObjectGuid(String guid) {
            return jdbc.query("SELECT * FROM Collaborator WHERE AdObjectGuid = ?",
                    RowMappers.COLLABORATOR, guid).stream().findFirst();
        }

        public boolean existsByAdObjectGuid(String guid) {
            Integer n = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM Collaborator WHERE AdObjectGuid = ?", Integer.class, guid);
            return n != null && n > 0;
        }

        public boolean existsByEmail(String email) {
            Integer n = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM Collaborator WHERE Email = ?", Integer.class, email);
            return n != null && n > 0;
        }

        public List<Models.Collaborator> findAll(boolean includeInactive) {
            String sql = includeInactive
                    ? "SELECT * FROM Collaborator ORDER BY Name"
                    : "SELECT * FROM Collaborator WHERE IsActive = TRUE ORDER BY Name";
            return jdbc.query(sql, RowMappers.COLLABORATOR);
        }

        public Long insert(Models.Collaborator c) {
            KeyHolder keys = new GeneratedKeyHolder();
            jdbc.update(conn -> {
                PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO Collaborator " +
                        "(AdObjectGuid, AdUserPrincipalName, Name, Department, Role, PhoneNumber, Email, " +
                        " IsProductAdmin, IsCollaboratorAdmin, IsActive, CreatedBy) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE, ?)",
                        Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, c.adObjectGuid());
                ps.setString(2, c.adUserPrincipalName());
                ps.setString(3, c.name());
                ps.setString(4, c.department());
                ps.setString(5, c.role());
                ps.setString(6, c.phoneNumber());
                ps.setString(7, c.email());
                ps.setBoolean(8, c.productAdmin());
                ps.setBoolean(9, c.collaboratorAdmin());
                if (c.createdBy() == null) ps.setNull(10, Types.BIGINT); else ps.setLong(10, c.createdBy());
                return ps;
            }, keys);
            return keys.getKey().longValue();
        }

        /** COALESCE means a null argument leaves the existing value alone — partial updates for free. */
        public int update(Long id, String name, String department, String role, String phone,
                          Boolean productAdmin, Boolean collaboratorAdmin) {
            return jdbc.update(
                    "UPDATE Collaborator SET " +
                    "Name = COALESCE(?, Name), Department = COALESCE(?, Department), " +
                    "Role = COALESCE(?, Role), PhoneNumber = COALESCE(?, PhoneNumber), " +
                    "IsProductAdmin = COALESCE(?, IsProductAdmin), " +
                    "IsCollaboratorAdmin = COALESCE(?, IsCollaboratorAdmin) " +
                    "WHERE CollaboratorID = ?",
                    name, department, role, phone, productAdmin, collaboratorAdmin, id);
        }

        public int updatePhone(Long id, String phone) {
            return jdbc.update("UPDATE Collaborator SET PhoneNumber = ? WHERE CollaboratorID = ?", phone, id);
        }

        public int setActive(Long id, boolean active) {
            return jdbc.update("UPDATE Collaborator SET IsActive = ? WHERE CollaboratorID = ?", active, id);
        }

        public int updateAdLink(Long id, String guid, String upn) {
            return jdbc.update(
                    "UPDATE Collaborator SET AdObjectGuid = ?, AdUserPrincipalName = ? WHERE CollaboratorID = ?",
                    guid, upn, id);
        }

        public void updateCard(Long id, String path) {
            jdbc.update("UPDATE Collaborator SET CardPath = ?, CardIssueDate = NOW() WHERE CollaboratorID = ?",
                    path, id);
        }

        public void clearCard(Long id) {
            jdbc.update("UPDATE Collaborator SET CardPath = NULL, CardIssueDate = NULL WHERE CollaboratorID = ?", id);
        }

        public void updateQrCode(Long id, String path) {
            jdbc.update("UPDATE Collaborator SET QrCodePath = ? WHERE CollaboratorID = ?", path, id);
        }

        /** One atomic statement, so concurrent scans never lose a count. */
        public void incrementScans(Long id) {
            jdbc.update("UPDATE Collaborator SET Scans = Scans + 1 WHERE CollaboratorID = ?", id);
        }

        public long countActiveProductAdmins() {
            Long n = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM Collaborator WHERE IsProductAdmin = TRUE AND IsActive = TRUE", Long.class);
            return n == null ? 0 : n;
        }

        public void insertSeedAdmin(String guid, String name, String email) {
            jdbc.update("INSERT INTO Collaborator (AdObjectGuid, Name, Email, IsProductAdmin, IsCollaboratorAdmin) " +
                        "VALUES (?, ?, ?, TRUE, TRUE)", guid, name, email);
        }
    }

    // ---------------------------------------------------------------------- Product

    @Repository
    public static class ProductDao {

        private final JdbcTemplate jdbc;

        public ProductDao(JdbcTemplate jdbc) { this.jdbc = jdbc; }

        public Optional<Models.Product> findById(Long id) {
            return jdbc.query("SELECT * FROM Product WHERE ProductID = ?", RowMappers.PRODUCT, id)
                    .stream().findFirst();
        }

        /** Both filters optional; nulls are ignored by the OR-IS-NULL pattern. */
        public List<Models.Product> find(String category, String state) {
            return jdbc.query(
                    "SELECT * FROM Product WHERE IsRetired = FALSE " +
                    "AND (? IS NULL OR LOWER(Category) = LOWER(?)) " +
                    "AND (? IS NULL OR State = ?) " +
                    "ORDER BY Category, Title",
                    RowMappers.PRODUCT, category, category, state, state);
        }

        public List<Models.Product> findApproved() {
            return jdbc.query(
                    "SELECT * FROM Product WHERE IsRetired = FALSE AND State = 'APPROVED' ORDER BY Category, Title",
                    RowMappers.PRODUCT);
        }

        /**
         * Category is a free-text column, so this is the distinct set actually in use
         * rather than a managed list. Grouped case-insensitively so "Savings" and
         * "savings" do not appear as two categories in the app.
         */
        public List<String> findCategories() {
            return jdbc.queryForList(
                    "SELECT Category FROM Product WHERE IsRetired = FALSE " +
                    "GROUP BY LOWER(Category) ORDER BY MIN(Category)", String.class);
        }

        public Long insert(String title, String description, String category, Long submittedBy) {
            KeyHolder keys = new GeneratedKeyHolder();
            jdbc.update(conn -> {
                PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO Product (Title, Description, Category, SubmittedBy, State) " +
                        "VALUES (?, ?, ?, ?, 'DRAFT')", Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, title);
                ps.setString(2, description);
                ps.setString(3, category);
                ps.setLong(4, submittedBy);
                return ps;
            }, keys);
            return keys.getKey().longValue();
        }

        public int update(Long id, String title, String description, String category) {
            return jdbc.update(
                    "UPDATE Product SET Title = COALESCE(?, Title), " +
                    "Description = COALESCE(?, Description), Category = COALESCE(?, Category) " +
                    "WHERE ProductID = ?",
                    title, description, category, id);
        }

        public int setState(Long id, String state) {
            return jdbc.update("UPDATE Product SET State = ?, ApprovedBy = NULL WHERE ProductID = ?", state, id);
        }

        public int approve(Long id, Long approvedBy) {
            return jdbc.update("UPDATE Product SET State = 'APPROVED', ApprovedBy = ? WHERE ProductID = ?",
                    approvedBy, id);
        }

        public int reject(Long id, Long rejectedBy) {
            return jdbc.update("UPDATE Product SET State = 'REJECTED', ApprovedBy = ? WHERE ProductID = ?",
                    rejectedBy, id);
        }

        /** Soft delete — a hard delete would break the SubmittedBy/ApprovedBy foreign keys. */
        public int retire(Long id) {
            return jdbc.update("UPDATE Product SET IsRetired = TRUE WHERE ProductID = ?", id);
        }

        public void updateDocument(Long id, String path) {
            jdbc.update("UPDATE Product SET DocumentPath = ? WHERE ProductID = ?", path, id);
        }

        public void clearDocument(Long id) {
            jdbc.update("UPDATE Product SET DocumentPath = NULL WHERE ProductID = ?", id);
        }
    }

    // ---------------------------------------------------------------------- Booklet

    @Repository
    public static class BookletDao {

        private final JdbcTemplate jdbc;

        public BookletDao(JdbcTemplate jdbc) { this.jdbc = jdbc; }

        public Optional<Models.Booklet> find() {
            return jdbc.query("SELECT * FROM Booklet WHERE ID = 1", RowMappers.BOOKLET)
                    .stream().findFirst();
        }

        /** Single-row table: insert on first render, overwrite afterwards. */
        public void upsert(String path, Long renderedBy) {
            jdbc.update(
                    "INSERT INTO Booklet (ID, BookletPath, RenderedBy, RenderedDate) VALUES (1, ?, ?, NOW()) " +
                    "ON DUPLICATE KEY UPDATE BookletPath = VALUES(BookletPath), " +
                    "RenderedBy = VALUES(RenderedBy), RenderedDate = NOW()",
                    path, renderedBy);
        }
    }

    // ------------------------------------------------------------------- Audit log

    @Repository
    public static class AuditDao {

        private final JdbcTemplate jdbc;

        public AuditDao(JdbcTemplate jdbc) { this.jdbc = jdbc; }

        /** Insert only. There is deliberately no update or delete method on this DAO. */
        public void insert(Long actorId, String actorName, String action,
                           String entityType, String entityId, String detail, String ip) {
            jdbc.update(
                    "INSERT INTO AuditLog (ActorID, ActorName, Action, EntityType, EntityID, Detail, IpAddress) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    actorId, actorName, action, entityType, entityId, detail, ip);
        }

        public List<Models.AuditEntry> find(String entityType, String entityId, int limit) {
            if (entityType != null && entityId != null) {
                return jdbc.query(
                        "SELECT * FROM AuditLog WHERE EntityType = ? AND EntityID = ? " +
                        "ORDER BY OccurredAt DESC LIMIT ?",
                        RowMappers.AUDIT, entityType, entityId, limit);
            }
            return jdbc.query("SELECT * FROM AuditLog ORDER BY OccurredAt DESC LIMIT ?",
                    RowMappers.AUDIT, limit);
        }
    }

    // ---------------------------------------------------------------- Revoked tokens

    @Repository
    public static class TokenDao {

        private final JdbcTemplate jdbc;

        public TokenDao(JdbcTemplate jdbc) { this.jdbc = jdbc; }

        public void revoke(String jti, Instant expiresAt) {
            jdbc.update("INSERT IGNORE INTO RevokedToken (Jti, ExpiresAt) VALUES (?, ?)",
                    jti, Timestamp.from(expiresAt));
        }

        public boolean isRevoked(String jti) {
            Integer n = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM RevokedToken WHERE Jti = ?", Integer.class, jti);
            return n != null && n > 0;
        }

        /** Keeps the table bounded by tokens issued in the last TTL window. */
        @Scheduled(cron = "0 0 3 * * *")
        public void purgeExpired() {
            jdbc.update("DELETE FROM RevokedToken WHERE ExpiresAt < NOW()");
        }
    }

    // ------------------------------------------------- Local credentials (dev only)

    @Repository
    public static class LocalCredentialDao {

        private final JdbcTemplate jdbc;

        public LocalCredentialDao(JdbcTemplate jdbc) { this.jdbc = jdbc; }

        public Optional<String> findPasswordHash(String username) {
            return jdbc.queryForList("SELECT PasswordHash FROM LocalCredential WHERE Username = ?",
                    String.class, username).stream().findFirst();
        }

        public Optional<Models.DirectoryAccount> findByUsername(String username) {
            return jdbc.query("SELECT * FROM LocalCredential WHERE Username = ?",
                    RowMappers.DIRECTORY_ACCOUNT, username).stream().findFirst();
        }

        public Optional<Models.DirectoryAccount> findByObjectGuid(String guid) {
            return jdbc.query("SELECT * FROM LocalCredential WHERE ObjectGuid = ?",
                    RowMappers.DIRECTORY_ACCOUNT, guid).stream().findFirst();
        }

        public List<Models.DirectoryAccount> search(String query, int limit) {
            return jdbc.query(
                    "SELECT * FROM LocalCredential WHERE DisplayName LIKE ? OR Username LIKE ? LIMIT ?",
                    RowMappers.DIRECTORY_ACCOUNT, "%" + query + "%", "%" + query + "%", limit);
        }
    }
}
