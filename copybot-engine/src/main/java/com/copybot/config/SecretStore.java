package com.copybot.config;

import com.copybot.resources.ResourcesEngine;
import com.copybot.utils.AtomicFiles;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The secrets of this user on this machine (spec run-end, Secrets): a JSON object {@code { "name": "<protected value
 * in base64>" }}, always in the user profile, never in the data folder (which may be on a NAS): Windows
 * {@code %APPDATA%\Copybot\secrets.json}, elsewhere {@code $XDG_CONFIG_HOME/copybot/secrets.json} (default
 * {@code ~/.config/copybot}). Windows protects the values with DPAPI for the current user; elsewhere they are only
 * base64 in a file readable by its owner only ({@code rw-------}). The file is replaced atomically. The values never
 * appear in an exception, a log or {@link #toString()}.
 * <p>
 * There is one instance per file in a JVM ({@link #forCurrentUser}, {@link #at}): the editor's dialog and the step
 * resolutions of the same Copybot are serialised. Two processes (the application and the CLI) changing the store at
 * the same moment are not: the last one to write wins, the file is never torn.
 */
public final class SecretStore {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final boolean POSIX = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    private static final Map<Path, SecretStore> STORES = new ConcurrentHashMap<>();

    /** Encrypts the values for the store; the identity (base64 only) where there is no user-bound encryption. */
    interface Protector {
        byte[] protect(byte[] clear) throws ProtectionException;

        /**
         * The clear value; empty when it cannot be decrypted by this user here (another user, another machine,
         * damaged data).
         *
         * @throws ProtectionException this user cannot decrypt anything now (e.g. the profile is not loaded)
         */
        Optional<byte[]> unprotect(byte[] stored) throws ProtectionException;
    }

    /** A secret that exists but cannot be decrypted by this user on this machine; the message is its name. */
    public static final class UnreadableSecretException extends Exception {
        public UnreadableSecretException(String name) {
            super(name);
        }
    }

    /**
     * The protection of the system (Windows DPAPI) failed for this user, whatever the secret; the message is
     * localised, with the diagnostic of the system, and holds no value.
     */
    public static final class ProtectionException extends IOException {
        public ProtectionException(String localisedMessage) {
            super(localisedMessage);
        }
    }

    private static final Protector CLEAR = new Protector() {
        @Override
        public byte[] protect(byte[] clear) {
            return clear;
        }

        @Override
        public Optional<byte[]> unprotect(byte[] stored) {
            return Optional.of(stored);
        }
    };

    /** Created at the first use: DPAPI is reached only when a secret is read or written. Guarded by the class. */
    private static Protector dpapi;

    private final Path file;
    /** Null: the protection of the platform. */
    private final Protector protector;

    private SecretStore(Path file, Protector protector) {
        this.file = file;
        this.protector = protector;
    }

    /** The store of the current user, at its place in the profile. */
    public static SecretStore forCurrentUser() {
        return at(defaultFile(isWindows(), System.getenv("APPDATA"), System.getenv("XDG_CONFIG_HOME"),
                System.getProperty("user.home")));
    }

    /** The store at this place, protected as the current user's (tests, tools): the same instance for a file. */
    public static SecretStore at(Path file) {
        return STORES.computeIfAbsent(file.toAbsolutePath().normalize(), path -> new SecretStore(path, null));
    }

    // visible for tests: a store of its own (not shared) with this protection
    static SecretStore withProtector(Path file, Protector protector) {
        return new SecretStore(file.toAbsolutePath().normalize(), protector);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").startsWith("Windows");
    }

    static Path defaultFile(boolean windows, String appData, String xdgConfigHome, String userHome) {
        Path folder;
        if (windows) {
            folder = (isSet(appData) ? Path.of(appData) : Path.of(userHome, "AppData", "Roaming")).resolve("Copybot");
        } else if (isSet(xdgConfigHome) && Path.of(xdgConfigHome).isAbsolute()) { // a relative one is invalid
            folder = Path.of(xdgConfigHome).resolve("copybot");
        } else {
            folder = Path.of(userHome, ".config", "copybot");
        }
        return folder.resolve("secrets.json").toAbsolutePath().normalize();
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }

    /** True for a name a reference can hold: letters, digits, '.', '_' and '-'. */
    public static boolean isValidName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    public Path file() {
        return file;
    }

    /** The names of the secrets, sorted; empty when there is no file. */
    public synchronized List<String> names() throws IOException {
        List<String> names = new ArrayList<>(read().keySet());
        names.sort(null);
        return names;
    }

    /**
     * The clear value of the secret, empty when it is not defined.
     *
     * @throws UnreadableSecretException it is defined but cannot be decrypted (another user, another machine)
     * @throws IOException               the store cannot be read or is not a store; a {@link ProtectionException}
     *                                   when this user cannot decrypt anything now (e.g. profile not loaded)
     */
    public synchronized Optional<String> get(String name) throws IOException, UnreadableSecretException {
        JsonElement stored = read().get(name);
        if (stored == null) {
            return Optional.empty();
        }
        if (!stored.isJsonPrimitive() || !stored.getAsJsonPrimitive().isString()) {
            throw new UnreadableSecretException(name);
        }
        byte[] protectedValue;
        try {
            protectedValue = Base64.getDecoder().decode(stored.getAsString());
        } catch (IllegalArgumentException e) {
            throw new UnreadableSecretException(name);
        }
        byte[] clear = protector().unprotect(protectedValue).orElseThrow(() -> new UnreadableSecretException(name));
        try {
            return Optional.of(new String(clear, StandardCharsets.UTF_8));
        } finally {
            Arrays.fill(clear, (byte) 0);
        }
    }

    /**
     * Saves the secret, replacing its value if any.
     *
     * @throws IllegalArgumentException an invalid name ({@link #isValidName})
     * @throws IOException              the store cannot be read (it is then left as it is) or written
     */
    public synchronized void set(String name, String value) throws IOException {
        if (!isValidName(name)) {
            throw new IllegalArgumentException("invalid secret name");
        }
        JsonObject secrets = read();
        byte[] clear = value.getBytes(StandardCharsets.UTF_8);
        try {
            secrets.addProperty(name, Base64.getEncoder().encodeToString(protector().protect(clear)));
        } finally {
            Arrays.fill(clear, (byte) 0);
        }
        write(secrets);
    }

    /** Removes the secret; false when it was not defined. */
    public synchronized boolean delete(String name) throws IOException {
        JsonObject secrets = read();
        if (secrets.remove(name) == null) {
            return false;
        }
        write(secrets);
        return true;
    }

    private Protector protector() throws ProtectionException {
        if (protector != null) {
            return protector;
        }
        return isWindows() ? dpapi() : CLEAR;
    }

    private static synchronized Protector dpapi() throws ProtectionException {
        if (dpapi == null) {
            try {
                dpapi = new WindowsDpapi();
            } catch (VirtualMachineError e) {
                throw e;
            } catch (RuntimeException | LinkageError e) { // crypt32 cannot be linked, native access refused
                throw new ProtectionException(ResourcesEngine.getString("secret.protection.failed", String.valueOf(e)));
            }
        }
        return dpapi;
    }

    private JsonObject read() throws IOException {
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return new JsonObject();
        }
        try {
            JsonElement root = JsonParser.parseString(text);
            if (root.isJsonObject()) {
                return root.getAsJsonObject();
            }
        } catch (JsonParseException e) {
            // reported below, without the parser's message: it may quote the content
        }
        throw new IOException(file + " is not a secret store");
    }

    private void write(JsonObject secrets) throws IOException {
        Path folder = file.getParent();
        if (POSIX) {
            if (!Files.isDirectory(folder)) {
                Files.createDirectories(folder, PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("rwx------")));
            }
            AtomicFiles.write(file, GSON.toJson(secrets).getBytes(StandardCharsets.UTF_8),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } else {
            Files.createDirectories(folder);
            AtomicFiles.write(file, GSON.toJson(secrets).getBytes(StandardCharsets.UTF_8));
        }
    }

    @Override
    public String toString() {
        return "SecretStore[" + file + "]";
    }
}
