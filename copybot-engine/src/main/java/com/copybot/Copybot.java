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

        CopybotEngine.run(pipelinePath, pipelineState -> System.out.println(pipelineState));

        try {
            CopybotEngine.waitForCompletion();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            CopybotEngine.destroy();
            System.out.println("Cancelled !");
            return;
        } catch (ExecutionException e) {
            // The pipeline itself blew up (e.g. an unresolvable step): that is a failure, not a
            // cancellation, and it must reach the exit code instead of being reported as "Cancelled".
            CopybotEngine.destroy();
            throw CopybotException.wrapIfNeeded(e.getCause() == null ? e : e.getCause());
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
