package com.copybot.plugin.exec.ffmpeg;

import com.copybot.plugin.exec.process.ProgramResolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Finds ffmpeg (spec exec-ffmpeg §4), in this order: the path of the action, the copy the plugin downloaded
 * ({@code <installRoot>/<version>/bin/ffmpeg(.exe)}, the highest version), the {@code ffmpeg} of the PATH.
 */
public final class FfmpegLocator {

    /** The name of a version folder of a downloaded copy ("8.1"); anything else (an unfinished download) is not. */
    private static final Pattern VERSION_FOLDER = Pattern.compile("\\d{1,6}(\\.\\d{1,6})*");

    private FfmpegLocator() {
    }

    /**
     * @param installRoot the folder of the downloaded copies; null: none
     * @return the executable, absolute; empty when there is none
     * @throws FfmpegUnavailableException the path of the action is set and is not found (no other ffmpeg is then
     *                                    taken: the user asked for this one), or is a batch file
     */
    public static Optional<Path> locate(FfmpegSettings settings, ProgramResolver.Environment environment,
                                        Path installRoot) throws FfmpegUnavailableException {
        String explicit = settings.ffmpegPath() == null ? "" : settings.ffmpegPath().strip();
        if (!explicit.isEmpty()) {
            Path executable = ProgramResolver.resolve(explicit, environment)
                    .orElseThrow(() -> new FfmpegUnavailableException(FfmpegText.of("ffmpeg.path-not-found",
                            explicit)));
            if (environment.windows() && ProgramResolver.isBatch(executable)) {
                throw new FfmpegUnavailableException(FfmpegText.of("exec.error.batch", explicit));
            }
            return Optional.of(executable);
        }
        Optional<Path> downloaded = downloaded(installRoot, environment.windows());
        if (downloaded.isPresent()) {
            return downloaded;
        }
        return ProgramResolver.resolve("ffmpeg", environment)
                .filter(executable -> !(environment.windows() && ProgramResolver.isBatch(executable)));
    }

    /** The name of the executable of ffmpeg. */
    static String executableName(boolean windows) {
        return windows ? "ffmpeg.exe" : "ffmpeg";
    }

    /** The downloaded copy of the highest version, empty when there is none. */
    static Optional<Path> downloaded(Path installRoot, boolean windows) {
        if (installRoot == null || !Files.isDirectory(installRoot)) {
            return Optional.empty();
        }
        try (Stream<Path> folders = Files.list(installRoot)) {
            return folders
                    .filter(folder -> VERSION_FOLDER.matcher(folder.getFileName().toString()).matches())
                    .sorted(Comparator.comparing((Path folder) -> folder.getFileName().toString(),
                            FfmpegLocator::compareVersions).reversed())
                    .map(folder -> folder.resolve("bin").resolve(executableName(windows)))
                    .filter(Files::isRegularFile)
                    .map(executable -> executable.toAbsolutePath().normalize())
                    .findFirst();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** Compares two versions made of numbers ("8.1" &lt; "10.0"). */
    static int compareVersions(String a, String b) {
        String[] left = a.split("\\.");
        String[] right = b.split("\\.");
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            int l = i < left.length ? Integer.parseInt(left[i]) : 0;
            int r = i < right.length ? Integer.parseInt(right[i]) : 0;
            if (l != r) {
                return Integer.compare(l, r);
            }
        }
        return 0;
    }
}
