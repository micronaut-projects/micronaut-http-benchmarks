package io.micronaut.benchmark.loadgen.oci;

public interface RemoteJdk {
    RemoteJdk SYSTEM = new RemoteJdk() {
        @Override
        public String command(String command) {
            return command;
        }
    };

    static RemoteJdk fromPrefix(String prefix) {
        return prefix.isEmpty() ? SYSTEM : new RemoteJdk() {
            @Override
            public String command(String command) {
                return prefix + command;
            }
        };
    }

    String command(String command);
}
