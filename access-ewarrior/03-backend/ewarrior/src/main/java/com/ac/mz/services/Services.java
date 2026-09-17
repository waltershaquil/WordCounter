package com.ac.mz.services;

import com.ac.mz.auth.AuthProviders;
import com.ac.mz.config.AppConfig;
import com.ac.mz.dao.Daos;
import com.ac.mz.exception.Exceptions;
import com.ac.mz.helpers.Helpers;
import com.ac.mz.model.Dtos;
import com.ac.mz.model.Models;
import com.ac.mz.utility.Utilities;
import com.ac.mz.websecurity.WebSecurity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * All business logic, in one file.
 *
 * Rules live here — never in a controller, never in a DAO. Each service is a static
 * nested class annotated @Service, so component scanning picks them up exactly as if
 * they were separate files.
 */
public final class Services {

    private Services() {}

    // ------------------------------------------------------------------- Auth

    @Service
    public static class AuthService {

        private final AuthProviders.AuthProvider directory;
        private final Daos.CollaboratorDao collaborators;
        private final Daos.TokenDao tokens;
        private final Utilities.JwtUtil jwt;
        private final Helpers.SecurityHelper security;
        private final Helpers.AuditHelper audit;

        public AuthService(AuthProviders.AuthProvider directory, Daos.CollaboratorDao collaborators,
                           Daos.TokenDao tokens, Utilities.JwtUtil jwt,
                           Helpers.SecurityHelper security, Helpers.AuditHelper audit) {
            this.directory = directory;
            this.collaborators = collaborators;
            this.tokens = tokens;
            this.jwt = jwt;
            this.security = security;
            this.audit = audit;
        }

        /**
         * The password appears in exactly one place — the call to authenticate(). It is
         * never logged, cached, or stored, and must never reach an exception message.
         */
        public Dtos.LoginResponse login(Dtos.LoginRequest req) {

            Optional<Models.DirectoryAccount> account =
                    directory.authenticate(req.username(), req.password());

            if (account.isEmpty()) {
                audit.recordAnonymous(Models.AuditAction.LOGIN_FAILED, "AUTH", req.username(),
                        "{\"reason\":\"bad_credentials\"}");
                throw new Exceptions.UnauthorizedException("Invalid credentials");
            }

            Models.Collaborator c = collaborators.findByAdObjectGuid(account.get().objectGuid())
                    .orElseThrow(() -> {
                        audit.recordAnonymous(Models.AuditAction.LOGIN_FAILED, "AUTH", req.username(),
                                "{\"reason\":\"not_enrolled\"}");
                        // Deliberately 403, not 401: someone who mistyped a password and someone
                        // never enrolled need different messages, or the support desk pays for it.
                        return new Exceptions.ForbiddenException(
                                "Your account is not registered in this application");
                    });

            if (!c.active()) {
                audit.recordAnonymous(Models.AuditAction.LOGIN_FAILED, "AUTH", req.username(),
                        "{\"reason\":\"inactive\"}");
                throw new Exceptions.UnauthorizedException("Invalid credentials");
            }

            Utilities.JwtUtil.IssuedToken issued = jwt.issue(c);
            audit.recordAnonymous(Models.AuditAction.LOGIN_SUCCEEDED, "COLLABORATOR", c.collaboratorId(), null);

            return new Dtos.LoginResponse(issued.token(), issued.expiresIn(), c.collaboratorId(),
                    c.productAdmin(), c.collaboratorAdmin());
        }

        /** Revokes this application's token only. The AD session is untouched. */
        public void logout() {
            WebSecurity.CurrentUser user = security.current();
            tokens.revoke(user.jti(), Instant.now().plusSeconds(24 * 3600));
        }
    }

    // ----------------------------------------------------------- Collaborator

    @Service
    public static class CollaboratorService {

        private final Daos.CollaboratorDao dao;
        private final AuthProviders.AuthProvider directory;
        private final Utilities.FileStorageUtil storage;
        private final Helpers.SecurityHelper security;
        private final Helpers.AuditHelper audit;

        public CollaboratorService(Daos.CollaboratorDao dao, AuthProviders.AuthProvider directory,
                                   Utilities.FileStorageUtil storage,
                                   Helpers.SecurityHelper security, Helpers.AuditHelper audit) {
            this.dao = dao;
            this.directory = directory;
            this.storage = storage;
            this.security = security;
            this.audit = audit;
        }

        public Models.Collaborator getOrThrow(Long id) {
            return dao.findById(id)
                    .orElseThrow(() -> new Exceptions.NotFoundException("Collaborator not found"));
        }

        public List<Models.Collaborator> list(boolean includeInactive) {
            security.requireCollaboratorAdmin();
            return dao.findAll(includeInactive);
        }

        public Models.Collaborator getForAdmin(Long id) {
            security.requireCollaboratorAdmin();
            return getOrThrow(id);
        }

        public List<Models.DirectoryAccount> searchDirectory(String query) {
            security.requireCollaboratorAdmin();
            return directory.search(query, 25);
        }

        /** Creating the record IS the grant of access — the person can log in from this moment. */
        @Transactional
        public Models.Collaborator create(Dtos.CreateCollaboratorRequest req) {
            security.requireCollaboratorAdmin();

            boolean productAdmin = Boolean.TRUE.equals(req.isProductAdmin());
            boolean collabAdmin  = Boolean.TRUE.equals(req.isCollaboratorAdmin());

            // Only a product admin can create another admin of either kind. Without this,
            // a collaborator admin could grant themselves full rights through a side door.
            if (productAdmin || collabAdmin) security.requireProductAdmin();

            if (dao.existsByAdObjectGuid(req.adObjectGuid())) {
                throw new Exceptions.ConflictException(
                        "That directory account is already linked to a collaborator");
            }
            if (dao.existsByEmail(req.email())) {
                throw new Exceptions.ConflictException("Email already registered");
            }

            Models.DirectoryAccount account = directory.findByObjectGuid(req.adObjectGuid())
                    .orElseThrow(() -> new Exceptions.NotFoundException("No such directory account"));

            Long id = dao.insert(new Models.Collaborator(
                    null, req.adObjectGuid(), account.username(), req.name(), req.department(),
                    req.role(), req.phoneNumber(), req.email(), productAdmin, collabAdmin,
                    true, null, null, null, 0, security.currentId(), null));

            audit.record(Models.AuditAction.COLLABORATOR_CREATED, "COLLABORATOR", id,
                    "{\"adObjectGuid\":\"" + req.adObjectGuid() + "\",\"productAdmin\":" + productAdmin
                            + ",\"collaboratorAdmin\":" + collabAdmin + "}");

            return getOrThrow(id);
        }

        @Transactional
        public Models.Collaborator update(Long id, Dtos.UpdateCollaboratorRequest req) {
            security.requireCollaboratorAdmin();
            Models.Collaborator existing = getOrThrow(id);

            boolean changingRights =
                    (req.isProductAdmin() != null && req.isProductAdmin() != existing.productAdmin())
                 || (req.isCollaboratorAdmin() != null && req.isCollaboratorAdmin() != existing.collaboratorAdmin());
            if (changingRights) security.requireProductAdmin();

            dao.update(id, req.name(), req.department(), req.role(), req.phoneNumber(),
                    req.isProductAdmin(), req.isCollaboratorAdmin());

            if (req.isProductAdmin() != null && req.isProductAdmin() != existing.productAdmin()) {
                audit.record(req.isProductAdmin() ? Models.AuditAction.ADMIN_RIGHTS_GRANTED
                                                  : Models.AuditAction.ADMIN_RIGHTS_REVOKED,
                        "COLLABORATOR", id, "{\"level\":\"product\"}");
            }
            if (req.isCollaboratorAdmin() != null && req.isCollaboratorAdmin() != existing.collaboratorAdmin()) {
                audit.record(req.isCollaboratorAdmin() ? Models.AuditAction.ADMIN_RIGHTS_GRANTED
                                                       : Models.AuditAction.ADMIN_RIGHTS_REVOKED,
                        "COLLABORATOR", id, "{\"level\":\"collaborator\"}");
            }
            audit.record(Models.AuditAction.COLLABORATOR_UPDATED, "COLLABORATOR", id, null);

            // The rendered card was built from these values, so it is now wrong.
            if (req.name() != null || req.department() != null
                    || req.role() != null || req.phoneNumber() != null) {
                invalidateCard(id);
            }
            return getOrThrow(id);
        }

        /** Phone number is the one field a collaborator owns — the rest appear on a bank-issued card. */
        @Transactional
        public Models.Collaborator updateOwn(Dtos.UpdateOwnProfileRequest req) {
            Long id = security.currentId();
            dao.updatePhone(id, req.phoneNumber());
            audit.record(Models.AuditAction.COLLABORATOR_UPDATED, "COLLABORATOR", id, "{\"self\":true}");
            invalidateCard(id);
            return getOrThrow(id);
        }

        @Transactional
        public Models.Collaborator setStatus(Long id, boolean active) {
            security.requireCollaboratorAdmin();
            Models.Collaborator target = getOrThrow(id);

            if (!active && id.equals(security.currentId())) {
                throw new Exceptions.ForbiddenException("You cannot deactivate your own account");
            }
            // Without this the last product admin can lock everyone out, and recovery is a
            // manual UPDATE against production.
            if (!active && target.productAdmin() && dao.countActiveProductAdmins() <= 1) {
                throw new Exceptions.ForbiddenException("Cannot deactivate the last active administrator");
            }

            dao.setActive(id, active);
            audit.record(active ? Models.AuditAction.COLLABORATOR_REACTIVATED
                                : Models.AuditAction.COLLABORATOR_DEACTIVATED,
                    "COLLABORATOR", id, null);
            return getOrThrow(id);
        }

        /**
         * Needed when IT rebuilds an AD account and it returns with a new objectGUID.
         * Both GUIDs are logged: this is the one call that hands a card to a different identity.
         */
        @Transactional
        public Models.Collaborator relink(Long id, String newGuid) {
            security.requireCollaboratorAdmin();
            Models.Collaborator existing = getOrThrow(id);

            Optional<Models.Collaborator> clash = dao.findByAdObjectGuid(newGuid);
            if (clash.isPresent() && !clash.get().collaboratorId().equals(id)) {
                throw new Exceptions.ConflictException("That directory account is already linked elsewhere");
            }

            Models.DirectoryAccount account = directory.findByObjectGuid(newGuid)
                    .orElseThrow(() -> new Exceptions.NotFoundException("No such directory account"));

            dao.updateAdLink(id, newGuid, account.username());
            audit.record(Models.AuditAction.AD_LINK_CHANGED, "COLLABORATOR", id,
                    "{\"from\":\"" + existing.adObjectGuid() + "\",\"to\":\"" + newGuid + "\"}");

            return getOrThrow(id);
        }

        private void invalidateCard(Long collaboratorId) {
            Models.Collaborator c = getOrThrow(collaboratorId);
            if (c.hasCard()) {
                String oldPath = c.cardPath();
                dao.clearCard(collaboratorId);
                storage.deleteQuietly(oldPath);
            }
        }
    }

    // ------------------------------------------------------------------ Card

    /**
     * The server does not render the card. The front end builds it as a div, converts it
     * to a PNG (html2canvas or dom-to-image) and uploads the blob. That keeps image
     * libraries and fonts off the backend entirely.
     */
    @Service
    public static class CardService {

        private final Daos.CollaboratorDao dao;
        private final Utilities.FileStorageUtil storage;
        private final Helpers.SecurityHelper security;
        private final Helpers.AuditHelper audit;

        public CardService(Daos.CollaboratorDao dao, Utilities.FileStorageUtil storage,
                           Helpers.SecurityHelper security, Helpers.AuditHelper audit) {
            this.dao = dao;
            this.storage = storage;
            this.security = security;
            this.audit = audit;
        }

        /**
         * Ordering is the whole trick:
         *   1. write the new file BEFORE touching the database
         *   2. update the row
         *   3. delete the old file AFTER the update succeeds
         * An orphaned file wastes bytes; a row pointing at a missing file is a 500.
         */
        public Models.Collaborator upload(MultipartFile file) {
            Long id = security.currentId();
            Models.Collaborator existing = getOrThrow(id);

            String oldPath = existing.cardPath();
            String newPath = storage.storeCard(file, id);       // step 1

            try {
                dao.updateCard(id, newPath);                    // step 2
            } catch (RuntimeException e) {
                storage.deleteQuietly(newPath);                 // roll the file back too
                throw e;
            }

            if (oldPath != null && !oldPath.equals(newPath)) {
                storage.deleteQuietly(oldPath);                 // step 3
            }

            audit.record(Models.AuditAction.CARD_UPLOADED, "COLLABORATOR", id, null);
            return getOrThrow(id);
        }

        public Models.Collaborator get(Long collaboratorId) {
            security.requireSelfOrCollaboratorAdmin(collaboratorId);
            Models.Collaborator c = getOrThrow(collaboratorId);
            if (!c.hasCard()) throw new Exceptions.NotFoundException("No card has been rendered yet");
            return c;
        }

        /** The stored path never leaves the server; clients address cards by collaborator id. */
        public Path resolveFile(Models.Collaborator c) {
            return storage.resolve(c.cardPath());
        }

        public String contentType(Models.Collaborator c) {
            return storage.contentTypeOf(c.cardPath());
        }

        public void delete(Long collaboratorId) {
            security.requireCollaboratorAdmin();
            Models.Collaborator c = getOrThrow(collaboratorId);
            if (c.hasCard()) {
                String oldPath = c.cardPath();
                dao.clearCard(collaboratorId);
                storage.deleteQuietly(oldPath);
                audit.record(Models.AuditAction.CARD_DELETED, "COLLABORATOR", collaboratorId, null);
            }
        }

        private Models.Collaborator getOrThrow(Long id) {
            return dao.findById(id)
                    .orElseThrow(() -> new Exceptions.NotFoundException("Collaborator not found"));
        }
    }

    // -------------------------------------------------------------------- QR

    @Service
    public static class QrService {

        private final Daos.CollaboratorDao dao;
        private final Utilities.FileStorageUtil storage;
        private final Utilities.QrCodeUtil qr;
        private final AppConfig.AccessProperties props;
        private final Helpers.SecurityHelper security;
        private final Helpers.AuditHelper audit;

        public QrService(Daos.CollaboratorDao dao, Utilities.FileStorageUtil storage,
                         Utilities.QrCodeUtil qr, AppConfig.AccessProperties props,
                         Helpers.SecurityHelper security, Helpers.AuditHelper audit) {
            this.dao = dao;
            this.storage = storage;
            this.qr = qr;
            this.props = props;
            this.security = security;
            this.audit = audit;
        }

        /**
         * The QR encodes a URL, never the contact data. If it held the vCard directly a
         * printed code would be frozen forever — a phone number change would mean
         * reissuing every one.
         */
        public Models.Collaborator generate() {
            Long id = security.currentId();
            Models.Collaborator existing = getOrThrow(id);

            byte[] png = qr.renderPng(props.getPublicCard().getBaseUrl() + "/c/" + id);
            String newPath = storage.storeBytes(png, Utilities.FileStorageUtil.QRCODES,
                    id + "_" + System.currentTimeMillis() + ".png");

            String oldPath = existing.qrCodePath();
            dao.updateQrCode(id, newPath);
            if (oldPath != null && !oldPath.equals(newPath)) storage.deleteQuietly(oldPath);

            audit.record(Models.AuditAction.QR_GENERATED, "COLLABORATOR", id, null);
            return getOrThrow(id);
        }

        public Models.Collaborator get(Long collaboratorId) {
            security.requireSelfOrCollaboratorAdmin(collaboratorId);
            Models.Collaborator c = getOrThrow(collaboratorId);
            if (!c.hasQrCode()) throw new Exceptions.NotFoundException("No QR code has been generated yet");
            return c;
        }

        public Path resolveFile(Models.Collaborator c) {
            return storage.resolve(c.qrCodePath());
        }

        private Models.Collaborator getOrThrow(Long id) {
            return dao.findById(id)
                    .orElseThrow(() -> new Exceptions.NotFoundException("Collaborator not found"));
        }
    }

    // --------------------------------------------------------- Public card page

    /**
     * Serves the unauthenticated /c/{id} page. Kept as its own service because the rules
     * differ from every other read: no caller, and an inactive collaborator must vanish.
     */
    @Service
    public static class PublicCardService {

        private final Daos.CollaboratorDao dao;
        private final Utilities.FileStorageUtil storage;

        public PublicCardService(Daos.CollaboratorDao dao, Utilities.FileStorageUtil storage) {
            this.dao = dao;
            this.storage = storage;
        }

        /**
         * AD disablement stops a leaver logging in, but this route never talks to AD —
         * without the IsActive check a departed employee's card stays online indefinitely.
         */
        public Models.Collaborator active(Long collaboratorId) {
            Models.Collaborator c = dao.findById(collaboratorId)
                    .orElseThrow(() -> new Exceptions.NotFoundException("This card is not available"));
            if (!c.active()) {
                throw new Exceptions.NotFoundException("This card is no longer active");
            }
            return c;
        }

        public void countScan(Long collaboratorId) {
            dao.incrementScans(collaboratorId);
        }

        public Path resolveCard(Models.Collaborator c) {
            if (!c.hasCard()) throw new Exceptions.NotFoundException("Card not available");
            return storage.resolve(c.cardPath());
        }

        public String contentType(Models.Collaborator c) {
            return storage.contentTypeOf(c.cardPath());
        }
    }

    // --------------------------------------------------------------- Product

    @Service
    public static class ProductService {

        private final Daos.ProductDao dao;
        private final Utilities.FileStorageUtil storage;
        private final Helpers.SecurityHelper security;
        private final Helpers.AuditHelper audit;
        private final Helpers.ProductStateHelper states;

        public ProductService(Daos.ProductDao dao, Utilities.FileStorageUtil storage,
                              Helpers.SecurityHelper security, Helpers.AuditHelper audit,
                              Helpers.ProductStateHelper states) {
            this.dao = dao;
            this.storage = storage;
            this.security = security;
            this.audit = audit;
            this.states = states;
        }

        public Models.Product getOrThrow(Long id) {
            return dao.findById(id)
                    .orElseThrow(() -> new Exceptions.NotFoundException("Product not found"));
        }

        public Models.Product create(Dtos.CreateProductRequest req) {
            security.requireCollaboratorAdmin();
            Long id = dao.insert(req.title(), req.description(), req.category().trim(), security.currentId());
            audit.record(Models.AuditAction.PRODUCT_CREATED, "PRODUCT", id, null);
            return getOrThrow(id);
        }

        /** Grouped server-side so category ordering lives in one place rather than in each client. */
        public Dtos.GroupedProductsResponse listGrouped(String category, String state) {
            return group(dao.find(category, state));
        }

        public Dtos.GroupedProductsResponse listApproved() {
            return group(dao.findApproved());
        }

        public List<String> categories() {
            return dao.findCategories();
        }

        public Models.Product update(Long id, Dtos.UpdateProductRequest req) {
            security.requireCollaboratorAdmin();
            Models.Product p = getOrThrow(id);

            // Only the submitter may edit their own draft; a product admin may edit any draft.
            if (!security.current().productAdmin() && !p.submittedBy().equals(security.currentId())) {
                throw new Exceptions.ForbiddenException("Not permitted for this product");
            }
            // An approved or in-flight product is changed by rejecting or retiring it, never
            // by silently editing content underneath an approval someone already gave.
            states.requireState(p, Models.State.DRAFT);

            dao.update(id, req.title(), req.description(),
                    req.category() == null ? null : req.category().trim());
            audit.record(Models.AuditAction.PRODUCT_UPDATED, "PRODUCT", id, null);
            return getOrThrow(id);
        }

        /** Soft delete — a hard delete would break the SubmittedBy/ApprovedBy foreign keys. */
        public void retire(Long id) {
            security.requireProductAdmin();
            getOrThrow(id);
            dao.retire(id);
            audit.record(Models.AuditAction.PRODUCT_RETIRED, "PRODUCT", id, null);
        }

        // ---- approval workflow ----

        public Models.Product submit(Long id) {
            Models.Product p = getOrThrow(id);
            if (!p.submittedBy().equals(security.currentId())) {
                throw new Exceptions.ForbiddenException("Only the submitter may submit this product");
            }
            states.requireState(p, Models.State.DRAFT);

            dao.setState(id, Models.State.SUBMITTED);
            audit.record(Models.AuditAction.PRODUCT_SUBMITTED, "PRODUCT", id, null);
            return getOrThrow(id);
        }

        public Models.Product approve(Long id) {
            security.requireProductAdmin();
            Models.Product p = getOrThrow(id);
            states.requireState(p, Models.State.SUBMITTED);
            states.requireDifferentApprover(p, security.currentId());

            dao.approve(id, security.currentId());
            audit.record(Models.AuditAction.PRODUCT_APPROVED, "PRODUCT", id, null);
            return getOrThrow(id);
        }

        public Models.Product reject(Long id) {
            security.requireProductAdmin();
            Models.Product p = getOrThrow(id);
            states.requireState(p, Models.State.SUBMITTED);
            states.requireDifferentApprover(p, security.currentId());

            dao.reject(id, security.currentId());
            audit.record(Models.AuditAction.PRODUCT_REJECTED, "PRODUCT", id, null);
            return getOrThrow(id);
        }

        // ---- document ----

        public Models.Product uploadDocument(Long id, MultipartFile file) {
            security.requireCollaboratorAdmin();
            Models.Product p = getOrThrow(id);

            String oldPath = p.documentPath();
            String newPath = storage.storeDocument(file, id);

            try {
                dao.updateDocument(id, newPath);
            } catch (RuntimeException e) {
                storage.deleteQuietly(newPath);
                throw e;
            }
            if (oldPath != null && !oldPath.equals(newPath)) storage.deleteQuietly(oldPath);

            audit.record(Models.AuditAction.PRODUCT_DOCUMENT_UPLOADED, "PRODUCT", id, null);
            return getOrThrow(id);
        }

        public Models.Product withDocument(Long id) {
            Models.Product p = getOrThrow(id);
            if (!p.hasDocument()) throw new Exceptions.NotFoundException("No document for this product");
            return p;
        }

        public Path resolveDocument(Models.Product p) {
            return storage.resolve(p.documentPath());
        }

        public void deleteDocument(Long id) {
            security.requireCollaboratorAdmin();
            Models.Product p = getOrThrow(id);
            if (p.hasDocument()) {
                String oldPath = p.documentPath();
                dao.clearDocument(id);
                storage.deleteQuietly(oldPath);
                audit.record(Models.AuditAction.PRODUCT_DOCUMENT_DELETED, "PRODUCT", id, null);
            }
        }

        /** Case-insensitive grouping, so "Savings" and "savings" are one section, not two. */
        private Dtos.GroupedProductsResponse group(List<Models.Product> products) {
            Map<String, List<Dtos.ProductSummary>> byCategory = new LinkedHashMap<>();
            Map<String, String> displayName = new LinkedHashMap<>();

            for (Models.Product p : products) {
                String key = p.category() == null ? "" : p.category().toLowerCase();
                displayName.putIfAbsent(key, p.category());
                byCategory.computeIfAbsent(key, k -> new ArrayList<>()).add(Dtos.ProductSummary.from(p));
            }

            List<Dtos.CategoryGroup> groups = new ArrayList<>();
            byCategory.forEach((key, list) -> groups.add(new Dtos.CategoryGroup(displayName.get(key), list)));
            return new Dtos.GroupedProductsResponse(groups);
        }
    }

    // --------------------------------------------------------------- Booklet

    /**
     * The Share Products image: one stored file, replaced on each render, shared by
     * every collaborator. The client renders it the same way it renders a card.
     */
    @Service
    public static class BookletService {

        private final Daos.BookletDao dao;
        private final Utilities.FileStorageUtil storage;
        private final Helpers.SecurityHelper security;
        private final Helpers.AuditHelper audit;

        public BookletService(Daos.BookletDao dao, Utilities.FileStorageUtil storage,
                              Helpers.SecurityHelper security, Helpers.AuditHelper audit) {
            this.dao = dao;
            this.storage = storage;
            this.security = security;
            this.audit = audit;
        }

        public Models.Booklet render(MultipartFile file) {
            security.requireCollaboratorAdmin();

            String oldPath = dao.find().map(Models.Booklet::bookletPath).orElse(null);
            String newPath = storage.storeBooklet(file);

            try {
                dao.upsert(newPath, security.currentId());
            } catch (RuntimeException e) {
                storage.deleteQuietly(newPath);
                throw e;
            }
            if (oldPath != null && !oldPath.equals(newPath)) storage.deleteQuietly(oldPath);

            audit.record(Models.AuditAction.BOOKLET_RENDERED, "BOOKLET", 1, null);
            return dao.find().orElseThrow();
        }

        public Models.Booklet get() {
            return dao.find()
                    .orElseThrow(() -> new Exceptions.NotFoundException("No booklet has been rendered yet"));
        }

        public Path resolveFile(Models.Booklet b) {
            return storage.resolve(b.bookletPath());
        }

        public String contentType(Models.Booklet b) {
            return storage.contentTypeOf(b.bookletPath());
        }
    }

    // ----------------------------------------------------------------- Audit

    @Service
    public static class AuditService {

        private final Daos.AuditDao dao;
        private final Helpers.SecurityHelper security;

        public AuditService(Daos.AuditDao dao, Helpers.SecurityHelper security) {
            this.dao = dao;
            this.security = security;
        }

        public List<Models.AuditEntry> find(String entityType, String entityId, int limit) {
            security.requireProductAdmin();
            return dao.find(entityType, entityId, Math.min(limit, 500));
        }
    }
}
