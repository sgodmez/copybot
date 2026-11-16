package com.copybot.plugin.ffmpeg;

import com.copybot.plugin.api.action.ActionDefinition;
import com.copybot.plugin.api.action.IProcessAction;
import com.copybot.plugin.api.definition.AbstractPlugin;
import com.copybot.plugin.ffmpeg.actions.FfmpegImage;
import com.copybot.plugin.ffmpeg.actions.FfmpegThumbnail;
import com.copybot.plugin.ffmpeg.actions.FfmpegVideo;

import java.util.List;

/** ffmpeg run on the files of a pipeline: compressed videos, images into JPEG, thumbnails. */
public class FfmpegPlugin extends AbstractPlugin {

    @Override
    public String getPluginCode() {
        return "ffmpeg";
    }

    @Override
    public List<ActionDefinition<? extends IProcessAction>> getProcessActions() {
        return List.of(
                new ActionDefinition<>(FfmpegVideo.CODE, FfmpegVideo.class),
                new ActionDefinition<>(FfmpegImage.CODE, FfmpegImage.class),
                new ActionDefinition<>(FfmpegThumbnail.CODE, FfmpegThumbnail.class)
        );
    }
}
