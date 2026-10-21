package com.smartcloset.auth.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class ConsoleEmailSenderTest {

    @TempDir
    Path tempDir;

    @Test
    void writesActionTokenToLocalOutboxWithoutLoggingToken(CapturedOutput output) throws Exception {
        Path outboxPath = tempDir.resolve("account-email-outbox.log");
        ConsoleEmailSender sender = new ConsoleEmailSender(
                outboxPath,
                Clock.fixed(Instant.parse("2026-06-21T12:00:00Z"), ZoneOffset.UTC)
        );

        sender.sendEmailVerification("demo@example.com", "SECRET_ACTION_TOKEN");

        assertThat(Files.readString(outboxPath))
                .contains("2026-06-21T12:00:00Z\tEMAIL_VERIFICATION\tdemo@example.com\tSECRET_ACTION_TOKEN");
        assertThat(output)
                .contains("account_email_written")
                .doesNotContain("SECRET_ACTION_TOKEN");
        String absolutePath = outboxPath.toAbsolutePath().toString();
        assertThat(output.getAll())
                .containsAnyOf(absolutePath, new ObjectMapper().writeValueAsString(absolutePath));
    }
}
