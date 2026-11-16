package com.copybot.config;

import java.nio.file.Path;
import java.util.Optional;

/** Stores whose protection fails as Windows DPAPI does for a user without a loaded profile. */
public final class SecretStoreFakes {

    public static final String UNAVAILABLE = "the protection of this user is unavailable (fake)";

    private SecretStoreFakes() {
    }

    /** A store whose protection cannot encrypt nor decrypt anything: {@link SecretStore.ProtectionException}. */
    public static SecretStore withoutProtection(Path file) {
        return SecretStore.withProtector(file, new SecretStore.Protector() {
            @Override
            public byte[] protect(byte[] clear) throws SecretStore.ProtectionException {
                throw new SecretStore.ProtectionException(UNAVAILABLE);
            }

            @Override
            public Optional<byte[]> unprotect(byte[] stored) throws SecretStore.ProtectionException {
                throw new SecretStore.ProtectionException(UNAVAILABLE);
            }
        });
    }
}
