package com.ac.mz.auth;

import com.ac.mz.dao.Daos;
import com.ac.mz.model.Models;
import com.ac.mz.utility.Utilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.query.LdapQueryBuilder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import javax.naming.NamingException;
import javax.naming.directory.Attributes;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The seam that lets the whole application be built before Active Directory details exist.
 *
 * Two implementations, selected by access.auth.provider:
 *   local — development, credentials in the LocalCredential table
 *   ad    — production, LDAPS bind against a domain controller
 *
 * Nothing downstream (JWT, filter, services, controllers) knows which one is active.
 */
public final class AuthProviders {

    private AuthProviders() {}

    public interface AuthProvider {

        /**
         * Verify credentials, returning the directory account on success and empty on
         * bad credentials. Never throws for a wrong password, and never returns or
         * retains the password.
         */
        Optional<Models.DirectoryAccount> authenticate(String username, String password);

        /** Search the directory for accounts an admin could link. */
        List<Models.DirectoryAccount> search(String query, int limit);

        /** Confirm a GUID still resolves — used when linking a collaborator record. */
        Optional<Models.DirectoryAccount> findByObjectGuid(String objectGuid);
    }

    /**
     * DEVELOPMENT ONLY. Enabled by access.auth.provider=local, set in the dev profile.
     * Reads the LocalCredential table, which must never exist in production.
     */
    @Component
    @ConditionalOnProperty(name = "access.auth.provider", havingValue = "local")
    public static class LocalAuthProvider implements AuthProvider {

        private static final String DUMMY_HASH =
                "$2a$12$0000000000000000000000000000000000000000000000000000";

        private final Daos.LocalCredentialDao dao;
        private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);

        public LocalAuthProvider(Daos.LocalCredentialDao dao) { this.dao = dao; }

        @Override
        public Optional<Models.DirectoryAccount> authenticate(String username, String password) {
            Optional<String> hash = dao.findPasswordHash(username);

            if (hash.isEmpty()) {
                // Same work as a real check, so response timing does not reveal whether
                // the username exists.
                encoder.matches(password, DUMMY_HASH);
                return Optional.empty();
            }
            if (!encoder.matches(password, hash.get())) {
                return Optional.empty();
            }
            return dao.findByUsername(username);
        }

        @Override
        public List<Models.DirectoryAccount> search(String query, int limit) {
            if (query == null || query.trim().length() < 3) return List.of();
            return dao.search(query.trim(), limit);
        }

        @Override
        public Optional<Models.DirectoryAccount> findByObjectGuid(String objectGuid) {
            return dao.findByObjectGuid(objectGuid);
        }
    }

    /**
     * PRODUCTION. Fill in the TODO(infra) items once IT confirms the directory details.
     *
     * Design points that hold whatever they answer:
     *  - Bind over LDAPS. A plain LDAP bind sends the password in cleartext.
     *  - Match on objectGUID, never sAMAccountName or userPrincipalName: both change
     *    when someone's name changes, silently orphaning the collaborator record.
     *  - The password is a parameter to the bind and nothing else. Never log it,
     *    never cache it, never put it in an exception message.
     */
    @Component
    @ConditionalOnProperty(name = "access.auth.provider", havingValue = "ad")
    public static class ActiveDirectoryAuthProvider implements AuthProvider {

        private static final Logger log = LoggerFactory.getLogger(ActiveDirectoryAuthProvider.class);

        private final LdapTemplate ldap;

        @Value("${access.auth.ad.domain}")
        private String domain;

        @Value("${access.auth.ad.search-base}")
        private String searchBase;

        public ActiveDirectoryAuthProvider(LdapTemplate ldap) { this.ldap = ldap; }

        @Override
        public Optional<Models.DirectoryAccount> authenticate(String username, String password) {
            // TODO(infra): confirm the expected username form — sAMAccountName, UPN, or DOMAIN\\user.
            String upn = username.contains("@") ? username : username + "@" + domain;
            try {
                boolean ok = ldap.authenticate(searchBase,
                        "(userPrincipalName=" + Utilities.TextUtil.escapeLdap(upn) + ")", password);
                return ok ? findByUpn(upn) : Optional.empty();
            } catch (Exception e) {
                // Log the failure class only — e.getMessage() can contain the bind DN.
                log.warn("LDAP bind failed for a login attempt: {}", e.getClass().getSimpleName());
                return Optional.empty();
            }
        }

        @Override
        public List<Models.DirectoryAccount> search(String query, int limit) {
            // An unbounded directory query is slow and noisy in the DC logs.
            if (query == null || query.trim().length() < 3) return List.of();
            String q = Utilities.TextUtil.escapeLdap(query.trim());

            return ldap.search(
                    LdapQueryBuilder.query().base(searchBase).countLimit(limit)
                            .filter("(&(objectClass=user)(|(displayName=*" + q + "*)(sAMAccountName=*" + q + "*)))"),
                    (Attributes attrs) -> toAccount(attrs));
        }

        @Override
        public Optional<Models.DirectoryAccount> findByObjectGuid(String objectGuid) {
            // TODO(infra): confirm objectGUID is searchable as a string here; some
            // directories require the byte-array form in the filter.
            return ldap.search(
                    LdapQueryBuilder.query().base(searchBase).filter("(objectGUID=" + objectGuid + ")"),
                    (Attributes attrs) -> toAccount(attrs)).stream().findFirst();
        }

        private Optional<Models.DirectoryAccount> findByUpn(String upn) {
            return ldap.search(
                    LdapQueryBuilder.query().base(searchBase)
                            .filter("(userPrincipalName=" + Utilities.TextUtil.escapeLdap(upn) + ")"),
                    (Attributes attrs) -> toAccount(attrs)).stream().findFirst();
        }

        private Models.DirectoryAccount toAccount(Attributes attrs) {
            try {
                return new Models.DirectoryAccount(
                        guidToString((byte[]) attrs.get("objectGUID").get()),
                        str(attrs, "sAMAccountName"),
                        str(attrs, "displayName"),
                        str(attrs, "mail"),
                        str(attrs, "department"),
                        str(attrs, "title"));
            } catch (Exception e) {
                throw new IllegalStateException("Could not read directory entry", e);
            }
        }

        private String str(Attributes attrs, String name) throws NamingException {
            return attrs.get(name) == null ? null : String.valueOf(attrs.get(name).get());
        }

        /** AD returns objectGUID as 16 bytes in mixed-endian order. */
        private String guidToString(byte[] g) {
            ByteBuffer bb = ByteBuffer.wrap(new byte[]{
                    g[3], g[2], g[1], g[0], g[5], g[4], g[7], g[6],
                    g[8], g[9], g[10], g[11], g[12], g[13], g[14], g[15]});
            return new UUID(bb.getLong(), bb.getLong()).toString();
        }
    }
}
