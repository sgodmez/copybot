package com.copybot.plugin.ffmpeg.actions;

import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WorkItemMetadata;
import com.copybot.plugin.ffmpeg.ffmpeg.FfmpegSetup;
import com.copybot.plugin.ffmpeg.ffmpeg.ImageArguments;
import com.copybot.plugin.exec.process.ItemFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * ffmpeg.image: converts an image into a JPEG, HEIC photos by default (spec exec-ffmpeg §6); the other items pass
 * unchanged. ffmpeg writes no EXIF or XMP into the JPEG: the date of the file is the analysed capture date when it
 * is known, else the date of the source (the item's {@code lastModified} is set to the capture date, the date the
 * writer gives the target).
 */
public class FfmpegImage extends FfmpegJpegAction<FfmpegImageConfig> {

    public static final String CODE = "ffmpeg.image";

    /** The extensions of the images ffmpeg reads, among which {@code onlyExtensions} chooses. */
    static final Set<String> IMAGE_EXTENSIONS = Set.of("heic", "heif", "avif", "png", "webp", "tif", "tiff", "bmp",
            "jpg", "jpeg");

    public FfmpegImage() {
    }

    /** For the tests: the working files under this folder, ffmpeg looked for in this context. */
    FfmpegImage(Path workFolder, Supplier<FfmpegSetup.Context> context) {
        super(workFolder, context);
    }

    @Override
    protected Class<FfmpegImageConfig> getConfigClass() {
        return FfmpegImageConfig.class;
    }

    private FfmpegImageConfig config() {
        FfmpegImageConfig config = getConfig();
        return config == null ? new FfmpegImageConfig(null, null, null, null, null, null) : config;
    }

    @Override
    protected String code() {
        return CODE;
    }

    @Override
    protected int timeoutSeconds() {
        return config().timeoutSeconds();
    }

    static boolean isImage(String name) {
        return IMAGE_EXTENSIONS.contains(ItemFiles.extension(name).toLowerCase(Locale.ROOT));
    }

    /** An image ffmpeg reads whose extension is chosen (an empty choice: every one). */
    private boolean converted(String name) {
        List<String> chosen = config().onlyExtensions();
        return isImage(name) && (chosen.isEmpty()
                || chosen.contains(ItemFiles.extension(name).toLowerCase(Locale.ROOT)));
    }

    @Override
    public List<WorkItem> doProcess(WorkItem item) {
        String name = ItemFiles.name(item);
        if (!converted(name)) {
            return List.of(item);
        }
        FfmpegImageConfig config = config();
        return toJpeg(item, ItemFiles.withExtension(name, "jpg"),
                (ffmpeg, in, out) -> ImageArguments.image(in, out, config.quality(), config.maxSize()))
                .map(List::of)
                .orElseGet(() -> List.of(item));
    }

    /** The date of the JPEG: the capture date, else the source's. */
    @Override
    protected void written(WorkItem item, Path out, WorkItem produced) throws IOException {
        Optional<Instant> capture = item.getMetadatas().getTime(WorkItemMetadata.CAPTURE_DATE);
        Optional<Instant> date = capture.or(() -> item.getMetadatas().getTime(WorkItemMetadata.LAST_MODIFIED))
                .or(() -> fileDate(item));
        if (date.isPresent()) {
            Files.setLastModifiedTime(out, FileTime.from(date.get()));
        }
        capture.ifPresent(instant -> produced.getMetadatas().setTime(WorkItemMetadata.LAST_MODIFIED, instant));
    }

    private static Optional<Instant> fileDate(WorkItem item) {
        Optional<Path> file = item.getContentFile();
        try {
            return file.isPresent() ? Optional.of(Files.getLastModifiedTime(file.get()).toInstant())
                    : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** A converted image named in .jpg, dated by its capture; another item unchanged. Nothing is run. */
    @Override
    public Optional<List<WorkItem>> dryRun(WorkItem item) {
        String name = ItemFiles.name(item);
        if (converted(name)) {
            item.getMetadatas().display().put("name", ItemFiles.withExtension(name, "jpg"));
            item.getMetadatas().getTime(WorkItemMetadata.CAPTURE_DATE)
                    .ifPresent(instant -> item.getMetadatas().setTime(WorkItemMetadata.LAST_MODIFIED, instant));
        }
        return Optional.of(List.of(item));
    }
}
