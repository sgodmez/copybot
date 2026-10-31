package com.copybot.plugin.exec.ffmpeg;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The arguments of ffmpeg for ffmpeg.video (spec exec-ffmpeg §5): the first video stream encoded in H.265 or H.264
 * by the processor or a hardware encoder, the audio in AAC or copied, into an MP4 that starts playing at once
 * ({@code +faststart}), keeps the metadata of the source (its date of creation) and, in H.265, is read by Apple
 * ({@code hvc1}). The progress goes to the error output ({@code -progress pipe:2 -nostats}).
 *
 * @param codec     the video codec
 * @param quality   the quality
 * @param maxHeight the height the video is reduced to when it is higher, the ratio kept (never enlarged); null or
 *                  below 2: not reduced
 * @param copyAudio true: the audio streams copied as they are; false: encoded in AAC at 160 kb/s
 * @param encoder   the encoder; never {@link Encoder#AUTO}, chosen before
 */
public record VideoArguments(Codec codec, Quality quality, Integer maxHeight, boolean copyAudio, Encoder encoder) {

    /** The device of the VA-API encoders (Linux). */
    static final String VAAPI_DEVICE = "/dev/dri/renderD128";

    /** The video codec: its value in the configuration, the processor encoder and the prefix of the others. */
    public enum Codec {
        H265("libx265", "hevc"),
        H264("libx264", "h264");

        private final String software;
        private final String prefix;

        Codec(String software, String prefix) {
            this.software = software;
            this.prefix = prefix;
        }

        public String code() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** The quality: a CRF of 22/26/30 for x265, 4 less for x264, or what stands for it with each encoder. */
    public enum Quality {
        HIGH(22, 65),
        MEDIUM(26, 55),
        SMALL(30, 45);

        private final int crf;
        private final int videoToolbox;

        Quality(int crf, int videoToolbox) {
            this.crf = crf;
            this.videoToolbox = videoToolbox;
        }

        /** The CRF of x265 (x264: 4 less), also the constant quality of the hardware encoders (lower is better). */
        int crf(Codec codec) {
            return codec == Codec.H264 ? crf - 4 : crf;
        }

        /** The quality of VideoToolbox, 1 to 100, higher is better (H.264: 5 more). */
        int videoToolbox(Codec codec) {
            return codec == Codec.H264 ? videoToolbox + 5 : videoToolbox;
        }
    }

    /** The encoder: the processor, a hardware one, or {@code auto} (the first hardware one that works). */
    public enum Encoder {
        AUTO(null),
        CPU(null),
        NVENC("nvenc"),
        QSV("qsv"),
        AMF("amf"),
        VIDEOTOOLBOX("videotoolbox"),
        VAAPI("vaapi");

        /** The hardware encoders in the order {@code auto} tries them. */
        public static final List<Encoder> AUTO_ORDER = List.of(NVENC, QSV, AMF, VIDEOTOOLBOX, VAAPI);

        private final String suffix;

        Encoder(String suffix) {
            this.suffix = suffix;
        }

        /** True for an encoder of a graphics card or of the processor's graphics: the "gpu" resource. */
        public boolean hardware() {
            return suffix != null;
        }

        /** The name of the ffmpeg encoder for this codec ("hevc_nvenc", "libx264"...). */
        public String ffmpegName(Codec codec) {
            if (this == AUTO) {
                throw new IllegalArgumentException("auto is chosen before");
            }
            return suffix == null ? codec.software : codec.prefix + "_" + suffix;
        }

        public String code() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** The constant of the enum written as this value of the configuration (case ignored); empty when none. */
    public static <E extends Enum<E>> Optional<E> parse(Class<E> type, String value) {
        if (value == null) {
            return Optional.empty();
        }
        String wanted = value.strip();
        for (E constant : type.getEnumConstants()) {
            if (constant.name().equalsIgnoreCase(wanted)) {
                return Optional.of(constant);
            }
        }
        return Optional.empty();
    }

    /** The arguments encoding {@code in} into the MP4 {@code out}. */
    public List<String> transcode(Path in, Path out) {
        String name = encoder.ffmpegName(codec);
        List<String> arguments = new ArrayList<>(List.of("-hide_banner", "-nostdin", "-y"));
        arguments.addAll(device(encoder));
        arguments.addAll(List.of("-i", in.toString()));
        // the first real video stream (not a cover picture), every audio stream if any, the metadata of the source
        arguments.addAll(List.of("-map", "0:V:0", "-map", "0:a?", "-map_metadata", "0"));
        List<String> filters = new ArrayList<>();
        int height = maxHeight == null ? 0 : maxHeight - maxHeight % 2;
        if (height >= 2) {
            // only down (min), even dimensions (-2 and trunc) as 4:2:0 encoders want
            filters.add("scale=-2:'min(" + height + ",trunc(ih/2)*2)'");
        }
        filters.addAll(upload(encoder));
        if (!filters.isEmpty()) {
            arguments.addAll(List.of("-vf", String.join(",", filters)));
        }
        arguments.addAll(video(name));
        arguments.addAll(copyAudio ? List.of("-c:a", "copy") : List.of("-c:a", "aac", "-b:a", "160k"));
        if (codec == Codec.H265) {
            arguments.addAll(List.of("-tag:v", "hvc1")); // what Apple reads
        }
        // use_metadata_tags: the keys of Apple (place, date of creation) written as they came, not dropped
        arguments.addAll(List.of("-movflags", "+faststart+use_metadata_tags", "-progress", "pipe:2", "-nostats",
                out.toString()));
        return arguments;
    }

    /** The arguments of a probe of the encoder: one black frame encoded to nothing, exit 0 when it works. */
    public static List<String> probe(Codec codec, Encoder encoder) {
        VideoArguments probe = new VideoArguments(codec, Quality.MEDIUM, null, false, encoder);
        String name = encoder.ffmpegName(codec);
        List<String> arguments = new ArrayList<>(List.of("-hide_banner", "-nostdin"));
        arguments.addAll(device(encoder));
        arguments.addAll(List.of("-f", "lavfi", "-i", "color=black:s=256x256", "-frames:v", "1"));
        List<String> upload = upload(encoder);
        if (!upload.isEmpty()) {
            arguments.addAll(List.of("-vf", String.join(",", upload)));
        }
        arguments.addAll(probe.video(name));
        arguments.addAll(List.of("-f", "null", "-"));
        return arguments;
    }

    /** The video encoder, its quality options and its pixel format. */
    private List<String> video(String name) {
        String crf = String.valueOf(quality.crf(codec));
        List<String> video = new ArrayList<>(switch (encoder) {
            case CPU -> codec == Codec.H265
                    // Main10: a 10-bit (HDR) source kept, never a range extension (4:2:2, 12 bits) few devices read
                    ? List.of("-c:v", name, "-preset", "medium", "-crf", crf, "-pix_fmt", "yuv420p10le",
                    "-x265-params", "log-level=error")
                    : List.of("-c:v", name, "-preset", "medium", "-crf", crf);
            case NVENC -> List.of("-c:v", name, "-preset", "p5", "-rc", "vbr", "-cq", crf, "-b:v", "0");
            case QSV -> List.of("-c:v", name, "-preset", "medium", "-global_quality", crf);
            case AMF -> codec == Codec.H264
                    ? List.of("-c:v", name, "-rc", "cqp", "-qp_i", crf, "-qp_p", crf, "-qp_b", crf)
                    : List.of("-c:v", name, "-rc", "cqp", "-qp_i", crf, "-qp_p", crf);
            case VIDEOTOOLBOX -> List.of("-c:v", name, "-q:v", String.valueOf(quality.videoToolbox(codec)));
            case VAAPI -> List.of("-c:v", name, "-rc_mode", "CQP", "-qp", crf); // nv12 by its filters
            case AUTO -> throw new IllegalArgumentException("auto is chosen before");
        });
        if (codec == Codec.H264 && encoder != Encoder.VAAPI) {
            // 8 bits 4:2:0, what every player reads in H.264: a 10-bit source makes the hardware encoders fail and
            // x264 write High 10; nv12 is the 8-bit 4:2:0 format Quick Sync takes
            video.addAll(List.of("-pix_fmt", encoder == Encoder.QSV ? "nv12" : "yuv420p"));
        }
        return video;
    }

    /** The options before the input opening the device of the encoder. */
    private static List<String> device(Encoder encoder) {
        return encoder == Encoder.VAAPI ? List.of("-vaapi_device", VAAPI_DEVICE) : List.of();
    }

    /** The filters handing the frames over to the device of the encoder, after the others. */
    private static List<String> upload(Encoder encoder) {
        return encoder == Encoder.VAAPI ? List.of("format=nv12", "hwupload") : List.of();
    }
}
