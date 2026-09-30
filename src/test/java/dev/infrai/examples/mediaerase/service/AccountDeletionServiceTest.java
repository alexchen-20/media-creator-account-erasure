package dev.infrai.examples.mediaerase.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.infrai.examples.mediaerase.domain.MediaAccountStore;
import dev.infrai.examples.mediaerase.infrai.InfraiControlPlane;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AccountDeletionServiceTest {
    @Test
    void revokesEverySessionAndCredentialBeforeErasingMediaState() {
        RecordingControlPlane infrai = new RecordingControlPlane(false);
        MediaAccountStore store = new MediaAccountStore();
        AccountDeletionService service = new AccountDeletionService(infrai, store);

        var receipt = service.delete(new AccountDeletionService.DeleteAccount(
                "creator-42", "user-42", "credential-42"));

        assertEquals(List.of("list:user-42", "session:session-a", "session:session-b", "key:credential-42"),
                infrai.calls);
        assertEquals(2, receipt.revokedSessions());
        assertEquals(2, receipt.erasedAssets());
        assertEquals(1, receipt.cancelledProcessingJobs());
        assertEquals(1, receipt.erasedCreatorDeliveries());
        assertFalse(store.exists("creator-42"));
    }

    @Test
    void retainsMediaStateWhenCredentialRevocationIsRejected() {
        RecordingControlPlane infrai = new RecordingControlPlane(true);
        MediaAccountStore store = new MediaAccountStore();
        AccountDeletionService service = new AccountDeletionService(infrai, store);

        assertThrows(IllegalStateException.class, () -> service.delete(
                new AccountDeletionService.DeleteAccount("creator-42", "user-42", "credential-42")));

        assertTrue(store.exists("creator-42"));
    }

    private static final class RecordingControlPlane implements InfraiControlPlane {
        private final List<String> calls = new ArrayList<>();
        private final boolean rejectCredential;

        private RecordingControlPlane(boolean rejectCredential) {
            this.rejectCredential = rejectCredential;
        }

        @Override public List<String> listSessionIds(String userId) {
            calls.add("list:" + userId);
            return List.of("session-a", "session-b");
        }

        @Override public void revokeSession(String sessionId) {
            calls.add("session:" + sessionId);
        }

        @Override public void revokeCredential(String credentialId) {
            calls.add("key:" + credentialId);
            if (rejectCredential) throw new IllegalStateException("rejected");
        }
    }
}
