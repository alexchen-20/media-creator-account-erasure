package dev.infrai.examples.mediaerase.infrai;

import java.util.List;

public interface InfraiControlPlane {
    List<String> listSessionIds(String userId);
    void revokeSession(String sessionId);
    void revokeCredential(String credentialId);
}
