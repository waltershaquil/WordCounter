package com.ac.mz.controller;

import com.ac.mz.model.Dtos;
import com.ac.mz.model.Models;
import com.ac.mz.services.Services;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * The page a scanned QR code opens.
 *
 * UNAUTHENTICATED and reachable from outside the bank network — that is the point,
 * since the person scanning is a client. Being the only public surface in the system,
 * three things matter here:
 *
 *  - It checks IsActive. AD disablement stops a leaver logging in, but this route never
 *    talks to AD: without the check a departed employee's card stays online indefinitely.
 *  - It must be rate limited by IP, at the reverse proxy or API gateway.
 *  - It returns only what belongs on a business card. Never the AD GUID, never an admin flag.
 */
@RestController
public class HomeController {

    private final Services.PublicCardService service;

    public HomeController(Services.PublicCardService service) { this.service = service; }

    @GetMapping("/c/{collaboratorId}")
    public Dtos.PublicCardResponse view(@PathVariable Long collaboratorId) {
        Models.Collaborator c = service.active(collaboratorId);
        service.countScan(collaboratorId);

        return new Dtos.PublicCardResponse(
                c.name(), c.department(), c.role(), c.phoneNumber(), c.email(),
                "/c/" + collaboratorId + "/image");
    }

    @GetMapping("/c/{collaboratorId}/image")
    public ResponseEntity<Resource> image(@PathVariable Long collaboratorId) {
        Models.Collaborator c = service.active(collaboratorId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(service.contentType(c)))
                .body(new FileSystemResource(service.resolveCard(c)));
    }
}
