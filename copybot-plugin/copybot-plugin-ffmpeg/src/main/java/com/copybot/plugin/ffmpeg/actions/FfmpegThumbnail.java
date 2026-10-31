package com.copybot.plugin.ffmpeg.actions;

import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.ffmpeg.ffmpeg.Ffmpeg;
import com.copybot.plugin.ffmpeg.ffmpeg.FfmpegSetup;
import com.copybot.plugin.ffmpeg.ffmpeg.ImageArguments;
import com.copybot.plugin.ffmpeg.ffmpeg.VideoProgress;
import com.copybot.plugin.exec.process.ItemFiles;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * ffmpeg.thumbnail: a small JPEG of an image or of a video, named with "-thumb" ({@code photo.jpg} gives
 * {@code photo-thumb.jpg}) or, for a video, "-video-thumb" ({@code clip.mov} gives {@code clip-video-thumb.jpg}, so
 * that a Live Photo's HEIC and MOV do not collide; spec exec-ffmpeg §7). An image is reduced; a video gives its frame at a tenth of its
 * duration, at most 10 s. Every other item is filtered out (the branch writes nothing for it), and so is a video
 * without a video stream.
 */
public class FfmpegThumbnail extends FfmpegJpegAction<FfmpegThumbnailConfig> {

    public static final String CODE = "ffmpeg.thumbnail";
    static final String SUFFIX = "-thumb";
    /** The suffix of a video's thumbnail: IMG_1234.HEIC and IMG_1234.MOV of a Live Photo do not collide. */
    static final String VIDEO_SUFFIX = "-video-thumb";

    public FfmpegThumbnail() {
    }

    /** For the tests: the working files under this folder, ffmpeg looked for in this context. */
    FfmpegThumbnail(Path workFolder, Supplier<FfmpegSetup.Context> context) {
        super(workFolder, context);
    }

    @Override
    protected Class<FfmpegThumbnailConfig> getConfigClass() {
        return FfmpegThumbnailConfig.class;
    }

    private FfmpegThumbnailConfig config() {
        FfmpegThumbnailConfig config = getConfig();
        return config == null ? new FfmpegThumbnailConfig(null, null, null, null) : config;
    }

    @Override
    protected String code() {
        return CODE;
    }

    @Override
    protected int timeoutSeconds() {
        return config().timeoutSeconds();
    }

    private static String thumbnailName(String name) {
        return ItemFiles.base(name) + (FfmpegImage.isImage(name) ? SUFFIX : VIDEO_SUFFIX) + ".jpg";
    }

    /** A video whose seek gave nothing: its first frame. */
    @Override
    protected Optional<List<String>> retryArguments(Ffmpeg ffmpeg, Path in, Path out, String name) {
        if (FfmpegImage.isImage(name) || !FfmpegVideo.isVideo(name)) {
            return Optional.empty();
        }
        return Optional.of(ImageArguments.videoFrame(in, out, 0, config().size(), ImageArguments.THUMBNAIL_QUALITY));
    }

    @Override
    public List<WorkItem> doProcess(WorkItem item) {
        String name = ItemFiles.name(item);
        int size = config().size();
        int quality = ImageArguments.THUMBNAIL_QUALITY;
        Optional<WorkItem> thumbnail;
        if (FfmpegImage.isImage(name)) {
            thumbnail = toJpeg(item, thumbnailName(name),
                    (ffmpeg, in, out) -> ImageArguments.image(in, out, quality, size));
        } else if (FfmpegVideo.isVideo(name)) {
            thumbnail = toJpeg(item, thumbnailName(name), (ffmpeg, in, out) ->
                    ImageArguments.videoFrame(in, out, ImageArguments.seekMicros(duration(ffmpeg, in, name)), size,
                            quality));
        } else {
            return List.of(); // neither an image nor a video
        }
        return thumbnail.map(List::of).orElseGet(List::of); // empty: no video stream
    }

    /** The duration of the video in microseconds, read from its header; 0 when unknown. */
    private long duration(Ffmpeg ffmpeg, Path in, String name) throws InterruptedException {
        AtomicLong duration = new AtomicLong(-1);
        run(ffmpeg, ImageArguments.inspect(in), name, line -> {
            if (duration.get() < 0) {
                duration.set(VideoProgress.durationMicros(line)); // the first one is the input's
            }
        }); // exit 1: no output named
        return Math.max(0, duration.get());
    }

    /** An image or a video named as its thumbnail, every other item filtered out. Nothing is run. */
    @Override
    public Optional<List<WorkItem>> dryRun(WorkItem item) {
        String name = ItemFiles.name(item);
        if (!FfmpegImage.isImage(name) && !FfmpegVideo.isVideo(name)) {
            return Optional.of(List.of());
        }
        item.getMetadatas().display().put("name", thumbnailName(name));
        return Optional.of(List.of(item));
    }
}
