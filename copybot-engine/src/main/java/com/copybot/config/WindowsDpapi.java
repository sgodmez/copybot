package com.copybot.config;

import com.copybot.resources.ResourcesEngine;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Windows DPAPI ({@code CryptProtectData} / {@code CryptUnprotectData} of crypt32) for the current user: only this
 * Windows account, on this machine, can decrypt what it encrypted. No UI is ever shown. The clear bytes the calls
 * see are wiped from native memory once done. A value that cannot be decrypted is told apart from a user who cannot
 * decrypt anything now (e.g. a scheduled task run without its profile loaded): the latter cannot protect and read
 * back a probe either.
 */
final class WindowsDpapi implements SecretStore.Protector {

    private static final System.Logger LOG = System.getLogger(WindowsDpapi.class.getName());

    private static final int CRYPTPROTECT_UI_FORBIDDEN = 0x1;
    /** DATA_BLOB: DWORD cbData, (padding), BYTE *pbData. */
    private static final StructLayout DATA_BLOB = MemoryLayout.structLayout(
            JAVA_INT.withName("cbData"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pbData"));
    private static final long CB_DATA = DATA_BLOB.byteOffset(groupElement("cbData"));
    private static final long PB_DATA = DATA_BLOB.byteOffset(groupElement("pbData"));
    private static final StructLayout CALL_STATE = Linker.Option.captureStateLayout();
    private static final long LAST_ERROR = CALL_STATE.byteOffset(groupElement("GetLastError"));
    private static final byte[] PROBE = "copybot-dpapi-probe".getBytes(StandardCharsets.US_ASCII);

    private final MethodHandle protectData;
    private final MethodHandle unprotectData;
    private final MethodHandle localFree;

    /** The outcome of one call: the output bytes, or the Windows error code. */
    private record Result(byte[] bytes, int error) {
    }

    WindowsDpapi() {
        Linker linker = Linker.nativeLinker();
        SymbolLookup crypt32 = SymbolLookup.libraryLookup("crypt32", Arena.global());
        // BOOL (DATA_BLOB *in, LPCWSTR / LPWSTR *description, DATA_BLOB *entropy, PVOID reserved,
        //       CRYPTPROTECT_PROMPTSTRUCT *prompt, DWORD flags, DATA_BLOB *out)
        FunctionDescriptor crypt = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS,
                JAVA_INT, ADDRESS);
        Linker.Option lastError = Linker.Option.captureCallState("GetLastError");
        protectData = linker.downcallHandle(crypt32.findOrThrow("CryptProtectData"), crypt, lastError);
        unprotectData = linker.downcallHandle(crypt32.findOrThrow("CryptUnprotectData"), crypt, lastError);
        SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
        localFree = linker.downcallHandle(kernel32.findOrThrow("LocalFree"), FunctionDescriptor.of(ADDRESS, ADDRESS));
    }

    @Override
    public byte[] protect(byte[] clear) throws SecretStore.ProtectionException {
        Result result = call(protectData, clear);
        if (result.bytes() == null) {
            throw new SecretStore.ProtectionException(
                    ResourcesEngine.getString("secret.protection.failed", code(result.error())));
        }
        return result.bytes();
    }

    @Override
    public Optional<byte[]> unprotect(byte[] stored) throws SecretStore.ProtectionException {
        Result result = call(unprotectData, stored);
        if (result.bytes() != null) {
            return Optional.of(result.bytes());
        }
        if (!roundTrips()) {
            throw new SecretStore.ProtectionException(
                    ResourcesEngine.getString("secret.protection.unavailable", code(result.error())));
        }
        // the code only, never the data
        LOG.log(System.Logger.Level.DEBUG, "CryptUnprotectData refused a value: " + code(result.error()));
        return Optional.empty(); // encrypted by another user or on another machine, or damaged
    }

    /** True when this user can encrypt and decrypt now. */
    private boolean roundTrips() throws SecretStore.ProtectionException {
        Result protectedProbe = call(protectData, PROBE);
        if (protectedProbe.bytes() == null) {
            return false;
        }
        Result back = call(unprotectData, protectedProbe.bytes());
        return back.bytes() != null && Arrays.equals(PROBE, back.bytes());
    }

    /** "0x8009000B": the Windows error as Microsoft documents it. */
    private static String code(int error) {
        return String.format("0x%08X", error);
    }

    private Result call(MethodHandle function, byte[] input) throws SecretStore.ProtectionException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment state = arena.allocate(CALL_STATE);
            MemorySegment data = arena.allocate(Math.max(1, input.length));
            MemorySegment.copy(input, 0, data, JAVA_BYTE, 0, input.length);
            MemorySegment in = arena.allocate(DATA_BLOB);
            in.set(JAVA_INT, CB_DATA, input.length);
            in.set(ADDRESS, PB_DATA, data);
            MemorySegment out = arena.allocate(DATA_BLOB);
            try {
                int ok = (int) function.invokeExact(state, in, MemorySegment.NULL, MemorySegment.NULL,
                        MemorySegment.NULL, MemorySegment.NULL, CRYPTPROTECT_UI_FORBIDDEN, out);
                if (ok == 0) {
                    return new Result(null, state.get(JAVA_INT, LAST_ERROR));
                }
                int size = out.get(JAVA_INT, CB_DATA);
                MemorySegment result = out.get(ADDRESS, PB_DATA).reinterpret(size);
                try {
                    return new Result(result.toArray(JAVA_BYTE), 0);
                } finally {
                    result.fill((byte) 0); // a clear value never stays in memory freed to the system
                    MemorySegment ignored = (MemorySegment) localFree.invokeExact(result);
                }
            } finally {
                data.fill((byte) 0);
            }
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Throwable t) {
            throw new SecretStore.ProtectionException(
                    ResourcesEngine.getString("secret.protection.failed", String.valueOf(t)));
        }
    }
}
