package com.copybot.plugin.exec.ffmpeg;

import com.copybot.plugin.api.action.ItemProgress;

import java.util.Arrays;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Reads the progress of an encoding in the lines of the error output of ffmpeg ({@code -progress pipe:2 -nostats}):
 * the duration of the input in its header ("Duration: 00:01:23.45", no ffprobe needed), then the time written
 * ({@code out_time_us=}); reports the percent to the item, once per percent, 100 at the end. Used by the reader
 * thread of one run only.
 */
public final class VideoProgress implements Consumer<String> {

    private static final Pattern DURATION = Pattern.compile("^\\s*Duration: (\\d+):(\\d{2}):(\\d{2}(?:\\.\\d+)?)");
    /** A line of the progress blocks: key=value. */
    private static final Pattern PROGRESS_LINE = Pattern.compile("^[a-z0-9_]+=.*$");
    private static final String OUT_TIME = "out_time_us=";

    private final ItemProgress progress;
    /** The duration of the input in microseconds; 0: not known (yet). */
    private long duration;
    private boolean durationRead;
    private int reported = -1;

    /** @param progress the channel of the item, captured on its thread */
    public VideoProgress(ItemProgress progress) {
        this.progress = progress == null ? ItemProgress.NONE : progress;
    }

    @Override
    public void accept(String line) {
        if (!durationRead) {
            long read = durationMicros(line);
            if (read >= 0) {
                durationRead = true; // the first one is the input's; 0 (N/A): unknown
                duration = read;
                return;
            }
        }
        if (line.startsWith(OUT_TIME) && duration > 0) {
            long written;
            try {
                written = Long.parseLong(line.substring(OUT_TIME.length()).strip());
            } catch (NumberFormatException e) {
                return; // N/A
            }
            if (written >= 0) {
                report((int) Math.min(99, written * 100 / duration)); // 100 once ffmpeg says it is done
            }
        } else if (line.equals("progress=end")) {
            report(100);
        }
    }

    /**
     * The duration of a line of the header of ffmpeg ("  Duration: 00:01:23.45, start: ..."), in microseconds: 0
     * when ffmpeg does not know it (N/A), -1 when the line gives no duration.
     */
    public static long durationMicros(String line) {
        Matcher matcher = DURATION.matcher(line);
        if (matcher.find()) {
            return Long.parseLong(matcher.group(1)) * 3_600_000_000L
                    + Long.parseLong(matcher.group(2)) * 60_000_000L
                    + Math.round(Double.parseDouble(matcher.group(3)) * 1_000_000);
        }
        return line.strip().startsWith("Duration:") ? 0 : -1;
    }

    private void report(int percent) {
        if (percent != reported) {
            reported = percent;
            progress.percent(percent);
        }
    }

    /** The end of the error output without the lines of the progress blocks: for the message of a failure. */
    public static String withoutProgress(String stderr) {
        return Arrays.stream(stderr.split("\\r?\\n|\\r"))
                .filter(line -> !PROGRESS_LINE.matcher(line).matches())
                .collect(Collectors.joining("\n"))
                .strip();
    }
}
