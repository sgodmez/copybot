import com.copybot.plugin.api.definition.IPlugin;
import com.copybot.plugin.remote.RemotePlugin;

module com.copybot.plugin.remote {
    requires com.copybot.engine;
    requires com.jcraft.jsch; // SFTP
    requires org.apache.commons.net; // FTP, FTPS
    requires java.net.http; // S3
    requires java.xml; // S3: its answers

    exports com.copybot.plugin.remote;
    exports com.copybot.plugin.remote.core;
    exports com.copybot.plugin.remote.ftp;
    exports com.copybot.plugin.remote.s3;
    exports com.copybot.plugin.remote.sftp;

    opens com.copybot.plugin.remote.i18n;
    // the configuration records (and their shared parts) are read by Gson
    opens com.copybot.plugin.remote.core;
    opens com.copybot.plugin.remote.ftp;
    opens com.copybot.plugin.remote.s3;
    opens com.copybot.plugin.remote.sftp;

    provides IPlugin with RemotePlugin;
}
