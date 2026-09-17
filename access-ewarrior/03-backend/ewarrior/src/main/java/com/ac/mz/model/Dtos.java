package com.ac.mz.model;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import java.time.LocalDateTime;
import java.util.List;

/**
 * What the API accepts and returns.
 *
 * DTOs are separate from Models on purpose: a Model is whatever the database row
 * contains, a DTO is what we are willing to expose. That separation is what
 * guarantees AdObjectGuid never reaches a public response — the DTO has no field for it.
 */
public final class Dtos {

    private Dtos() {}

    // --------------------------------------------------------------- auth

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}

    public record LoginResponse(String token, long expiresIn, Long collaboratorId,
                                boolean isProductAdmin, boolean isCollaboratorAdmin) {}

    // ------------------------------------------------------- collaborators

    public record CreateCollaboratorRequest(
            @NotBlank String adObjectGuid,
            @NotBlank String name,
            @Email @NotBlank String email,
            String department,
            String role,
            String phoneNumber,
            Boolean isProductAdmin,
            Boolean isCollaboratorAdmin
    ) {}

    public record UpdateCollaboratorRequest(
            String name, String department, String role, String phoneNumber,
            Boolean isProductAdmin, Boolean isCollaboratorAdmin
    ) {}

    public record UpdateOwnProfileRequest(String phoneNumber) {}

    public record StatusRequest(boolean isActive) {}

    public record AdLinkRequest(@NotBlank String adObjectGuid) {}

    public record DirectoryAccountResponse(String adObjectGuid, String username, String displayName,
                                           String email, String department, String title) {
        public static DirectoryAccountResponse from(Models.DirectoryAccount a) {
            return new DirectoryAccountResponse(a.objectGuid(), a.username(), a.displayName(),
                    a.email(), a.department(), a.title());
        }
    }

    /** What a collaborator sees about themselves. No AD link, no account state. */
    public record ProfileResponse(Long collaboratorId, String name, String department, String role,
                                  String phoneNumber, String email,
                                  boolean isProductAdmin, boolean isCollaboratorAdmin,
                                  LocalDateTime createdDate) {
        public static ProfileResponse from(Models.Collaborator c) {
            return new ProfileResponse(c.collaboratorId(), c.name(), c.department(), c.role(),
                    c.phoneNumber(), c.email(), c.productAdmin(), c.collaboratorAdmin(), c.createdDate());
        }
    }

    /** What an admin sees. Adds the AD link, account state and asset presence. */
    public record CollaboratorResponse(Long collaboratorId, String adObjectGuid, String name,
                                       String department, String role, String phoneNumber, String email,
                                       boolean isProductAdmin, boolean isCollaboratorAdmin, boolean isActive,
                                       boolean hasCard, boolean hasQrCode, int scans,
                                       Long createdBy, LocalDateTime createdDate) {
        public static CollaboratorResponse from(Models.Collaborator c) {
            return new CollaboratorResponse(c.collaboratorId(), c.adObjectGuid(), c.name(), c.department(),
                    c.role(), c.phoneNumber(), c.email(), c.productAdmin(), c.collaboratorAdmin(),
                    c.active(), c.hasCard(), c.hasQrCode(), c.scans(), c.createdBy(), c.createdDate());
        }
    }

    public record StatusResponse(Long collaboratorId, boolean isActive) {}

    // ---------------------------------------------------------- card & qr

    public record CardResponse(Long collaboratorId, LocalDateTime issueDate, String cardUrl) {}

    public record CardInfoResponse(Long collaboratorId, LocalDateTime issueDate, boolean exists) {}

    public record QrResponse(Long collaboratorId, int scans, String qrUrl) {}

    /** The public card page. Only what belongs on a business card — never the AD GUID. */
    public record PublicCardResponse(String name, String department, String role,
                                     String phoneNumber, String email, String cardImageUrl) {}

    // ----------------------------------------------------------- products

    public record CreateProductRequest(@NotBlank String title, String description,
                                       @NotBlank String category) {}

    public record UpdateProductRequest(String title, String description, String category) {}

    public record ProductResponse(Long productId, String title, String description, String category,
                                  String state, Long submittedBy, Long approvedBy,
                                  boolean hasDocument, LocalDateTime createdDate) {
        public static ProductResponse from(Models.Product p) {
            return new ProductResponse(p.productId(), p.title(), p.description(), p.category(),
                    p.state(), p.submittedBy(), p.approvedBy(), p.hasDocument(), p.createdDate());
        }
    }

    public record ProductSummary(Long productId, String title, String description,
                                 String state, boolean hasDocument) {
        public static ProductSummary from(Models.Product p) {
            return new ProductSummary(p.productId(), p.title(), p.description(), p.state(), p.hasDocument());
        }
    }

    public record CategoryGroup(String category, List<ProductSummary> products) {}

    /** Grouped server-side so category ordering lives in one place, not in each client. */
    public record GroupedProductsResponse(List<CategoryGroup> categories) {}

    public record ProductStateResponse(Long productId, String state, Long approvedBy) {}

    public record DocumentResponse(Long productId, boolean hasDocument) {}

    // ------------------------------------------------------------ booklet

    public record BookletResponse(Long renderedBy, LocalDateTime renderedDate) {}

    // -------------------------------------------------------------- audit

    public record AuditResponse(Long auditId, LocalDateTime occurredAt, Long actorId, String actorName,
                                String action, String entityType, String entityId,
                                String detail, String ipAddress) {
        public static AuditResponse from(Models.AuditEntry e) {
            return new AuditResponse(e.auditId(), e.occurredAt(), e.actorId(), e.actorName(),
                    e.action(), e.entityType(), e.entityId(), e.detail(), e.ipAddress());
        }
    }
}
