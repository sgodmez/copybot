import com.copybot.plugin.api.definition.IPlugin;
import com.copybot.plugin.ffmpeg.FfmpegPlugin;

module com.copybot.plugin.ffmpeg {
    requires com.copybot.engine;
    // the plugin exec: its process package runs ffmpeg (loaded over the layer of exec, see the README)
    requires com.copybot.plugin.exec;
    requires java.net.http;

    exports com.copybot.plugin.ffmpeg;
    exports com.copybot.plugin.ffmpeg.actions;

    opens com.copybot.plugin.ffmpeg.i18n;
    // the configuration records are read by Gson
    opens com.copybot.plugin.ffmpeg.actions;

    provides IPlugin with FfmpegPlugin;
}
