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
 *     <li>{@code ffmpeg <version> -version}: a fake ffmpeg giving its version line on the standard output, exit 0
 *     (other arguments: exit 1);</li>
 *     <li>{@code ffmpeg-video <state folder> <arguments>}: a fake ffmpeg for ffmpeg.video, see
 *     {@link #ffmpegVideo};</li>
 *     <li>{@code ffmpeg-image <state folder> <arguments>}: a fake ffmpeg for ffmpeg.image and ffmpeg.thumbnail,
 *     see {@link #ffmpegImage};</li>
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
                // progress lines ended with a carriage return only, as ffmpeg's statistics
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
            case "ffmpeg" -> {
                // ffmpeg -version: the version line on the standard output
                if (args.length > 2 && "-version".equals(args[2])) {
                    System.out.println("ffmpeg version " + args[1] + " Copyright (c) 2000-2026 the FFmpeg developers");
                    System.out.println("built with gcc");
                } else {
                    System.err.println("unexpected arguments");
                    System.exit(1);
                }
            }
            case "ffmpeg-video" -> ffmpegVideo(Path.of(args[1]), List.of(args).subList(2, args.length));
            case "ffmpeg-image" -> ffmpegImage(Path.of(args[1]), List.of(args).subList(2, args.length));
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

    /**
     * A fake ffmpeg: each call writes its arguments, one per line, in a new file of {@code <state>/calls}.
     * <ul>
     *     <li>{@code -version}: version n7.1;</li>
     *     <li>a probe ({@code -f lavfi ... -c:v <encoder>}): exit 0 when the encoder is a line of
     *     {@code <state>/encoders}, else "Unknown encoder" and exit 1;</li>
     *     <li>else an encoding of the file after {@code -i} into the last argument, by its content: "NO VIDEO" gives
     *     the error of ffmpeg for a file without a video stream (exit 1), "FAIL" an error after some progress (exit
     *     1), "SLEEP" sleeps a minute; any other content gives a duration of 10 s, its progress, and the file
     *     written as "mp4:" then the content.</li>
     * </ul>
     */
    private static void ffmpegVideo(Path state, List<String> arguments) throws Exception {
        Path calls = Files.createDirectories(state.resolve("calls"));
        Files.write(Files.createTempFile(calls, "call", ".txt"), arguments, StandardCharsets.UTF_8);
        if (arguments.equals(List.of("-version"))) {
            System.out.println("ffmpeg version n7.1 Copyright (c) 2000-2026 the FFmpeg developers");
            return;
        }
        if (arguments.contains("lavfi")) {
            String encoder = arguments.get(arguments.indexOf("-c:v") + 1);
            Path encoders = state.resolve("encoders");
            if (Files.exists(encoders) && Files.readAllLines(encoders).contains(encoder)) {
                return;
            }
            System.err.println("Unknown encoder '" + encoder + "'");
            System.exit(1);
        }
        Path in = Path.of(arguments.get(arguments.indexOf("-i") + 1));
        Path out = Path.of(arguments.getLast());
        String content = Files.readString(in, StandardCharsets.UTF_8);
        System.err.println("Input #0, mov,mp4,m4a,3gp,3g2,mj2, from '" + in.getFileName() + "':");
        if (content.startsWith("NO VIDEO")) {
            System.err.println("  Duration: 00:00:10.00, start: 0.000000, bitrate: 128 kb/s");
            System.err.println("Stream map '0:V:0' matches no streams.");
            System.err.println("To ignore this, add a trailing '?' to the map.");
            System.exit(1);
        }
        System.err.println("  Duration: 00:00:10.00, start: 0.000000, bitrate: 1200 kb/s");
        if (content.startsWith("SLEEP")) {
            Thread.sleep(60000);
        }
        System.err.print("frame=10\nout_time_us=2500000\nprogress=continue\n");
        System.err.flush();
        if (content.startsWith("FAIL")) {
            System.err.println("Error while decoding stream #0:0: Invalid data found when processing input");
            System.err.print("frame=20\nout_time_us=5000000\nprogress=continue\n");
            System.exit(1);
        }
        System.err.print("frame=20\nout_time_us=5000000\nprogress=continue\nout_time_us=10000000\nprogress=end\n");
        System.err.flush();
        Files.writeString(out, "mp4:" + content, StandardCharsets.UTF_8);
    }

    /**
     * A fake ffmpeg: each call writes its arguments, one per line, in a new file of {@code <state>/calls}.
     * <ul>
     *     <li>{@code -version}: the version in {@code <state>/version}, n7.1 when there is none;</li>
     *     <li>an inspection (the input is the last argument): "Duration: " then what follows "VIDEO " at the start of
     *     the content (e.g. "VIDEO 00:00:30.00"), 00:00:10.00 for "NO VIDEO", nothing else; then exit 1, as
     *     ffmpeg without an output;</li>
     *     <li>else a JPEG of the file after {@code -i} into the last argument, by its content: "NO VIDEO" with
     *     {@code -map 0:V:0} gives the error of ffmpeg for a file without a video stream (exit 1), "FAIL" an error
     *     (exit 1), "EMPTY" exit 0 without a file ("SEEK-EMPTY" at the end: only with -ss), "SLEEP" sleeps a minute; any other content gives the file
     *     written as "jpg:" then the content.</li>
     * </ul>
     */
    private static void ffmpegImage(Path state, List<String> arguments) throws Exception {
        Path calls = Files.createDirectories(state.resolve("calls"));
        Files.write(Files.createTempFile(calls, "call", ".txt"), arguments, StandardCharsets.UTF_8);
        if (arguments.equals(List.of("-version"))) {
            Path version = state.resolve("version");
            System.out.println("ffmpeg version " + (Files.exists(version) ? Files.readString(version).strip() : "n7.1")
                    + " Copyright (c) 2000-2026 the FFmpeg developers");
            return;
        }
        Path in = Path.of(arguments.get(arguments.indexOf("-i") + 1));
        String content = Files.readString(in, StandardCharsets.UTF_8);
        System.err.println("Input #0, mov,mp4,m4a,3gp,3g2,mj2, from '" + in.getFileName() + "':");
        if (arguments.getLast().equals(in.toString())) {
            if (content.startsWith("VIDEO ")) {
                System.err.println("  Duration: " + content.substring("VIDEO ".length()).strip().split(" ")[0]
                        + ", start: 0.000000, bitrate: 1200 kb/s");
            } else if (content.startsWith("NO VIDEO")) {
                System.err.println("  Duration: 00:00:10.00, start: 0.000000, bitrate: 128 kb/s");
            }
            System.err.println("At least one output file must be specified");
            System.exit(1);
        }
        if (content.startsWith("NO VIDEO") && arguments.contains("0:V:0")) {
            System.err.println("Stream map '0:V:0' matches no streams.");
            System.exit(1);
        }
        if (content.startsWith("SLEEP")) {
            Thread.sleep(60000);
        }
        if (content.startsWith("FAIL")) {
            System.err.println("Error while decoding stream #0:0: Invalid data found when processing input");
            System.exit(1);
        }
        if (content.startsWith("EMPTY") || (content.endsWith("SEEK-EMPTY") && arguments.contains("-ss"))) {
            return; // exit 0 without a file ("SEEK-EMPTY": only when seeking)
        }
        Files.writeString(Path.of(arguments.getLast()), "jpg:" + content, StandardCharsets.UTF_8);
    }
}
