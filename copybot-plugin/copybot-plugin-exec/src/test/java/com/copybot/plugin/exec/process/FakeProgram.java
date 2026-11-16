package com.copybot.plugin.exec.process;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * The program the tests launch in a JVM of their own (JDK classes only): {@link #command} gives its command line.
 * Its first argument says what it does:
 * <ul>
 *     <li>{@code copy <in> <out>}: copies the file, "copied" on stderr, exit 0;</li>
 *     <li>{@code copy-exit <in> <out> <code>}: copies the file, exits with the code;</li>
 *     <li>{@code exit <code> <text>}: the text on stderr, exits with the code;</li>
 *     <li>{@code stderr <bytes>}: that many bytes of numbered lines on stderr, then "THE END", exit 3;</li>
 *     <li>{@code stderr-cr <bytes>}: the same with lines ended by a carriage return only;</li>
 *     <li>{@code utf8}: "caf", e acute, " ", a check mark, in UTF-8 on stderr, exit 2;</li>
 *     <li>{@code lines <line>...}: each line on stderr, exit 0;</li>
 *     <li>{@code stdout <line>...}: each line on the standard output, exit 0;</li>
 *     <li>{@code sleep <millis>}: sleeps, exit 0;</li>
 *     <li>{@code child <pidFile>}: starts a sleeping child, writes its pid and its own to the file, sleeps;</li>
 *     <li>{@code stdin}: reads its input to its end, exit 0.</li>
 * </ul>
 */
public final class FakeProgram {

    private FakeProgram() {
    }

    /** java -cp &lt;the test classes&gt; FakeProgram, the arguments to add after it. */
    public static List<String> command(String... args) {
        List<String> command = new ArrayList<>(List.of(java(), "-cp", classes(), FakeProgram.class.getName()));
        command.addAll(List.of(args));
        return command;
    }

    public static String java() {
        return ProcessHandle.current().info().command().orElseThrow();
    }

    public static String classes() {
        try {
            return Path.of(FakeProgram.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "copy" -> {
                Files.copy(Path.of(args[1]), Path.of(args[2]));
                System.err.println("copied");
            }
            case "copy-exit" -> {
                Files.copy(Path.of(args[1]), Path.of(args[2]));
                System.exit(Integer.parseInt(args[3]));
            }
            case "exit" -> {
                System.err.println(args[2]);
                System.exit(Integer.parseInt(args[1]));
            }
            case "stderr" -> {
                int bytes = Integer.parseInt(args[1]);
                StringBuilder text = new StringBuilder();
                for (int line = 0; text.length() < bytes; line++) {
                    text.append("line ").append(line).append('\n');
                }
                System.err.print(text);
                System.err.println("THE END");
                System.exit(3);
            }
            case "stderr-cr" -> {
                // progress lines ended with a carriage return only, as the statistics of an encoder
                int bytes = Integer.parseInt(args[1]);
                StringBuilder text = new StringBuilder();
                for (int line = 0; text.length() < bytes; line++) {
                    text.append("frame ").append(line).append('\r');
                }
                System.err.print(text);
                System.err.print("THE END");
                System.exit(3);
            }
            case "utf8" -> {
                // the UTF-8 bytes, whatever the encoding of this JVM
                System.err.write(new byte[]{'c', 'a', 'f', (byte) 0xC3, (byte) 0xA9, ' ', (byte) 0xE2, (byte) 0x9C,
                        (byte) 0x93});
                System.err.flush();
                System.exit(2);
            }
            case "lines" -> {
                for (int i = 1; i < args.length; i++) {
                    System.err.println(args[i]);
                }
            }
            case "stdout" -> {
                for (int i = 1; i < args.length; i++) {
                    System.out.println(args[i]);
                }
            }
            case "sleep" -> Thread.sleep(Long.parseLong(args[1]));
            case "child" -> {
                Process child = new ProcessBuilder(command("sleep", "60000")).start();
                Path pids = Path.of(args[1]);
                Path partial = Files.writeString(pids.resolveSibling(pids.getFileName() + ".tmp"),
                        child.pid() + " " + ProcessHandle.current().pid(), StandardCharsets.US_ASCII);
                Files.move(partial, pids, StandardCopyOption.ATOMIC_MOVE); // never read half written
                Thread.sleep(60000);
            }
            case "stdin" -> System.in.readAllBytes();
            default -> throw new IOException("unknown: " + args[0]);
        }
    }
}
