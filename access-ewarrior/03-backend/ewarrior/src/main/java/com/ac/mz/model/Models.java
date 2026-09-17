package com.ac.mz.model;

import java.time.LocalDateTime;

/**
 * Domain records — one per database table. These are what the DAO returns.
 * They are never returned directly from a controller; see Dtos for that.
 */
public final class Models {

    private Models() {}

    /** Collaborator table. Card and QR live here — one of each per collaborator. */
    public record Collaborator(
            Long collaboratorId,
            String adObjectGuid,
            String adUserPrincipalName,
            String name,
            String department,
            String role,
            String phoneNumber,
            String email,
            boolean productAdmin,
            boolean collaboratorAdmin,
            boolean active,
            String cardPath,
            LocalDateTime cardIssueDate,
            String qrCodePath,
            int scans,
            Long createdBy,
            LocalDateTime createdDate
    ) {
        public boolean hasCard()   { return cardPath != null; }
        public boolean hasQrCode() { return qrCodePath != null; }
        /** Either admin flag may manage collaborator accounts. */
        public boolean canManageCollaborators() { return productAdmin || collaboratorAdmin; }
    }

    /** Product table. Carries its own approval state — there is no list entity. */
    public record Product(
            Long productId,
            String title,
            String description,
            String category,
            String documentPath,
            Long submittedBy,
            Long approvedBy,
            String state,
            boolean retired,
            LocalDateTime createdDate
    ) {
        public boolean hasDocument() { return documentPath != null; }
    }

    /** Booklet table — a single row (ID = 1) holding the rendered Share Products image. */
    public record Booklet(
            String bookletPath,
            Long renderedBy,
            LocalDateTime renderedDate
    ) {}

    /** AuditLog table. Append-only. */
    public record AuditEntry(
            Long auditId,
            LocalDateTime occurredAt,
            Long actorId,
            String actorName,
            String action,
            String entityType,
            String entityId,
            String detail,
            String ipAddress
    ) {}

    /** A directory account, from Active Directory in production or the local table in dev. */
    public record DirectoryAccount(
            String objectGuid,
            String username,
            String displayName,
            String email,
            String department,
            String title
    ) {}

    /** Product approval states. Kept as constants rather than an enum so the DB value maps 1:1. */
    public static final class State {
        private State() {}
        public static final String DRAFT     = "DRAFT";
        public static final String SUBMITTED = "SUBMITTED";
        public static final String APPROVED  = "APPROVED";
        public static final String REJECTED  = "REJECTED";
    }

    /** Audit actions. Keep short — a log that records everything gets read by nobody. */
    public enum AuditAction {
        LOGIN_SUCCEEDED, LOGIN_FAILED,
        COLLABORATOR_CREATED, COLLABORATOR_UPDATED,
        COLLABORATOR_DEACTIVATED, COLLABORATOR_REACTIVATED,
        ADMIN_RIGHTS_GRANTED, ADMIN_RIGHTS_REVOKED,
        AD_LINK_CHANGED,
        CARD_UPLOADED, CARD_DELETED, QR_GENERATED,
        PRODUCT_CREATED, PRODUCT_UPDATED, PRODUCT_RETIRED,
        PRODUCT_DOCUMENT_UPLOADED, PRODUCT_DOCUMENT_DELETED,
        PRODUCT_SUBMITTED, PRODUCT_APPROVED, PRODUCT_REJECTED,
        BOOKLET_RENDERED
    }
}
