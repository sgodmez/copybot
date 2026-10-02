package com.copybot;

import com.copybot.engine.Plan;
import com.copybot.engine.pipeline.WorkItemExecution;
import com.copybot.engine.resume.ResumePoint;
import com.copybot.engine.resume.ResumeProposal;
import com.copybot.engine.resume.ResumeSource;
import com.copybot.resources.ResourcesEngine;

import java.io.PrintStream;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Dry-run output: the resume point, its origin, the warnings (the steps' configuration first, then the
 * resume ones), then one line per item. Texts in the current language (engine bundle, keys cli.*).
 */
final class PlanPrinter {

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss").withZone(ZoneId.systemDefault());

    private PlanPrinter() {
    }

    static void print(Plan plan, ResumePoint override, PrintStream out) {
        ResumeProposal proposal = plan.getProposal();
        ResumePoint point = override != null ? override : proposal.point();
        ResumeSource source = override != null ? ResumeSource.MANUAL : proposal.source();
        out.println(ResourcesEngine.getString("cli.plan.resume-point", describe(point),
                ResourcesEngine.getString("cli.plan.source." + source.name())));
        plan.getState().getWarnings().forEach(w -> out.println(ResourcesEngine.getString("cli.warning", w)));
        proposal.warnings().forEach(w -> out.println(ResourcesEngine.getString("cli.warning", w)));
        for (WorkItemExecution item : plan.getOrderedItems()) {
            String name = Copybot.name(item);
            String line = switch (item.getStatus()) {
                case ERROR -> ResourcesEngine.getString("cli.item.error", name, Copybot.message(item.getError()));
                case SKIPPED -> ResourcesEngine.getString("cli.plan.skip", name, item.getSkipReason());
                default -> ResourcesEngine.getString("cli.plan.copy", name);
            };
            out.println(line);
        }
    }

    private static String named(ResumePoint point) {
        return point.key().name().isEmpty() ? "" : point.key().name() + " ";
    }

    private static String describe(ResumePoint point) {
        return switch (point.kind()) {
            case ALL -> ResourcesEngine.getString("cli.plan.resume.all");
            case AFTER -> ResourcesEngine.getString("cli.plan.resume.after", named(point), DATE.format(point.key().date()));
            case FROM -> ResourcesEngine.getString("cli.plan.resume.from", named(point), DATE.format(point.key().date()));
        };
    }
}
