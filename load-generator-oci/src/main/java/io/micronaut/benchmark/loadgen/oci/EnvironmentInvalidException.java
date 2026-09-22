package io.micronaut.benchmark.loadgen.oci;

final class EnvironmentInvalidException extends Exception {
    EnvironmentInvalidException(String message, Throwable cause) {
        super(message, cause);
    }
}
