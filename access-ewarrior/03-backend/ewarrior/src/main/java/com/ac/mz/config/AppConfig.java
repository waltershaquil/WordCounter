package com.ac.mz.config;

import com.ac.mz.dao.Daos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties and startup wiring.
 * Nothing in this application hardcodes a path, secret or URL — it all comes from here.
 */
public final class AppConfig {

    private AppConfig() {}

    @Component
    @ConfigurationProperties(prefix = "access")
    public static class AccessProperties {

        private Jwt jwt = new Jwt();
        private Storage storage = new Storage();
        private Seed seed = new Seed();
        private PublicCard publicCard = new PublicCard();

        public static class Jwt {
            private String secret;
            private long ttlSeconds = 3600;
            public String getSecret() { return secret; }
            public void setSecret(String v) { this.secret = v; }
            public long getTtlSeconds() { return ttlSeconds; }
            public void setTtlSeconds(long v) { this.ttlSeconds = v; }
        }

        public static class Storage {
            private String root;
            private long maxCardBytes = 2_097_152;
            private long maxDocumentBytes = 10_485_760;
            public String getRoot() { return root; }
            public void setRoot(String v) { this.root = v; }
            public long getMaxCardBytes() { return maxCardBytes; }
            public void setMaxCardBytes(long v) { this.maxCardBytes = v; }
            public long getMaxDocumentBytes() { return maxDocumentBytes; }
            public void setMaxDocumentBytes(long v) { this.maxDocumentBytes = v; }
        }

        public static class Seed {
            private String adminAdObjectGuid;
            private String adminName;
            private String adminEmail;
            public String getAdminAdObjectGuid() { return adminAdObjectGuid; }
            public void setAdminAdObjectGuid(String v) { this.adminAdObjectGuid = v; }
            public String getAdminName() { return adminName; }
            public void setAdminName(String v) { this.adminName = v; }
            public String getAdminEmail() { return adminEmail; }
            public void setAdminEmail(String v) { this.adminEmail = v; }
        }

        public static class PublicCard {
            private String baseUrl;
            public String getBaseUrl() { return baseUrl; }
            public void setBaseUrl(String v) { this.baseUrl = v; }
        }

        public Jwt getJwt() { return jwt; }
        public void setJwt(Jwt v) { this.jwt = v; }
        public Storage getStorage() { return storage; }
        public void setStorage(Storage v) { this.storage = v; }
        public Seed getSeed() { return seed; }
        public void setSeed(Seed v) { this.seed = v; }
        public PublicCard getPublicCard() { return publicCard; }
        public void setPublicCard(PublicCard v) { this.publicCard = v; }
    }

    /**
     * Only an admin can create collaborator records, and a fresh database has none.
     * This seeds exactly one, from configuration.
     *
     * Deliberately NOT an endpoint: a "create the first admin if none exist" route is
     * exploitable the moment an admin record is deactivated, and it survives into production.
     */
    @Component
    public static class SeedAdminRunner implements ApplicationRunner {

        private static final Logger log = LoggerFactory.getLogger(SeedAdminRunner.class);

        private final AccessProperties props;
        private final Daos.CollaboratorDao dao;

        public SeedAdminRunner(AccessProperties props, Daos.CollaboratorDao dao) {
            this.props = props;
            this.dao = dao;
        }

        @Override
        public void run(ApplicationArguments args) {
            String guid = props.getSeed().getAdminAdObjectGuid();

            if (guid == null || guid.isBlank()) {
                if (dao.countActiveProductAdmins() == 0) {
                    log.warn("No administrators exist and access.seed.admin-ad-object-guid is not set. " +
                             "Nobody can log in. Set SEED_ADMIN_GUID and restart.");
                }
                return;
            }
            if (dao.existsByAdObjectGuid(guid)) return;

            dao.insertSeedAdmin(guid, props.getSeed().getAdminName(), props.getSeed().getAdminEmail());
            log.info("Seeded initial administrator for directory account {}", guid);
        }
    }
}
