package com.copybot.engine;

import com.copybot.exception.CopybotException;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.logging.Logger;

@CommandLine.Command(name = "Copybot", version = "0.1", mixinStandardHelpOptions = true)
public class Copybot implements Runnable {

    private static final Logger LOG = Logger.getLogger(Copybot.class.getCanonicalName());

    @CommandLine.Option(names = {"-c", "--config-file"}, description = "Configuration file", defaultValue = "./config.json")
    private Path configPath;

    @CommandLine.Option(names = {"-p", "--pipeline"}, description = "Pipeline file", required = true)
    private Path pipelinePath;

    @CommandLine.Option(names = {"-dr", "--dry-run"}, description = "Dry run")
    private boolean isDryRun;

    @CommandLine.Option(names = {"--debug"}, description = "Debug")
    private boolean isDebug;

    @Override
    public void run() {
        try {
            doRun();
        } catch (Exception e) {
            if (isDebug) {
                throw e;
            } else {
                System.err.println(e.getMessage());
            }
        }
    }

    public void doRun() {
        if (!configPath.toFile().canRead()) {
            LOG.warning("test");
            System.out.println("No config file found at '{0}', using defaults");
        }

        CopybotEngine.init(null);


        if (!pipelinePath.toFile().canRead()) {
            throw CopybotException.ofResourceBundle("pipeline.not-found", pipelinePath);
        }


        // var plugin = PluginEngine.getLoadedPlugins().get(1).getPluginInstance();

        CopybotEngine.run(null, pipelineState -> System.out.println(pipelineState));

        try {
            CopybotEngine.waitForCompletion();
        } catch (InterruptedException | ExecutionException e) {
            CopybotEngine.destroy();
            System.out.println("Cancelled !");
            return;
        }
        System.out.println("Done !");
    }


    public static void main(String... args) {
        int exitCode = new CommandLine(new Copybot()).execute(args);
        System.exit(exitCode);
    }
}
