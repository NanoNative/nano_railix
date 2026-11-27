package dev.nanonative.railix.mesh.peer;

import dev.nanonative.railix.mesh.MeshErrors;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertPath;
import java.security.cert.CertPathValidator;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.security.spec.AlgorithmParameterSpec;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.time.Duration;
import java.time.InstantSource;
import java.util.Arrays;
import java.util.Collection;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocket;

/** Establishes a framed TLS channel with a certificate-authenticated mesh peer. */
final class PeerTls {
    private static final String IDENTITY_URI = "https://github.com/NanoNative/nano_railix/mesh/v1/";
    private static final int URI_SAN = 6;
    private static final String TLS_SERVER_AUTH = "1.3.6.1.5.5.7.3.1";
    private static final String TLS_CLIENT_AUTH = "1.3.6.1.5.5.7.3.2";
    private static final String ANY_EXTENDED_KEY_USAGE = "2.5.29.37.0";

    private final UUID meshId;
    private final UUID replicaId;
    private final PeerTrust trust;

    private PeerTls(final UUID meshId, final UUID replicaId, final PeerTrust trust) {
        this.meshId = meshId;
        this.replicaId = replicaId;
        this.trust = trust;
    }

    /** Loads deployment credentials before the caller opens a listener. */
    static PeerTls load(final UUID meshId, final Path identity, final Path identityPassword,
                        final Path trust, final Path trustPassword) throws IOException, GeneralSecurityException {
        return load(meshId, identity, identityPassword, trust, trustPassword, InstantSource.system());
    }

    static PeerTls load(final UUID meshId, final Path identity, final Path identityPassword,
                        final Path trust, final Path trustPassword, final InstantSource time)
            throws IOException, GeneralSecurityException {
        final KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        final char[] password = readPassword(identityPassword);
        final UUID replica;
        try {
            final KeyStore keys = readStore(identity, password);
            replica = localReplicaId(keys, password, meshId);
            keyManagers.init(keys, password);
        } finally {
            Arrays.fill(password, '\0');
        }

        final char[] trustSecret = readPassword(trustPassword);
        final PeerTrust anchors;
        try {
            anchors = new PeerTrust(keyManagers.getKeyManagers(), readStore(trust, trustSecret), time);
        } finally {
            Arrays.fill(trustSecret, '\0');
        }

        anchors.context();
        return new PeerTls(meshId, replica, anchors);
    }

    UUID replicaId() {
        return replicaId;
    }

    /** Uses the caller's monotonic join deadline for TCP setup and the TLS handshake. */
    PeerTlsConnection dial(final InetSocketAddress endpoint, final long deadlineNanos) throws IOException {
        final Optional<InetSocketAddress> target = Optional.of(endpoint);
        return establish(new Socket(), target, deadlineNanos);
    }

    /** Takes ownership of an accepted TCP socket, including when authentication fails. */
    PeerTlsConnection accept(final Socket transport, final long deadlineNanos) throws IOException {
        return establish(transport, Optional.empty(), deadlineNanos);
    }

    private PeerTlsConnection establish(final Socket transport, final Optional<InetSocketAddress> target,
                                        final long deadlineNanos)
            throws IOException {
        final boolean client = target.isPresent();
        final AtomicBoolean handshaking = new AtomicBoolean(true);
        SSLSocket tls = null;
        Thread timeout = null;
        try {
            if (client) {
                connect(transport, target.orElseThrow(), deadlineNanos);
            }
            remainingNanos(deadlineNanos);
            timeout = Thread.ofVirtual().name("railix-peer-tls-deadline").start(() ->
                    closeAtDeadline(transport, handshaking, deadlineNanos));
            final SSLContext context = trustedContext();
            tls = (SSLSocket) context.getSocketFactory().createSocket(
                    transport, transport.getInetAddress().getHostAddress(), transport.getPort(), true);
            tls.setUseClientMode(client);
            if (!client) {
                tls.setNeedClientAuth(true);
            }
            tls.startHandshake();
            final UUID peer = authenticatedReplica(tls);
            if (context != trustedContext()) {
                throw new SSLHandshakeException(MeshErrors.TRUST_CHANGED_DURING_HANDSHAKE);
            }
            if (!handshaking.compareAndSet(true, false)) {
                throw deadlineExpired();
            }
            remainingNanos(deadlineNanos);
            return new PeerTlsConnection(peer, new PeerChannel(tls), transport);
        } catch (final IOException | RuntimeException failure) {
            // The TLS watchdog can turn an expired handshake into a socket I/O failure.
            final boolean timedOut = timeout != null
                    && failure instanceof IOException && !(failure instanceof SocketTimeoutException)
                    && deadlineNanos - System.nanoTime() <= 0;
            closeAfterFailure(transport, failure);
            if (tls != null) {
                closeAfterFailure(tls, failure);
            }
            if (timedOut) {
                final SocketTimeoutException rejection = deadlineExpired();
                rejection.initCause(failure);
                throw rejection;
            }
            throw failure;
        } finally {
            handshaking.set(false);
            if (timeout != null) {
                timeout.interrupt();
            }
        }
    }

    private static void connect(final Socket transport, final InetSocketAddress endpoint, final long deadlineNanos)
            throws IOException {
        final long remaining = remainingNanos(deadlineNanos);
        final long timeoutMillis = TimeUnit.NANOSECONDS.toMillis(remaining - 1) + 1;
        transport.connect(endpoint, (int) Math.min(timeoutMillis, Integer.MAX_VALUE));
    }

    private SSLContext trustedContext() throws SSLHandshakeException {
        try {
            return trust.context();
        } catch (final IOException | GeneralSecurityException invalid) {
            throw new SSLHandshakeException(invalid.getMessage(), invalid);
        }
    }

    private UUID authenticatedReplica(final SSLSocket tls) throws IOException {
        try {
            final Certificate certificate = tls.getSession().getPeerCertificates()[0];
            if (!(certificate instanceof X509Certificate peer)) {
                throw new CertificateException(MeshErrors.PEER_CERTIFICATE_REQUIRED);
            }
            return replicaId(peer, meshId);
        } catch (final CertificateException invalid) {
            throw new SSLHandshakeException(invalid.getMessage(), invalid);
        }
    }

    private static UUID replicaId(final X509Certificate certificate, final UUID meshId) throws CertificateException {
        certificate.checkValidity();
        final List<String> identities = Optional.ofNullable(certificate.getSubjectAlternativeNames()).stream()
                .flatMap(Collection::stream)
                .filter(name -> Integer.valueOf(URI_SAN).equals(name.getFirst()))
                .map(name -> name.get(1))
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(uri -> uri.startsWith(IDENTITY_URI))
                .limit(2)
                .toList();
        if (identities.isEmpty()) {
            throw new CertificateException(MeshErrors.MISSING_REPLICA_ID);
        }

        final String prefix = IDENTITY_URI + meshId + "/replicas/";
        final String uri = identities.getFirst();
        if (!uri.startsWith(prefix)) {
            throw new CertificateException(MeshErrors.WRONG_MESH_CERTIFICATE);
        }

        final String value = uri.substring(prefix.length());
        final UUID replica;
        try {
            replica = UUID.fromString(value);
        } catch (final IllegalArgumentException invalid) {
            throw new CertificateException(MeshErrors.INVALID_REPLICA_ID, invalid);
        }
        if (!replica.toString().equals(value)) {
            throw new CertificateException(MeshErrors.INVALID_REPLICA_ID);
        }
        if (identities.size() > 1) {
            throw new CertificateException(MeshErrors.MULTIPLE_REPLICA_IDS);
        }
        return replica;
    }

    private static UUID localReplicaId(final KeyStore keys, final char[] password, final UUID meshId)
            throws GeneralSecurityException {
        UUID identity = null;
        final Enumeration<String> aliases = keys.aliases();
        while (aliases.hasMoreElements()) {
            final String alias = aliases.nextElement();
            if (!keys.isKeyEntry(alias)) {
                continue;
            }
            final Certificate[] chain = keys.getCertificateChain(alias);
            if (identity != null || !(keys.getKey(alias, password) instanceof PrivateKey privateKey)
                    || chain == null || chain.length == 0 || !(chain[0] instanceof X509Certificate certificate)) {
                throw new CertificateException(MeshErrors.INVALID_IDENTITY_STORE);
            }
            identity = replicaId(certificate, meshId);
            requireTlsRoles(certificate);
            validateIdentityChain(chain);
            requireMatchingKey(privateKey, certificate.getPublicKey());
        }
        if (identity == null) {
            throw new CertificateException(MeshErrors.MISSING_PRIVATE_KEY_IDENTITY);
        }
        return identity;
    }

    private static void validateIdentityChain(final Certificate[] chain) throws GeneralSecurityException {
        if (chain.length == 1) {
            return;
        }
        final int end = chain.length - 1;
        if (!(chain[end] instanceof X509Certificate issuer) || issuer.getBasicConstraints() < 0) {
            throw new CertificateException(MeshErrors.IDENTITY_ISSUER_REQUIRED);
        }
        issuer.checkValidity();

        // Preflight the supplied chain locally. Peers authenticate it using their own trust stores.
        final CertPath path = CertificateFactory.getInstance("X.509").generateCertPath(Arrays.asList(chain).subList(0, end));
        final PKIXParameters parameters = new PKIXParameters(Set.of(new TrustAnchor(issuer, null)));
        parameters.setRevocationEnabled(false);
        CertPathValidator.getInstance("PKIX").validate(path, parameters);
    }

    private static void requireTlsRoles(final X509Certificate certificate) throws CertificateException {
        final boolean[] keyUsage = certificate.getKeyUsage();
        if (keyUsage != null && !keyUsage[0]) {
            throw new CertificateException(MeshErrors.IDENTITY_DIGITAL_SIGNATURE_REQUIRED);
        }
        final List<String> extended = certificate.getExtendedKeyUsage();
        if (extended == null || extended.contains(ANY_EXTENDED_KEY_USAGE)) {
            return;
        }
        if (!extended.contains(TLS_CLIENT_AUTH) || !extended.contains(TLS_SERVER_AUTH)) {
            throw new CertificateException(MeshErrors.IDENTITY_TLS_ROLES_REQUIRED);
        }
    }

    private static void requireMatchingKey(final PrivateKey privateKey, final PublicKey publicKey)
            throws CertificateException {
        final boolean matches;
        try {
            final Signature proof = pairingSignature(publicKey);
            final byte[] challenge = new byte[32];
            new SecureRandom().nextBytes(challenge);
            proof.initSign(privateKey);
            proof.update(challenge);
            final byte[] signed = proof.sign();
            proof.initVerify(publicKey);
            proof.update(challenge);
            matches = proof.verify(signed);
        } catch (final GeneralSecurityException invalid) {
            throw new CertificateException(MeshErrors.PRIVATE_KEY_VERIFICATION_FAILED, invalid);
        }
        if (!matches) {
            throw new CertificateException(MeshErrors.PRIVATE_KEY_MISMATCH);
        }
    }

    private static Signature pairingSignature(final PublicKey key) throws GeneralSecurityException {
        final String algorithm = switch (key.getAlgorithm()) {
            case "RSA" -> "SHA256withRSA";
            case "EC" -> "SHA256withECDSA";
            case "DSA" -> "SHA256withDSA";
            default -> key.getAlgorithm();
        };
        final Signature proof = Signature.getInstance(algorithm);
        if (algorithm.equals("RSASSA-PSS")) {
            final AlgorithmParameterSpec parameters = key.getParams();
            proof.setParameter(parameters == null
                    ? new PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1) : parameters);
        }
        return proof;
    }

    private static KeyStore readStore(final Path path, final char[] password)
            throws IOException, GeneralSecurityException {
        final KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(regularFile(path))) {
            store.load(input, password);
        }
        return store;
    }

    private static char[] readPassword(final Path path) throws IOException {
        try (BufferedReader input = Files.newBufferedReader(regularFile(path))) {
            final String value = input.readLine();
            if (value == null || input.readLine() != null) {
                throw new IOException(MeshErrors.INVALID_PASSWORD_FILE);
            }
            return value.toCharArray();
        }
    }

    private static Path regularFile(final Path path) throws IOException {
        if (!Files.readAttributes(path, BasicFileAttributes.class).isRegularFile()) {
            throw new IOException(MeshErrors.REGULAR_FILE_REQUIRED + path);
        }
        return path;
    }

    private static long remainingNanos(final long deadlineNanos) throws SocketTimeoutException {
        final long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) {
            throw deadlineExpired();
        }
        return remaining;
    }

    private static SocketTimeoutException deadlineExpired() {
        return new SocketTimeoutException(MeshErrors.TLS_JOIN_DEADLINE_EXPIRED);
    }

    private static void closeAtDeadline(final Socket transport, final AtomicBoolean handshaking,
                                        final long deadlineNanos) {
        try {
            Thread.sleep(Duration.ofNanos(Math.max(0, deadlineNanos - System.nanoTime())));
            if (handshaking.compareAndSet(true, false)) {
                transport.close();
            }
        } catch (final InterruptedException completed) {
            Thread.currentThread().interrupt();
        } catch (final IOException closing) {
            // The handshake still rejects the connection when its deadline expires.
        }
    }

    private static void closeAfterFailure(final Closeable resource, final Throwable failure) {
        try {
            resource.close();
        } catch (final IOException closing) {
            if (closing != failure) {
                failure.addSuppressed(closing);
            }
        }
    }
}
