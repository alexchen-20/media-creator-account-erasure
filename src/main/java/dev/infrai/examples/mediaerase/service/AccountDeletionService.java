package dev.infrai.examples.mediaerase.service;

import dev.infrai.examples.mediaerase.domain.MediaAccountStore;
import dev.infrai.examples.mediaerase.domain.MediaAccountStore.MediaAccount;
import dev.infrai.examples.mediaerase.infrai.InfraiControlPlane;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public final class AccountDeletionService {
    private final InfraiControlPlane infrai;
    private final MediaAccountStore store;

    public AccountDeletionService(InfraiControlPlane infrai, MediaAccountStore store) {
        this.infrai = infrai;
        this.store = store;
    }

    public DeletionReceipt delete(DeleteAccount command) {
        MediaAccount account = store.require(command.accountId());
        List<String> sessionIds = infrai.listSessionIds(command.userId());
        sessionIds.forEach(infrai::revokeSession);
        infrai.revokeCredential(command.credentialId());
        store.erase(command.accountId());
        return new DeletionReceipt(
                account.accountId(),
                sessionIds.size(),
                command.credentialId(),
                account.ingestedAssets().size(),
                account.processingJobs().size(),
                account.creatorDeliveries().size(),
                "deleted");
    }

    public record DeleteAccount(String accountId, String userId, String credentialId) {
        public DeleteAccount {
            if (accountId == null || accountId.isBlank()) throw new IllegalArgumentException("accountId is required");
            if (userId == null || userId.isBlank()) throw new IllegalArgumentException("userId is required");
            if (credentialId == null || credentialId.isBlank()) throw new IllegalArgumentException("credentialId is required");
        }
    }

    public record DeletionReceipt(String accountId, int revokedSessions, String revokedCredentialId,
                                  int erasedAssets, int cancelledProcessingJobs,
                                  int erasedCreatorDeliveries, String status) {}
}
