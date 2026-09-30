package dev.infrai.examples.mediaerase.web;

import dev.infrai.examples.mediaerase.domain.MediaAccountStore.AccountNotFoundException;
import dev.infrai.examples.mediaerase.infrai.InfraiException;
import dev.infrai.examples.mediaerase.service.AccountDeletionService;
import dev.infrai.examples.mediaerase.service.AccountDeletionService.DeleteAccount;
import dev.infrai.examples.mediaerase.service.AccountDeletionService.DeletionReceipt;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/media-accounts")
public final class AccountDeletionController {
    private final AccountDeletionService deletions;

    public AccountDeletionController(AccountDeletionService deletions) {
        this.deletions = deletions;
    }

    @PostMapping("/delete")
    public DeletionReceipt delete(@RequestBody DeleteAccount command) {
        return deletions.delete(command);
    }

    @ExceptionHandler(InfraiException.class)
    ResponseEntity<Map<String, Object>> rejected(InfraiException exception) {
        int sourceStatus = exception.statusCode();
        HttpStatus status = sourceStatus >= 400 && sourceStatus < 500
                ? HttpStatus.valueOf(sourceStatus) : HttpStatus.BAD_GATEWAY;
        return ResponseEntity.status(status).body(Map.of(
                "code", exception.code(),
                "message", exception.getMessage()));
    }

    @ExceptionHandler({IllegalArgumentException.class, AccountNotFoundException.class})
    ResponseEntity<Map<String, String>> invalid(RuntimeException exception) {
        HttpStatus status = exception instanceof AccountNotFoundException
                ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(Map.of("message", exception.getMessage()));
    }
}
