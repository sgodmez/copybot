package com.copybot.plugin.exec.process;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Finds the executable of a program: a path is taken as written, a bare name is looked up in the folders of the
 * PATH, on Windows with the extensions of PATHEXT. Done here rather than left to the system, whose lookup on
 * Windows also searches the current folder and the folder of Java.
 */
public final class ProgramResolver {

    private static final String DEFAULT_PATHEXT = ".COM;.EXE;.BAT;.CMD";

    /**
     * Where to look.
     *
     * @param path    the PATH, null: none
     * @param pathExt the PATHEXT (Windows), null: the default one
     * @param windows true on Windows
     */
    public record Environment(String path, String pathExt, boolean windows) {

        public static Environment current() {
            return new Environment(System.getenv("PATH"), System.getenv("PATHEXT"),
                    System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows"));
        }
    }

    private ProgramResolver() {
    }

    /** The executable of the program in the environment of this process, empty when there is none. */
    public static Optional<Path> resolve(String program) {
        return resolve(program, Environment.current());
    }

    /**
     * @param program a path (holding a separator, or absolute) or a bare name
     * @return the executable, absolute; empty when the name is in no folder of the PATH or the path is no file
     */
    public static Optional<Path> resolve(String program, Environment environment) {
        if (program == null || program.isBlank()) {
            return Optional.empty();
        }
        String name = program.strip();
        try {
            if (name.contains("/") || name.contains("\\") || Path.of(name).isAbsolute()) {
                Path path = Path.of(name);
                return candidates(path, environment).stream().filter(p -> executable(p, environment)).findFirst()
                        .map(p -> p.toAbsolutePath().normalize());
            }
            if (environment.path() == null) {
                return Optional.empty();
            }
            for (String folder : environment.path().split(environment.windows() ? ";" : ":")) {
                String dir = folder.strip();
                if (environment.windows() && dir.length() > 1 && dir.startsWith("\"") && dir.endsWith("\"")) {
                    dir = dir.substring(1, dir.length() - 1);
                }
                if (dir.isEmpty()) {
                    continue; // an empty entry is the current folder: not searched
                }
                Path base;
                try {
                    base = Path.of(dir);
                } catch (InvalidPathException e) {
                    continue;
                }
                if (!base.isAbsolute()) {
                    continue; // relative to the current folder: not searched either
                }
                for (Path candidate : candidates(base.resolve(name), environment)) {
                    if (executable(candidate, environment)) {
                        return Optional.of(candidate.toAbsolutePath().normalize());
                    }
                }
            }
        } catch (InvalidPathException e) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    /**
     * True for a batch file (.bat, .cmd): Windows runs it through cmd.exe, whose parsing of the arguments makes a
     * file name holding {@code &} or {@code %} unsafe.
     */
    public static boolean isBatch(Path program) {
        String name = program.getFileName() == null ? "" : program.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".bat") || name.endsWith(".cmd");
    }

    /** On Windows the name as written when it has an extension of PATHEXT, else the name with each of them. */
    private static List<Path> candidates(Path path, Environment environment) {
        if (!environment.windows()) {
            return List.of(path);
        }
        List<String> extensions = new ArrayList<>();
        String pathExt = environment.pathExt() == null || environment.pathExt().isBlank()
                ? DEFAULT_PATHEXT : environment.pathExt();
        for (String extension : pathExt.split(";")) {
            if (!extension.isBlank()) {
                extensions.add(extension.strip().toLowerCase(Locale.ROOT));
            }
        }
        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (extensions.stream().anyMatch(fileName::endsWith)) {
            return List.of(path);
        }
        List<Path> candidates = new ArrayList<>();
        for (String extension : extensions) {
            candidates.add(path.resolveSibling(path.getFileName() + extension));
        }
        return candidates;
    }

    private static boolean executable(Path path, Environment environment) {
        return Files.isRegularFile(path) && (environment.windows() || Files.isExecutable(path));
    }
}
