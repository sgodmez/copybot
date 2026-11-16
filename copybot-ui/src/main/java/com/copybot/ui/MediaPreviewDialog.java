package com.copybot.ui;

import com.copybot.resources.ResourcesEngine;
import com.copybot.ui.model.MediaPreview;
import com.copybot.ui.model.MediaPreview.Kind;
import com.copybot.ui.model.MediaPreview.Orientation;
import com.copybot.ui.util.Background;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Slider;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.animation.PauseTransition;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * The preview of the files of the plan (double click on a row), best effort: pictures, videos and sounds that
 * JavaFX reads; for the others, or when it fails, why, and a button opening the file with the application of the
 * system. Not modal: the plan stays usable. The previous / next buttons (or the arrow keys) go through the files.
 */
final class MediaPreviewDialog {

    /** The ids of what the window shows (its tests read them). */
    private static final String LOADING = "preview-loading";
    private static final String IMAGE = "preview-image";
    private static final String VIDEO = "preview-video";
    private static final String AUDIO = "preview-audio";
    private static final String OTHER = "preview-other";
    private static final String MISSING = "preview-missing";
    private static final String FAILED = "preview-failed";
    private static final String BUSY = "preview-busy";
    private static final String MESSAGE_STYLE = "-fx-text-fill: #e5e7eb; -fx-font-size: 14px;";

    /**
     * How long a video or a sound may take to be ready: without a codec (GStreamer missing on Linux) the player
     * neither gets ready nor fails, and the preview would load forever. Visible for tests.
     */
    static Duration loadTimeout = Duration.seconds(10);

    /** The preview window open, if any: a new preview shows its files there instead of opening another one. */
    private static MediaPreviewDialog open;
    /** A collection is requested and not run yet: the media dropped meanwhile are closed by it too. */
    private static final AtomicBoolean COLLECTING = new AtomicBoolean();

    private List<Path> files = List.of();
    /**
     * The engine prepares or copies: no video nor sound is opened then (its file would stay open, and a copy in
     * "move" mode could not delete it).
     */
    private BooleanSupplier engineBusy = () -> false;
    private final Stage stage = new Stage();
    /** What is shown of the file, on a dark background. */
    private final StackPane center = new StackPane();
    private final Button previous = new Button("◀");
    private final Button next = new Button("▶");
    private final Label position = new Label();
    private final Label name = new Label();
    private final Button openOutside = new Button(ResourcesEngine.getString("preview.open"));
    /** At the bottom: previous / next when there are several files, the path, opening outside. */
    private final HBox navigation = new HBox(8.0);
    /** Under the picture: play / pause and the position of a video or a sound. */
    private final HBox playback = new HBox(8.0);
    private int index;
    /** The picture shown or being decoded: a decoding no longer needed is cancelled. */
    private Image image;
    /** The video or sound playing: released when another file is shown and on close (it keeps the file open). */
    private MediaPlayer player;
    /** The view of the video playing: while it shows the player, a disposed player still keeps the file open. */
    private MediaView video;
    /** Gives up on the video or sound being loaded when it is still not ready after {@link #loadTimeout}. */
    private PauseTransition loading;
    /**
     * Each file shown has a new one, closing drops it: what is read in the background for an earlier file, or
     * after the window is closed, is released instead of shown.
     */
    private Object token;

    private MediaPreviewDialog(Window owner) {
        if (owner != null) {
            stage.initOwner(owner);
        }
        center.setStyle("-fx-background-color: #111827;");
        center.setMinSize(0, 0); // the picture follows the window, it never pushes it
        playback.setAlignment(Pos.CENTER_LEFT);
        playback.setPadding(new Insets(6, 10, 0, 10));
        previous.setOnAction(e -> previous());
        next.setOnAction(e -> next());
        openOutside.setOnAction(e -> openOutside());
        name.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(name, Priority.ALWAYS);
        navigation.setAlignment(Pos.CENTER_LEFT);
        navigation.setPadding(new Insets(8, 10, 8, 10));
        BorderPane root = new BorderPane(center);
        root.setBottom(new VBox(playback, navigation));
        Rectangle2D screen = Screen.getPrimary().getVisualBounds();
        Scene scene = new Scene(root, screen.getWidth() * 0.7, screen.getHeight() * 0.75);
        // a filter: the arrows go through the files even when a button or the position slider has the focus
        // (Space only plays / pauses a video or a sound: with a picture, it presses the focused button)
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.isShiftDown() || e.isControlDown() || e.isAltDown() || e.isMetaDown()
                    || (e.getCode() == KeyCode.SPACE && player == null)) {
                return;
            }
            switch (e.getCode()) {
                case LEFT -> previous();
                case RIGHT -> next();
                case ESCAPE -> close();
                case SPACE -> togglePlay();
                default -> {
                    return;
                }
            }
            e.consume();
        });
        stage.setScene(scene);
        stage.setOnHidden(e -> {
            token = null;
            release();
            if (open == this) {
                open = null;
            }
        });
    }

    /**
     * Shows these files, from the one at start, in the preview window: the one already open (brought to the front),
     * else a new one.
     *
     * @param engineBusy whether the engine prepares or copies: no video nor sound is opened then
     */
    static MediaPreviewDialog show(Window owner, BooleanSupplier engineBusy, List<Path> files, int start) {
        if (open == null) {
            open = new MediaPreviewDialog(owner);
        }
        MediaPreviewDialog dialog = open;
        dialog.files = List.copyOf(files);
        dialog.index = start;
        dialog.engineBusy = engineBusy;
        dialog.navigation.getChildren().setAll(files.size() > 1
                ? List.of(dialog.previous, dialog.position, dialog.next, dialog.name, dialog.openOutside)
                : List.of(dialog.name, dialog.openOutside));
        dialog.display();
        dialog.stage.show();
        dialog.stage.toFront();
        return dialog;
    }

    /**
     * The engine starts to prepare or to copy: the video or sound of the preview, if any, stops and its file is
     * released (a copy in "move" mode deletes the files it copied).
     */
    static void engineBusy() {
        if (open != null && open.player != null) {
            open.display();
        }
    }

    /** The id of what is shown: loading, image, video, audio, other (not shown here), missing or failed. */
    String shown() {
        return center.getChildren().isEmpty() ? LOADING : center.getChildren().getFirst().getId();
    }

    int index() {
        return index;
    }

    void next() {
        if (index < files.size() - 1) {
            index++;
            display();
        }
    }

    void previous() {
        if (index > 0) {
            index--;
            display();
        }
    }

    void close() {
        stage.close();
    }

    private void display() {
        release();
        Path file = files.get(index);
        String fileName = file.getFileName() == null ? file.toString() : file.getFileName().toString();
        stage.setTitle(ResourcesEngine.getString("preview.title", fileName));
        name.setText(file.toString());
        position.setText(ResourcesEngine.getString("plan.detail.position", index + 1, files.size()));
        previous.setDisable(index == 0);
        next.setDisable(index == files.size() - 1);
        Object current = new Object();
        token = current;
        Kind kind = MediaPreview.kindOf(file);
        ProgressIndicator loading = new ProgressIndicator();
        loading.setId(LOADING);
        loading.setMaxSize(48, 48);
        center.getChildren().setAll(loading);
        boolean playable = !engineBusy.getAsBoolean();
        if (playable && (kind == Kind.VIDEO || kind == Kind.AUDIO)) {
            startLoading(current); // reading the media can hang too, not only the player
        }
        // the file is read off the JavaFX thread: a share may be slow or gone
        Background.run(() -> read(file, kind, playable), read -> {
            if (token == current) {
                show(file, kind, read);
            } else if (read.media() != null) {
                closeDroppedMedia(); // read too late: dropped at once
            }
        }, failure -> {
            if (token == current) {
                failed(failure);
            }
        });
    }

    /**
     * What is read in the background: whether the file exists, the turn of a JPEG, the media to play (none while
     * the engine is busy).
     */
    private record Read(boolean exists, Orientation orientation, Media media) {
    }

    private static Read read(Path file, Kind kind, boolean playable) throws Exception {
        if (!Files.isRegularFile(file)) {
            return new Read(false, Orientation.UPRIGHT, null);
        }
        return switch (kind) {
            case IMAGE -> {
                try (InputStream in = Files.newInputStream(file)) {
                    yield new Read(true, MediaPreview.orientationOf(in), null);
                }
            }
            case VIDEO, AUDIO -> new Read(true, Orientation.UPRIGHT,
                    playable ? new Media(file.toUri().toString()) : null);
            case OTHER -> new Read(true, Orientation.UPRIGHT, null);
        };
    }

    private void show(Path file, Kind kind, Read read) {
        if (read.media() == null) {
            stopLoading(); // nothing to play: the file is gone, or the engine started meanwhile
        }
        if (!read.exists()) {
            message(MISSING, ResourcesEngine.getString("preview.missing", file));
            return;
        }
        switch (kind) {
            case IMAGE -> showImage(file, read.orientation());
            case VIDEO, AUDIO -> {
                // the engine may have started while the media was read
                if (read.media() != null && !engineBusy.getAsBoolean()) {
                    play(kind, read.media());
                } else {
                    if (read.media() != null) {
                        closeDroppedMedia();
                    }
                    message(BUSY, ResourcesEngine.getString("preview.busy"));
                }
            }
            case OTHER -> message(OTHER, ResourcesEngine.getString("preview.unsupported"));
        }
    }

    private void showImage(Path file, Orientation orientation) {
        // decoded at most at the size of the screen, in its pixels: a photo of 50 Mpx would take hundreds of MB
        Screen screen = Screen.getPrimary();
        double box = Math.max(screen.getBounds().getWidth(), screen.getBounds().getHeight())
                * Math.max(screen.getOutputScaleX(), screen.getOutputScaleY());
        Image picture = new Image(file.toUri().toString(), box, box, true, true, true);
        image = picture;
        ImageView view = new ImageView(picture);
        view.setId(IMAGE);
        view.setPreserveRatio(true);
        view.setSmooth(true);
        // turned a quarter, the picture fits the window the other way round
        view.fitWidthProperty().bind(orientation.quarterTurn() ? center.heightProperty() : center.widthProperty());
        view.fitHeightProperty().bind(orientation.quarterTurn() ? center.widthProperty() : center.heightProperty());
        view.setRotate(orientation.rotate());
        view.setScaleX(orientation.mirror() ? -1 : 1);
        Object current = token;
        boolean[] settled = {false};
        Runnable settle = () -> {
            if (token != current || settled[0]) {
                return;
            }
            if (picture.isError()) {
                settled[0] = true;
                failed(picture.getException());
            } else if (picture.getProgress() >= 1) {
                settled[0] = true;
                center.getChildren().setAll(view);
            }
        };
        picture.progressProperty().addListener((obs, old, progress) -> settle.run());
        picture.errorProperty().addListener((obs, old, error) -> settle.run());
        settle.run(); // already loaded (or failed) when it was quick
    }

    private void play(Kind kind, Media media) {
        MediaPlayer played = new MediaPlayer(media);
        player = played;
        Node shown;
        if (kind == Kind.VIDEO) {
            video = new MediaView(played);
            video.setPreserveRatio(true);
            video.fitWidthProperty().bind(center.widthProperty());
            video.fitHeightProperty().bind(center.heightProperty());
            shown = video;
        } else {
            Label note = new Label("♪");
            note.setStyle("-fx-text-fill: #9ca3af; -fx-font-size: 96px;");
            shown = note;
        }
        shown.setId(kind == Kind.VIDEO ? VIDEO : AUDIO);
        played.setOnError(() -> {
            if (player == played) {
                failed(played.getError());
            }
        });
        Button toggle = new Button(ResourcesEngine.getString("preview.pause"));
        toggle.setOnAction(e -> togglePlay());
        played.statusProperty().addListener((obs, old, status) -> toggle.setText(ResourcesEngine.getString(
                status == MediaPlayer.Status.PLAYING ? "preview.pause" : "preview.play")));
        Slider seek = new Slider(0, 1, 0);
        HBox.setHgrow(seek, Priority.ALWAYS);
        seek.setMaxWidth(Double.MAX_VALUE);
        Label time = new Label(MediaPreview.clock(0));
        time.setMinWidth(Region.USE_PREF_SIZE);
        played.currentTimeProperty().addListener((obs, old, now) -> {
            double total = played.getTotalDuration() == null ? Double.NaN : played.getTotalDuration().toSeconds();
            time.setText(MediaPreview.clock(now.toSeconds()) + " / " + MediaPreview.clock(total));
            if (!seek.isValueChanging() && total > 0) {
                seek.setValue(now.toSeconds() / total);
            }
        });
        // a click or a drag on the slider moves the playing position
        seek.valueProperty().addListener((obs, old, value) -> {
            double total = played.getTotalDuration() == null ? Double.NaN : played.getTotalDuration().toSeconds();
            if (total > 0 && Math.abs(value.doubleValue() * total - played.getCurrentTime().toSeconds()) > 0.5) {
                played.seek(Duration.seconds(value.doubleValue() * total));
            }
        });
        played.setOnEndOfMedia(() -> {
            played.stop(); // back to the start, paused
        });
        // shown once readable: a file it cannot decode fails before, and says so instead
        played.setOnReady(() -> {
            if (player == played) {
                stopLoading();
                playback.getChildren().setAll(toggle, seek, time);
                center.getChildren().setAll(shown);
            }
        });
        played.setAutoPlay(true);
    }

    private void togglePlay() {
        if (player != null) {
            if (player.getStatus() == MediaPlayer.Status.PLAYING) {
                player.pause();
            } else {
                player.play();
            }
        }
    }

    /** The format looked supported, but JavaFX cannot read this file (a codec, a CMYK JPEG, a damaged file). */
    private void failed(Throwable failure) {
        release();
        String reason = Optional.ofNullable(failure).map(Throwable::getMessage).filter(m -> !m.isBlank())
                .orElse(failure == null ? "?" : failure.getClass().getSimpleName());
        message(FAILED, ResourcesEngine.getString("preview.failed", reason));
    }

    private void message(String id, String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setStyle(MESSAGE_STYLE);
        label.setMaxWidth(560);
        VBox box = new VBox(12.0, label);
        // nothing to open when the file is gone; while the engine is busy, an application would keep it open
        if (!MISSING.equals(id) && !BUSY.equals(id)) {
            Button open = new Button(ResourcesEngine.getString("preview.open"));
            open.setOnAction(e -> openOutside());
            box.getChildren().add(open);
        }
        box.setId(id);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(20));
        center.getChildren().setAll(box);
    }

    private void openOutside() {
        if (CopybotMainUi.HOST_SERVICES != null) {
            CopybotMainUi.HOST_SERVICES.showDocument(files.get(index).toUri().toString());
        }
    }

    /**
     * Gives up on this media when it is still not ready after {@link #loadTimeout}: what is still read in the
     * background is dropped when it comes (another token), the player shown is released.
     */
    private void startLoading(Object current) {
        stopLoading();
        Duration timeout = loadTimeout;
        PauseTransition timer = new PauseTransition(timeout);
        timer.setOnFinished(e -> {
            if (token == current && loading == timer) {
                token = new Object();
                release();
                message(FAILED, ResourcesEngine.getString("preview.load-timeout", Math.round(timeout.toSeconds())));
            }
        });
        loading = timer;
        timer.play();
    }

    private void stopLoading() {
        if (loading != null) {
            loading.stop();
            loading = null;
        }
    }

    /** Stops the video or sound shown, if any, and releases its file. */
    private void release() {
        stopLoading();
        playback.getChildren().clear();
        if (image != null) {
            image.cancel(); // when still decoding (going quickly through large photos)
            image = null;
        }
        if (video != null) {
            video.setMediaPlayer(null); // first: a view still showing it keeps the file of a disposed player open
            video = null;
        }
        if (player != null) {
            MediaPlayer released = player;
            player = null;
            released.dispose();
            closeDroppedMedia();
        }
    }

    /**
     * A JavaFX media opens its file with a stream that only the garbage collector closes (disposing the player
     * does not): until then Windows refuses to move or delete the file, which a copy running in "move" mode
     * needs. Once the media is no longer referenced, a collection closes it (a moment later: the player releases
     * it asynchronously). One collection for the media dropped meanwhile (going quickly through clips); none with
     * -XX:+DisableExplicitGC, the files then stay open until the next collection.
     */
    private static void closeDroppedMedia() {
        if (!COLLECTING.compareAndSet(false, true)) {
            return;
        }
        try {
            Background.executor().execute(() -> {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } finally {
                    COLLECTING.set(false);
                }
                System.gc();
            });
        } catch (RejectedExecutionException stopping) {
            COLLECTING.set(false); // the application stops: its files are closed with it
        }
    }
}
