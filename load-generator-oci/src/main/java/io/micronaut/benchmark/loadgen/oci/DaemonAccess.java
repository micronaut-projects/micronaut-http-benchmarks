package io.micronaut.benchmark.loadgen.oci;

import io.micronaut.context.annotation.Context;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ServerFilter;
import io.micronaut.http.exceptions.HttpStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

@Context
@ServerFilter("/v1/**")
public final class DaemonAccess {
    private final String token;

    public DaemonAccess() throws IOException {
        Files.createDirectories(Path.of("output/daemon"));
        Path file = Path.of("output/daemon").resolve("token");
        if (!Files.exists(file)) {
            byte[] bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
            String value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            try {
                Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                Files.writeString(file, value);
            } catch (FileAlreadyExistsException ignored) {
            }
        }
        token = Files.readString(file).trim();
        if (token.isEmpty()) {
            throw new IOException("Empty daemon token file: " + file);
        }
    }

    @RequestFilter
    public void authenticate(HttpRequest<?> request) {
        String supplied = request.getHeaders().get("X-Benchmark-Token");
        if (supplied == null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
            throw new HttpStatusException(HttpStatus.UNAUTHORIZED, "Invalid daemon token");
        }
    }
}
