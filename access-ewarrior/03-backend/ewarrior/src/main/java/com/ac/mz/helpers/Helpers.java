package com.ac.mz.helpers;

import com.ac.mz.dao.Daos;
import com.ac.mz.exception.Exceptions;
import com.ac.mz.model.Models;
import com.ac.mz.websecurity.WebSecurity;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Supporting classes for the services: permission checks and the audit trail.
 * These exist so the same rule is written once and called from many services.
 */
public final class Helpers {

    private Helpers() {}

    /**
     * Every authorization decision in the application goes through here, so there is
     * one place to read when someone asks "who can do this?".
     */
    @Component
    public static class SecurityHelper {

        public WebSecurity.CurrentUser current() {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !(auth.getPrincipal() instanceof WebSecurity.CurrentUser u)) {
                throw new Exceptions.UnauthorizedException("Not authenticated");
            }
            return u;
        }

        public Long currentId() { return current().collaboratorId(); }

        /** Full platform admin — required for product approval and for granting admin rights. */
        public void requireProductAdmin() {
            if (!current().productAdmin()) {
                throw new Exceptions.ForbiddenException("Product administrator rights required");
            }
        }

        /** Either admin flag — required for collaborator account management. */
        public void requireCollaboratorAdmin() {
            if (!current().canManageCollaborators()) {
                throw new Exceptions.ForbiddenException("Administrator rights required");
            }
        }

        /** A collaborator may act on their own record; an admin may act on anyone's. */
        public void requireSelfOrCollaboratorAdmin(Long targetId) {
            WebSecurity.CurrentUser u = current();
            if (!u.canManageCollaborators() && !u.collaboratorId().equals(targetId)) {
                throw new Exceptions.ForbiddenException("Not permitted for this collaborator");
            }
        }
    }

    /**
     * Append-only audit trail.
     *
     * Call it from the service method, AFTER the action succeeded. Not from a controller,
     * which does not know what actually changed; not before, or you record things that
     * then roll back.
     *
     * REQUIRES_NEW means an audit write survives a rolled-back business transaction —
     * deliberate, since a failed attempt is often the more interesting record.
     */
    @Component
    public static class AuditHelper {

        private static final Logger log = LoggerFactory.getLogger(AuditHelper.class);

        private final Daos.AuditDao dao;

        public AuditHelper(Daos.AuditDao dao) { this.dao = dao; }

        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public void record(Models.AuditAction action, String entityType, Object entityId, String detailJson) {
            WebSecurity.CurrentUser actor = currentOrNull();
            write(actor == null ? null : actor.collaboratorId(),
                  actor == null ? null : actor.name(),
                  action, entityType, entityId, detailJson);
        }

        /** For unauthenticated events such as a failed login, where there is no actor yet. */
        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public void recordAnonymous(Models.AuditAction action, String entityType, Object entityId, String detailJson) {
            write(null, null, action, entityType, entityId, detailJson);
        }

        private void write(Long actorId, String actorName, Models.AuditAction action,
                           String entityType, Object entityId, String detailJson) {
            try {
                dao.insert(actorId, actorName, action.name(), entityType,
                        entityId == null ? null : String.valueOf(entityId), detailJson, clientIp());
            } catch (Exception e) {
                // An audit failure must never break the user's request, but it must be visible.
                log.error("AUDIT WRITE FAILED action={} entity={}:{}", action, entityType, entityId, e);
            }
        }

        private WebSecurity.CurrentUser currentOrNull() {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            return (auth != null && auth.getPrincipal() instanceof WebSecurity.CurrentUser u) ? u : null;
        }

        private String clientIp() {
            try {
                var attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
                if (attrs == null) return null;
                HttpServletRequest req = attrs.getRequest();
                String forwarded = req.getHeader("X-Forwarded-For");
                // Behind our own reverse proxy the first entry is the client. Never use it
                // for an authorization decision — only for the record.
                return forwarded != null ? forwarded.split(",")[0].trim() : req.getRemoteAddr();
            } catch (Exception e) {
                return null;
            }
        }
    }

    /**
     * The product approval state machine, in one place.
     * Keeping the legal transitions here stops each service method inventing its own.
     */
    @Component
    public static class ProductStateHelper {

        public void requireState(Models.Product product, String expected) {
            if (!expected.equals(product.state())) {
                throw new Exceptions.ConflictException(
                        "Product is in state " + product.state() + "; expected " + expected);
            }
        }

        /**
         * The submitter must not be the approver. Without this the approval step is
         * decoration — the submitter could wave through their own product.
         */
        public void requireDifferentApprover(Models.Product product, Long approverId) {
            if (product.submittedBy().equals(approverId)) {
                throw new Exceptions.ForbiddenException("You cannot approve your own submission");
            }
        }
    }
}
