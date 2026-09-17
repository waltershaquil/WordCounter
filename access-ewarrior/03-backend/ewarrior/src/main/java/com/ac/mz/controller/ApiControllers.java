package com.ac.mz.controller;

import com.ac.mz.model.Dtos;
import com.ac.mz.model.Models;
import com.ac.mz.services.Services;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Every JSON endpoint in the application.
 *
 * Controllers are thin by design: bind the request, call a service, map the result.
 * No business rules and no authorization checks live here — those are in the services,
 * so there is a single place to read when asking "who can do this?".
 *
 * File upload and download endpoints live in FileController; the public card page
 * lives in HomeController.
 */
public final class ApiControllers {

    private ApiControllers() {}

    // ------------------------------------------------------------------ Auth

    @RestController
    @RequestMapping("/api/auth")
    public static class AuthController {

        private final Services.AuthService service;

        public AuthController(Services.AuthService service) { this.service = service; }

        @PostMapping("/login")
        public Dtos.LoginResponse login(@Valid @RequestBody Dtos.LoginRequest req) {
            return service.login(req);
        }

        @PostMapping("/logout")
        public ResponseEntity<Void> logout() {
            service.logout();
            return ResponseEntity.noContent().build();
        }
    }

    // ---------------------------------------------------------- Collaborators

    @RestController
    @RequestMapping("/api")
    public static class CollaboratorController {

        private final Services.CollaboratorService service;
        private final com.ac.mz.helpers.Helpers.SecurityHelper security;

        public CollaboratorController(Services.CollaboratorService service,
                                      com.ac.mz.helpers.Helpers.SecurityHelper security) {
            this.service = service;
            this.security = security;
        }

        @GetMapping("/directory/search")
        public List<Dtos.DirectoryAccountResponse> searchDirectory(@RequestParam("q") String query) {
            return service.searchDirectory(query).stream()
                    .map(Dtos.DirectoryAccountResponse::from).toList();
        }

        @PostMapping("/collaborators")
        public ResponseEntity<Dtos.CollaboratorResponse> create(
                @Valid @RequestBody Dtos.CreateCollaboratorRequest req) {
            var created = service.create(req);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(Dtos.CollaboratorResponse.from(created));
        }

        @GetMapping("/collaborators")
        public List<Dtos.CollaboratorResponse> list(
                @RequestParam(defaultValue = "false") boolean includeInactive) {
            return service.list(includeInactive).stream()
                    .map(Dtos.CollaboratorResponse::from).toList();
        }

        @GetMapping("/collaborators/{id}")
        public Dtos.CollaboratorResponse getOne(@PathVariable Long id) {
            return Dtos.CollaboratorResponse.from(service.getForAdmin(id));
        }

        @PutMapping("/collaborators/{id}")
        public Dtos.CollaboratorResponse update(@PathVariable Long id,
                                                @Valid @RequestBody Dtos.UpdateCollaboratorRequest req) {
            return Dtos.CollaboratorResponse.from(service.update(id, req));
        }

        @PatchMapping("/collaborators/{id}/status")
        public Dtos.StatusResponse setStatus(@PathVariable Long id,
                                             @RequestBody Dtos.StatusRequest req) {
            var c = service.setStatus(id, req.isActive());
            return new Dtos.StatusResponse(c.collaboratorId(), c.active());
        }

        @PutMapping("/collaborators/{id}/ad-link")
        public Dtos.CollaboratorResponse relink(@PathVariable Long id,
                                                @Valid @RequestBody Dtos.AdLinkRequest req) {
            return Dtos.CollaboratorResponse.from(service.relink(id, req.adObjectGuid()));
        }

        /** Populates the card. Returns the profile shape, not the admin shape. */
        @GetMapping("/collaborators/me")
        public Dtos.ProfileResponse me() {
            return Dtos.ProfileResponse.from(service.getOrThrow(security.currentId()));
        }

        @PutMapping("/collaborators/me")
        public Dtos.ProfileResponse updateMe(@RequestBody Dtos.UpdateOwnProfileRequest req) {
            return Dtos.ProfileResponse.from(service.updateOwn(req));
        }
    }

    // --------------------------------------------------------------- Products

    @RestController
    @RequestMapping("/api/products")
    public static class ProductController {

        private final Services.ProductService service;

        public ProductController(Services.ProductService service) { this.service = service; }

        @PostMapping
        public ResponseEntity<Dtos.ProductResponse> create(
                @Valid @RequestBody Dtos.CreateProductRequest req) {
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(Dtos.ProductResponse.from(service.create(req)));
        }

        @GetMapping
        public Dtos.GroupedProductsResponse list(@RequestParam(required = false) String category,
                                                 @RequestParam(required = false) String state) {
            return service.listGrouped(category, state);
        }

        /** Everything APPROVED and not retired — what the app's product screen shows. */
        @GetMapping("/active")
        public Dtos.GroupedProductsResponse active() {
            return service.listApproved();
        }

        @GetMapping("/categories")
        public List<String> categories() {
            return service.categories();
        }

        @GetMapping("/{id}")
        public Dtos.ProductResponse getOne(@PathVariable Long id) {
            return Dtos.ProductResponse.from(service.getOrThrow(id));
        }

        @PutMapping("/{id}")
        public Dtos.ProductResponse update(@PathVariable Long id,
                                           @RequestBody Dtos.UpdateProductRequest req) {
            return Dtos.ProductResponse.from(service.update(id, req));
        }

        @DeleteMapping("/{id}")
        public ResponseEntity<Void> retire(@PathVariable Long id) {
            service.retire(id);
            return ResponseEntity.noContent().build();
        }

        // ---- approval workflow ----

        @PostMapping("/{id}/submit")
        public Dtos.ProductStateResponse submit(@PathVariable Long id) {
            return state(service.submit(id));
        }

        @PostMapping("/{id}/approve")
        public Dtos.ProductStateResponse approve(@PathVariable Long id) {
            return state(service.approve(id));
        }

        @PostMapping("/{id}/reject")
        public Dtos.ProductStateResponse reject(@PathVariable Long id) {
            return state(service.reject(id));
        }

        private Dtos.ProductStateResponse state(Models.Product p) {
            return new Dtos.ProductStateResponse(p.productId(), p.state(), p.approvedBy());
        }
    }

    // ------------------------------------------------------------------ Audit

    /** Read-only. There is deliberately no endpoint that writes or deletes audit entries. */
    @RestController
    @RequestMapping("/api/audit")
    public static class AuditController {

        private final Services.AuditService service;

        public AuditController(Services.AuditService service) { this.service = service; }

        @GetMapping
        public List<Dtos.AuditResponse> list(@RequestParam(required = false) String entityType,
                                             @RequestParam(required = false) String entityId,
                                             @RequestParam(defaultValue = "100") int limit) {
            return service.find(entityType, entityId, limit).stream()
                    .map(Dtos.AuditResponse::from).toList();
        }
    }
}
