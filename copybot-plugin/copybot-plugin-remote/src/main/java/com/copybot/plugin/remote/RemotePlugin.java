package com.copybot.plugin.remote;

import com.copybot.plugin.api.action.ActionDefinition;
import com.copybot.plugin.api.action.IInAction;
import com.copybot.plugin.api.action.IOutAction;
import com.copybot.plugin.api.definition.AbstractPlugin;
import com.copybot.plugin.remote.ftp.FtpRead;
import com.copybot.plugin.remote.ftp.FtpWrite;
import com.copybot.plugin.remote.s3.S3Read;
import com.copybot.plugin.remote.s3.S3Write;
import com.copybot.plugin.remote.sftp.SftpRead;
import com.copybot.plugin.remote.sftp.SftpWrite;

import java.util.List;

/**
 * Remote servers (spec remote §3): SFTP, FTP / FTPS and S3, as inputs and outputs. The protocols share the reading
 * and the writing of {@link com.copybot.plugin.remote.core}; each one only implements
 * {@link com.copybot.plugin.remote.core.RemoteFs}.
 */
public class RemotePlugin extends AbstractPlugin {

    @Override
    public String getPluginCode() {
        return "remote";
    }

    @Override
    public List<ActionDefinition<? extends IInAction>> getInActions() {
        return List.of(new ActionDefinition<>("sftp.read", SftpRead.class),
                new ActionDefinition<>("ftp.read", FtpRead.class),
                new ActionDefinition<>("s3.read", S3Read.class));
    }

    @Override
    public List<ActionDefinition<? extends IOutAction>> getOutActions() {
        return List.of(new ActionDefinition<>("sftp.write", SftpWrite.class),
                new ActionDefinition<>("ftp.write", FtpWrite.class),
                new ActionDefinition<>("s3.write", S3Write.class));
    }
}
