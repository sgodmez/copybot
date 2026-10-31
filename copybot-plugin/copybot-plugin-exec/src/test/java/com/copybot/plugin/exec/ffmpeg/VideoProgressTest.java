package com.copybot.plugin.exec.ffmpeg;

import com.copybot.plugin.api.action.WorkStatus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The progress of an encoding read from the error output of ffmpeg ({@code -progress pipe:2 -nostats}). */
public class VideoProgressTest {

    private final List<Integer> percents = new ArrayList<>();
    private final VideoProgress progress = new VideoProgress(status -> percents.add(status.actionPercent()));

    @Test
    public void theTimeWrittenOverTheDurationOfTheInputIsThePercent() {
        progress.accept("Input #0, mov,mp4,m4a,3gp,3g2,mj2, from 'clip.mov':");
        progress.accept("  Duration: 00:01:40.00, start: 0.000000, bitrate: 1200 kb/s");
        progress.accept("  Duration: 00:00:01.00, start: 0.000000, bitrate: 1 kb/s"); // not the first: ignored
        progress.accept("frame=10");
        progress.accept("out_time_us=25000000");
        progress.accept("progress=continue");
        progress.accept("out_time_us=25500000"); // the same percent: not reported again
        progress.accept("out_time_us=N/A");
        progress.accept("out_time_us=-5000"); // before the start: ignored
        progress.accept("out_time_us=100000000");
        progress.accept("progress=end");

        assertEquals(List.of(25, 99, 100), percents);
    }

    @Test
    public void hoursAndFractionsOfTheDuration() {
        progress.accept("  Duration: 01:00:00.50, start: 0.000000, bitrate: 1 kb/s");
        progress.accept("out_time_us=1800250000");
        assertEquals(List.of(50), percents);
    }

    @Test
    public void withoutADurationNothingIsReportedButTheEnd() {
        progress.accept("  Duration: N/A, bitrate: N/A");
        progress.accept("out_time_us=5000000");
        progress.accept("progress=end");
        assertEquals(List.of(100), percents);
    }

    @Test
    public void theDurationOfALineOfTheHeader() {
        assertEquals(5_025_000_000L, VideoProgress.durationMicros("  Duration: 01:23:45.00, start: 0.000000"));
        assertEquals(1_500_000, VideoProgress.durationMicros("  Duration: 00:00:01.50, bitrate: 1 kb/s"));
        assertEquals(0, VideoProgress.durationMicros("  Duration: N/A, bitrate: N/A"), "unknown");
        assertEquals(-1, VideoProgress.durationMicros("Input #0, avi, from 'clip.avi':"), "not a duration");
    }

    @Test
    public void theReportsKeepTheActionName() {
        List<WorkStatus> statuses = new ArrayList<>();
        VideoProgress named = new VideoProgress(statuses::add);
        named.accept("  Duration: 00:00:10.00, start: 0.000000, bitrate: 1 kb/s");
        named.accept("out_time_us=5000000");
        assertNull(statuses.getFirst().actionName(), "the name of the step stays");
    }

    @Test
    public void theProgressLinesAreLeftOutOfAnErrorMessage() {
        assertEquals("Input #0, mov, from 'a.mov':\nError while decoding stream #0:0\nConversion failed!",
                VideoProgress.withoutProgress("Input #0, mov, from 'a.mov':\r\nframe=12\nfps=0.00\n"
                        + "bitrate=  12.3kbits/s\nError while decoding stream #0:0\nout_time_us=1000\n"
                        + "progress=continue\nConversion failed!"));
        assertEquals("", VideoProgress.withoutProgress(""));
    }
}
