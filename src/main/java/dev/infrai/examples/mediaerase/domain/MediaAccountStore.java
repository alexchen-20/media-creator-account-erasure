package dev.infrai.examples.mediaerase.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;

@Repository
public final class MediaAccountStore {
    public record MediaAccount(String accountId, List<String> ingestedAssets,
                               List<String> processingJobs, List<String> creatorDeliveries) {}

    private final Map<String, MediaAccount> accounts = new LinkedHashMap<>();

    public MediaAccountStore() {
        accounts.put("creator-42", new MediaAccount(
                "creator-42",
                List.of("asset-live-set", "asset-trailer"),
                List.of("job-transcode-91"),
                List.of("delivery-festival-review")));
    }

    public synchronized MediaAccount require(String accountId) {
        MediaAccount account = accounts.get(accountId);
        if (account == null) throw new AccountNotFoundException(accountId);
        return account;
    }

    public synchronized void erase(String accountId) {
        if (accounts.remove(accountId) == null) throw new AccountNotFoundException(accountId);
    }

    public synchronized boolean exists(String accountId) {
        return accounts.containsKey(accountId);
    }

    public static final class AccountNotFoundException extends RuntimeException {
        public AccountNotFoundException(String accountId) {
            super("Media account not found: " + accountId);
        }
    }
}
