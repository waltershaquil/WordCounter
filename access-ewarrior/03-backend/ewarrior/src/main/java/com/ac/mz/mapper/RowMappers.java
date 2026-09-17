package com.ac.mz.mapper;

import com.ac.mz.model.Models;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;

/**
 * Every RowMapper in the application, in one place and outside the DAO.
 *
 * This is the layer that connects a database column to a Java field. There is no JPA
 * here — no @Entity, no @Column — so the mapping is explicit and lives here rather than
 * being inferred at runtime. One mapper per table, reused by every query that reads it,
 * which is what keeps two queries against the same table from drifting apart.
 */
public final class RowMappers {

    private RowMappers() {}

    public static final RowMapper<Models.Collaborator> COLLABORATOR = (rs, i) -> new Models.Collaborator(
            rs.getLong("CollaboratorID"),
            rs.getString("AdObjectGuid"),
            rs.getString("AdUserPrincipalName"),
            rs.getString("Name"),
            rs.getString("Department"),
            rs.getString("Role"),
            rs.getString("PhoneNumber"),
            rs.getString("Email"),
            rs.getBoolean("IsProductAdmin"),
            rs.getBoolean("IsCollaboratorAdmin"),
            rs.getBoolean("IsActive"),
            rs.getString("CardPath"),
            dateOrNull(rs, "CardIssueDate"),
            rs.getString("QrCodePath"),
            rs.getInt("Scans"),
            longOrNull(rs, "CreatedBy"),
            dateOrNull(rs, "CreatedDate"));

    public static final RowMapper<Models.Product> PRODUCT = (rs, i) -> new Models.Product(
            rs.getLong("ProductID"),
            rs.getString("Title"),
            rs.getString("Description"),
            rs.getString("Category"),
            rs.getString("DocumentPath"),
            rs.getLong("SubmittedBy"),
            longOrNull(rs, "ApprovedBy"),
            rs.getString("State"),
            rs.getBoolean("IsRetired"),
            dateOrNull(rs, "CreatedDate"));

    public static final RowMapper<Models.Booklet> BOOKLET = (rs, i) -> new Models.Booklet(
            rs.getString("BookletPath"),
            rs.getLong("RenderedBy"),
            dateOrNull(rs, "RenderedDate"));

    public static final RowMapper<Models.AuditEntry> AUDIT = (rs, i) -> new Models.AuditEntry(
            rs.getLong("AuditID"),
            dateOrNull(rs, "OccurredAt"),
            longOrNull(rs, "ActorID"),
            rs.getString("ActorName"),
            rs.getString("Action"),
            rs.getString("EntityType"),
            rs.getString("EntityID"),
            rs.getString("Detail"),
            rs.getString("IpAddress"));

    /** Used by the local (development) authentication provider. */
    public static final RowMapper<Models.DirectoryAccount> DIRECTORY_ACCOUNT = (rs, i) ->
            new Models.DirectoryAccount(
                    rs.getString("ObjectGuid"),
                    rs.getString("Username"),
                    rs.getString("DisplayName"),
                    rs.getString("Email"),
                    rs.getString("Department"),
                    rs.getString("Title"));

    // ---- helpers: a null DATETIME or BIGINT must not become 1970 or 0 ----

    private static java.time.LocalDateTime dateOrNull(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toLocalDateTime();
    }

    private static Long longOrNull(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
