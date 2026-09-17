package com.ac.mz.controller;

import com.ac.mz.model.Dtos;
import com.ac.mz.model.Models;
import com.ac.mz.services.Services;
import com.ac.mz.utility.Utilities;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;

/**
 * Every endpoint that moves a file: card images, QR codes, product PDFs and the
 * booklet. Split out from ApiControllers because these return binary rather than
 * JSON and share the same upload/download shape.
 *
 * A stored path is never accepted from a client and never appears in a URL — callers
 * address files by collaborator or product id, and the service resolves the path.
 */
@RestController
public class FileController {

    private final Services.CardService cards;
    private final Services.QrService qrCodes;
    private final Services.ProductService products;
    private final Services.BookletService booklet;

    public FileController(Services.CardService cards, Services.QrService qrCodes,
                          Services.ProductService products, Services.BookletService booklet) {
        this.cards = cards;
        this.qrCodes = qrCodes;
        this.products = products;
        this.booklet = booklet;
    }

    // -------------------------------------------------------------- the card

    /** The front end converts its card div to a PNG blob and posts it here. */
    @PostMapping(value = "/api/collaborators/me/card", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Dtos.CardResponse uploadCard(@RequestPart("file") MultipartFile file) {
        Models.Collaborator c = cards.upload(file);
        return new Dtos.CardResponse(c.collaboratorId(), c.cardIssueDate(),
                "/api/collaborators/card?collaboratorId=" + c.collaboratorId());
    }

    /** Returns the image itself, not JSON. */
    @GetMapping("/api/collaborators/card")
    public ResponseEntity<Resource> downloadCard(@RequestParam Long collaboratorId) {
        Models.Collaborator c = cards.get(collaboratorId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(cards.contentType(c)))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"card-" + collaboratorId + "\"")
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePrivate())
                .body(new FileSystemResource(cards.resolveFile(c)));
    }

    /** Metadata without the file, for clients that only need to know whether a card exists. */
    @GetMapping("/api/collaborators/card/info")
    public Dtos.CardInfoResponse cardInfo(@RequestParam Long collaboratorId) {
        Models.Collaborator c = cards.get(collaboratorId);
        return new Dtos.CardInfoResponse(c.collaboratorId(), c.cardIssueDate(), true);
    }

    /** Admin: force a re-render, e.g. after a template or branding change. */
    @DeleteMapping("/api/collaborators/{collaboratorId}/card")
    public ResponseEntity<Void> deleteCard(@PathVariable Long collaboratorId) {
        cards.delete(collaboratorId);
        return ResponseEntity.noContent().build();
    }

    // ----------------------------------------------------------- the QR code

    @PostMapping("/api/collaborators/qr")
    public ResponseEntity<Dtos.QrResponse> generateQr() {
        Models.Collaborator c = qrCodes.generate();
        return ResponseEntity.status(HttpStatus.CREATED).body(
                new Dtos.QrResponse(c.collaboratorId(), c.scans(),
                        "/api/collaborators/qr?collaboratorId=" + c.collaboratorId()));
    }

    @GetMapping("/api/collaborators/qr")
    public ResponseEntity<Resource> downloadQr(@RequestParam Long collaboratorId) {
        Models.Collaborator c = qrCodes.get(collaboratorId);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .body(new FileSystemResource(qrCodes.resolveFile(c)));
    }

    // ------------------------------------------------------ product documents

    @PostMapping(value = "/api/products/{id}/document", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Dtos.DocumentResponse uploadDocument(@PathVariable Long id,
                                                @RequestPart("file") MultipartFile file) {
        Models.Product p = products.uploadDocument(id, file);
        return new Dtos.DocumentResponse(p.productId(), p.hasDocument());
    }

    /**
     * The schema stores no original filename, so the download name comes from the product
     * title. It is slugified first: a title containing a quote or newline would otherwise
     * break the Content-Disposition header, which is header injection.
     */
    @GetMapping("/api/products/{id}/document")
    public ResponseEntity<Resource> downloadDocument(@PathVariable Long id) {
        Models.Product p = products.withDocument(id);
        String filename = Utilities.TextUtil.slug(p.title(), "document") + ".pdf";

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(new FileSystemResource(products.resolveDocument(p)));
    }

    @DeleteMapping("/api/products/{id}/document")
    public ResponseEntity<Void> deleteDocument(@PathVariable Long id) {
        products.deleteDocument(id);
        return ResponseEntity.noContent().build();
    }

    // ----------------------------------------------------------- the booklet

    /** Stores the rendered Share Products image. One row, replaced on each render. */
    @PostMapping(value = "/api/booklet", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Dtos.BookletResponse renderBooklet(@RequestPart("file") MultipartFile file) {
        Models.Booklet b = booklet.render(file);
        return new Dtos.BookletResponse(b.renderedBy(), b.renderedDate());
    }

    /** What the Share Products button calls. */
    @GetMapping("/api/booklet")
    public ResponseEntity<Resource> downloadBooklet() {
        Models.Booklet b = booklet.get();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(booklet.contentType(b)))
                .body(new FileSystemResource(booklet.resolveFile(b)));
    }
}
