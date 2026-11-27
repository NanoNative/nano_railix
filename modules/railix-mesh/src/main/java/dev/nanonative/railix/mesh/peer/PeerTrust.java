package dev.nanonative.railix.mesh.peer;

import dev.nanonative.railix.mesh.MeshErrors;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;

/** Supplies JSSE with the currently valid trust anchors for new peer connections. */
final class PeerTrust {
    private final KeyManager[] identities;
    private final List<X509Certificate> configured;
    private final InstantSource time;
    private List<X509Certificate> active = List.of();
    private SSLContext context;

    PeerTrust(final KeyManager[] identities, final KeyStore issuers, final InstantSource time)
            throws GeneralSecurityException {
        this.identities = identities;
        this.time = time;
        final ArrayList<X509Certificate> certificates = new ArrayList<>();
        final Enumeration<String> aliases = issuers.aliases();
        while (aliases.hasMoreElements()) {
            final String alias = aliases.nextElement();
            if (!issuers.isCertificateEntry(alias) || !(issuers.getCertificate(alias) instanceof X509Certificate certificate)
                    || certificate.getBasicConstraints() < 0) {
                throw new CertificateException(MeshErrors.INVALID_TRUST_STORE);
            }
            certificates.add(certificate);
        }
        configured = List.copyOf(certificates);
    }

    synchronized SSLContext context() throws IOException, GeneralSecurityException {
        // JSSE validates the chain up to the anchor, whose validity dates are our policy.
        final Date now = Date.from(time.instant());
        final List<X509Certificate> valid = configured.stream()
                .filter(certificate -> !now.before(certificate.getNotBefore()) && !now.after(certificate.getNotAfter()))
                .toList();
        if (!valid.equals(active)) {
            // Replacing the context discards sessions authenticated with the old set of anchors.
            context = valid.isEmpty() ? null : createContext(valid);
            active = valid;
        }
        if (context == null) {
            throw new CertificateException(MeshErrors.NO_CURRENT_TRUST_ANCHOR);
        }
        return context;
    }

    private SSLContext createContext(final List<X509Certificate> valid) throws IOException, GeneralSecurityException {
        final KeyStore trusted = KeyStore.getInstance("PKCS12");
        trusted.load(null, null);
        for (int index = 0; index < valid.size(); index++) {
            trusted.setCertificateEntry("issuer-" + index, valid.get(index));
        }
        final TrustManagerFactory managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        managers.init(trusted);
        final SSLContext updated = SSLContext.getInstance("TLS");
        updated.init(identities, managers.getTrustManagers(), null);
        return updated;
    }
}
