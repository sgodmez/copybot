package com.copybot;

import com.copybot.engine.CopybotEngine;
import com.copybot.exception.CopybotException;
import com.copybot.logger.CopybotLogger;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

@CommandLine.Command(name = "Copybot", version = "0.1", mixinStandardHelpOptions = true)
public class Copybot implements Runnable {

    private static final CopybotLogger LOG = CopybotLogger.getLogger(Copybot.class);

    @CommandLine.Option(names = {"-c", "--config-file"}, description = "Configuration file")
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
                throw CopybotException.wrapIfNeeded(e);
            } else {
                System.err.println(e.getMessage());
            }
        }
    }

    public void doRun() {
        CopybotEngine.init(Optional.ofNullable(configPath));

        if (!pipelinePath.toFile().canRead()) {
            throw CopybotException.ofResource("pipeline.not-found", pipelinePath);
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
        int exitCode = doMain(args);
        System.exit(exitCode);
    }

    public static int doMain(String... args) {
        return new CommandLine(new Copybot()).execute(args);
    }
}
