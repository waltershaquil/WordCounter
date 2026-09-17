package com.ac.mz.utility;

import com.ac.mz.config.AppConfig;
import com.ac.mz.exception.Exceptions;
import com.ac.mz.model.Models;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import javax.crypto.SecretKey;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

/**
 * Technical utilities: token signing, file storage, QR generation, text helpers.
 * Nothing here knows a business rule — these are the tools the services use.
 */
public final class Utilities {

    private Utilities() {}

    // ------------------------------------------------------------------ JWT

    @Component
    public static class JwtUtil {

        private final AppConfig.AccessProperties props;
        private final SecretKey key;

        public JwtUtil(AppConfig.AccessProperties props) {
            this.props = props;
            this.key = Keys.hmacShaKeyFor(props.getJwt().getSecret().getBytes(StandardCharsets.UTF_8));
        }

        public record IssuedToken(String token, String jti, long expiresIn, Instant expiresAt) {}

        public IssuedToken issue(Models.Collaborator c) {
            String jti = UUID.randomUUID().toString();
            Instant now = Instant.now();
            Instant exp = now.plusSeconds(props.getJwt().getTtlSeconds());

            String token = Jwts.builder()
                    .id(jti)
                    .subject(String.valueOf(c.collaboratorId()))
                    .claim("email", c.email())
                    .claim("name", c.name())
                    .claim("isProductAdmin", c.productAdmin())
                    .claim("isCollaboratorAdmin", c.collaboratorAdmin())
                    .issuedAt(Date.from(now))
                    .expiration(Date.from(exp))
                    .signWith(key)
                    .compact();

            return new IssuedToken(token, jti, props.getJwt().getTtlSeconds(), exp);
        }

        /** Throws on bad signature, expiry or malformed input — the filter treats any failure as anonymous. */
        public Claims parse(String token) {
            return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        }
    }

    // ------------------------------------------------------------ File storage

    /**
     * Cards, QR codes, product PDFs and the booklet image are files on disk; the
     * database stores a RELATIVE path.
     *
     * Four rules, enforced here so no caller has to remember them:
     *  1. Stored paths are relative to the configured root.
     *  2. A path never comes from a client and never appears in a URL.
     *  3. The server generates every filename.
     *  4. Files are written before the DB update; old files are deleted after it.
     *
     * With more than one app instance, the root must be a shared mount — a card
     * written on instance A is a 404 on instance B otherwise.
     */
    @Component
    public static class FileStorageUtil {

        private static final Logger log = LoggerFactory.getLogger(FileStorageUtil.class);
        private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

        public static final String CARDS = "cards";
        public static final String QRCODES = "qrcodes";
        public static final String DOCUMENTS = "documents";
        public static final String BOOKLET = "booklet";

        private final AppConfig.AccessProperties props;
        private Path root;

        public FileStorageUtil(AppConfig.AccessProperties props) { this.props = props; }

        @PostConstruct
        void init() throws IOException {
            root = Paths.get(props.getStorage().getRoot()).toAbsolutePath().normalize();
            for (String dir : new String[]{CARDS, QRCODES, DOCUMENTS, BOOKLET}) {
                Files.createDirectories(root.resolve(dir));
            }
            log.info("File storage root: {}", root);
        }

        public String storeCard(MultipartFile file, long collaboratorId) {
            requireSize(file, props.getStorage().getMaxCardBytes());
            String ext = detectImageExtension(file);
            return write(file, CARDS + "/" + collaboratorId + "_" + stamp() + "." + ext);
        }

        public String storeBooklet(MultipartFile file) {
            requireSize(file, props.getStorage().getMaxCardBytes());
            String ext = detectImageExtension(file);
            return write(file, BOOKLET + "/" + stamp() + "." + ext);
        }

        public String storeDocument(MultipartFile file, long productId) {
            requireSize(file, props.getStorage().getMaxDocumentBytes());
            requirePdf(file);
            return write(file, DOCUMENTS + "/" + productId + "_" + UUID.randomUUID() + ".pdf");
        }

        public String storeBytes(byte[] bytes, String directory, String filename) {
            try {
                String relative = directory + "/" + filename;
                Path target = root.resolve(relative).normalize();
                Files.createDirectories(target.getParent());
                Files.copy(new ByteArrayInputStream(bytes), target, StandardCopyOption.REPLACE_EXISTING);
                return relative;
            } catch (IOException e) {
                throw new IllegalStateException("Could not write file", e);
            }
        }

        /** Resolve a stored path for reading, refusing anything that escapes the root. */
        public Path resolve(String relativePath) {
            Path resolved = root.resolve(relativePath).normalize();
            if (!resolved.startsWith(root)) {
                // Cannot happen with server-generated names; catches a corrupted or tampered row.
                throw new Exceptions.ForbiddenException("Invalid storage path");
            }
            if (!Files.exists(resolved)) {
                throw new Exceptions.NotFoundException("File not found");
            }
            return resolved;
        }

        /**
         * Best-effort delete, called AFTER the database update succeeds.
         * An orphaned file wastes bytes; a row pointing at a missing file is a 500.
         */
        public void deleteQuietly(String relativePath) {
            if (relativePath == null) return;
            try {
                Path p = root.resolve(relativePath).normalize();
                if (p.startsWith(root)) Files.deleteIfExists(p);
            } catch (Exception e) {
                log.warn("Could not delete stored file {}", relativePath, e);
            }
        }

        /** The schema has no content-type column, so it is derived from the stored extension. */
        public String contentTypeOf(String relativePath) {
            if (relativePath == null) return "application/octet-stream";
            String lower = relativePath.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
            if (lower.endsWith(".pdf")) return "application/pdf";
            return "image/png";
        }

        private String stamp() { return LocalDateTime.now().format(STAMP); }

        private String write(MultipartFile file, String relative) {
            try (InputStream in = file.getInputStream()) {
                Path target = root.resolve(relative).normalize();
                Files.createDirectories(target.getParent());
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                return relative;
            } catch (IOException e) {
                throw new IllegalStateException("Could not write file", e);
            }
        }

        private void requireSize(MultipartFile file, long max) {
            if (file == null || file.isEmpty()) {
                throw new Exceptions.BadRequestException("File is required");
            }
            if (file.getSize() > max) {
                throw new Exceptions.BadRequestException("File exceeds the maximum allowed size");
            }
        }

        /** Validate by magic bytes — the extension and declared Content-Type are client-controlled. */
        private String detectImageExtension(MultipartFile file) {
            byte[] h = head(file, 8);
            if (h.length >= 4 && (h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G') return "png";
            if (h.length >= 3 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF) return "jpg";
            throw new Exceptions.BadRequestException("File must be a PNG or JPEG image");
        }

        private void requirePdf(MultipartFile file) {
            byte[] h = head(file, 5);
            boolean isPdf = h.length >= 5 && h[0] == '%' && h[1] == 'P' && h[2] == 'D' && h[3] == 'F' && h[4] == '-';
            if (!isPdf) throw new Exceptions.BadRequestException("File must be a PDF");
        }

        private byte[] head(MultipartFile file, int n) {
            try (InputStream in = file.getInputStream()) {
                return in.readNBytes(n);
            } catch (IOException e) {
                throw new Exceptions.BadRequestException("Could not read the uploaded file");
            }
        }
    }

    // ------------------------------------------------------------------ QR codes

    @Component
    public static class QrCodeUtil {

        private static final int SIZE = 512;

        /** Encodes a URL, never the contact data — a printed code must survive a phone number change. */
        public byte[] renderPng(String content) {
            try {
                BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, SIZE, SIZE);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                MatrixToImageWriter.writeToStream(matrix, "PNG", out);
                return out.toByteArray();
            } catch (Exception e) {
                throw new IllegalStateException("Could not generate QR code", e);
            }
        }
    }

    // ------------------------------------------------------------------ Text

    public static final class TextUtil {

        private TextUtil() {}

        /**
         * The schema stores no original document filename, so downloads are named from
         * the product title. Anything going into a Content-Disposition header must be
         * sanitised — a quote or newline breaks the header, which is header injection.
         */
        public static String slug(String input, String fallback) {
            if (input == null || input.isBlank()) return fallback;
            String s = java.text.Normalizer.normalize(input, java.text.Normalizer.Form.NFD)
                    .replaceAll("\\p{M}", "")
                    .toLowerCase(Locale.ROOT)
                    .replaceAll("[^a-z0-9]+", "-")
                    .replaceAll("(^-|-$)", "");
            if (s.isBlank()) return fallback;
            return s.length() > 80 ? s.substring(0, 80) : s;
        }

        /** Minimal LDAP filter escaping — user input never goes into a filter raw. */
        public static String escapeLdap(String s) {
            return s.replace("\\", "\\5c")
                    .replace("*", "\\2a")
                    .replace("(", "\\28")
                    .replace(")", "\\29")
                    .replace("\0", "\\00");
        }
    }
}
