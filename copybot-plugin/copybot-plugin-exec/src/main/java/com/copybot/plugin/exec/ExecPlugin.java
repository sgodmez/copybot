package com.copybot.plugin.exec;

import com.copybot.plugin.api.action.ActionDefinition;
import com.copybot.plugin.api.action.IProcessAction;
import com.copybot.plugin.api.definition.AbstractPlugin;
import com.copybot.plugin.exec.actions.ExecProcess;
import com.copybot.plugin.exec.actions.FfmpegImage;
import com.copybot.plugin.exec.actions.FfmpegThumbnail;
import com.copybot.plugin.exec.actions.FfmpegVideo;

import java.util.List;

/** External programs run on the files of a pipeline: any program (exec.process), and ffmpeg. */
public class ExecPlugin extends AbstractPlugin {

    @Override
    public String getPluginCode() {
        return "exec";
    }

    @Override
    public List<ActionDefinition<? extends IProcessAction>> getProcessActions() {
        return List.of(
                new ActionDefinition<>(ExecProcess.CODE, ExecProcess.class),
                new ActionDefinition<>(FfmpegVideo.CODE, FfmpegVideo.class),
                new ActionDefinition<>(FfmpegImage.CODE, FfmpegImage.class),
                new ActionDefinition<>(FfmpegThumbnail.CODE, FfmpegThumbnail.class)
        );
    }
}
