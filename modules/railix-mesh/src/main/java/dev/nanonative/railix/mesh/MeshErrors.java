package dev.nanonative.railix.mesh;

/** Diagnostics shared by mesh startup, peer connections, and mesh status. */
public final class MeshErrors {

    private MeshErrors() {}

    // Deployment configuration.
    public static final String STARTUP_FAILED_PREFIX = "MESH_STARTUP_FAILED: ";
    public static final String CONFIG_PATH_REQUIRED = "RAILIX_MESH_CONFIG requires a path.";
    public static final String REGULAR_FILE_REQUIRED = "Mesh startup requires a regular file: ";
    public static final String CONFIG_TOO_LARGE = "Mesh configuration exceeds 64 KiB.";
    public static final String INVALID_CONFIG =
            "Mesh configuration must be a JSON object with the required mesh fields.";
    public static final String INVALID_MESH_ID = "meshId must be a lowercase UUID.";
    public static final String INVALID_LISTEN_ENDPOINT = "listenEndpoint requires a host and a port from 1 to 65535.";
    public static final String INVALID_ADVERTISED_ENDPOINT =
            "advertisedEndpoint requires a host and a port from 1 to 65535.";
    public static final String INVALID_BOOTSTRAP_ENDPOINTS = "bootstrapEndpoints must be a list of endpoints.";
    public static final String INVALID_BOOTSTRAP_ENDPOINT =
            "Each bootstrap endpoint requires a host and a port from 1 to 65535.";
    public static final String INVALID_FLOW_CAPACITY = "flowCapacity must be a positive integer.";
    public static final String INVALID_STORE_PATHS = "%s requires a PKCS12 path and a passwordFile path.";
    public static final String INVALID_ENDPOINT = "A mesh endpoint requires a host and a port from 1 to 65535.";

    // Hostname resolution.
    public static final String LISTEN_LOOKUP_INTERRUPTED = "Listen endpoint lookup was interrupted.";
    public static final String HOST_LOOKUP_TIMED_OUT = "Mesh hostname lookup exceeded its deadline.";
    public static final String HOST_LOOKUP_FAILED = "Mesh hostname lookup failed.";

    // TLS and credentials.
    public static final String TRUST_CHANGED_DURING_HANDSHAKE = "Mesh trusted issuers changed during handshake.";
    public static final String PEER_CERTIFICATE_REQUIRED = "Mesh requires an X.509 peer certificate.";
    public static final String MISSING_REPLICA_ID = "Mesh certificate is missing its replica identity.";
    public static final String WRONG_MESH_CERTIFICATE = "Mesh certificate belongs to a different mesh.";
    public static final String INVALID_REPLICA_ID = "Mesh certificate has an invalid replica ID.";
    public static final String MULTIPLE_REPLICA_IDS = "Mesh certificate has multiple replica identities.";
    public static final String INVALID_IDENTITY_STORE =
            "Mesh identity store must contain one X.509 private-key identity.";
    public static final String MISSING_PRIVATE_KEY_IDENTITY =
            "Mesh identity store is missing its private-key identity.";
    public static final String IDENTITY_ISSUER_REQUIRED =
            "Mesh identity certificate chain must end with an issuer CA certificate.";
    public static final String IDENTITY_DIGITAL_SIGNATURE_REQUIRED =
            "Mesh identity certificate must permit digital signatures for TLS.";
    public static final String IDENTITY_TLS_ROLES_REQUIRED =
            "Mesh identity certificate must permit TLS client and server authentication.";
    public static final String PRIVATE_KEY_VERIFICATION_FAILED =
            "Mesh private key cannot be verified against its certificate.";
    public static final String PRIVATE_KEY_MISMATCH = "Mesh private key does not match its certificate.";
    public static final String INVALID_PASSWORD_FILE = "Mesh password file must contain one UTF-8 line.";
    public static final String TLS_JOIN_DEADLINE_EXPIRED = "Mesh TLS join deadline expired.";
    public static final String INVALID_TRUST_STORE = "Mesh trust store must contain only trusted CA certificates.";
    public static final String NO_CURRENT_TRUST_ANCHOR = "Mesh trust store has no currently valid CA certificate.";

    // Channels and framing.
    public static final String SOCKET_ALREADY_CLAIMED = "Socket already has a peer channel.";
    public static final String SOCKET_CLOSED = "Peer socket is closed.";
    public static final String INVALID_RESERVED_BUFFER =
            "Reserved peer frame buffer must match the validated body length.";
    public static final String INVALID_FRAME_LIMIT = "Peer frame body limit must be between 1 and %s bytes.";
    public static final String CONTROL_FRAME_TOO_LARGE = "Mesh control message exceeds the frame limit.";

    // Rejection codes reported through mesh status.
    public static final String LISTENER_UNAVAILABLE = "listener-unavailable";
    public static final String JOIN_TIMEOUT = "join-timeout";
    public static final String PEER_UNAVAILABLE = "peer-unavailable";
    public static final String TLS_AUTHENTICATION = "tls-authentication";
    public static final String INVALID_HELLO = "invalid-hello";
    public static final String INVALID_STATE = "invalid-state";
    public static final String IDENTITY_MISMATCH = "identity-mismatch";
    public static final String BUILD_MISMATCH = "build-mismatch";
    public static final String PEER_LIMIT_REACHED = "peer-limit-reached";
    public static final String REPLICA_ALREADY_CONNECTED = "replica-already-connected";
}
