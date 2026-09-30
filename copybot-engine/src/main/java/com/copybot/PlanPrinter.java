package com.copybot;

import com.copybot.engine.Plan;
import com.copybot.engine.pipeline.WorkItemExecution;
import com.copybot.engine.resume.ResumePoint;
import com.copybot.engine.resume.ResumeProposal;

import java.io.PrintStream;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Dry-run output: the resume point, its origin, the warnings, then one line per item. */
final class PlanPrinter {

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss").withZone(ZoneId.systemDefault());

    private PlanPrinter() {
    }

    static void print(Plan plan, ResumePoint override, PrintStream out) {
        ResumeProposal proposal = plan.getProposal();
        ResumePoint point = override != null ? override : proposal.point();
        out.println("Resume point: " + describe(point) + " [" + (override != null ? "MANUAL" : proposal.source()) + "]");
        proposal.warnings().forEach(w -> out.println("Warning: " + w));
        for (WorkItemExecution item : plan.getOrderedItems()) {
            String name = Copybot.name(item);
            String line = switch (item.getStatus()) {
                case ERROR -> "ERROR " + name + "  " + Copybot.message(item.getError());
                case SKIPPED -> "SKIP  " + name + "  " + item.getSkipReason();
                default -> "COPY  " + name;
            };
            out.println(line);
        }
    }

    private static String named(ResumePoint point) {
        return point.key().name().isEmpty() ? "" : point.key().name() + " ";
    }

    private static String describe(ResumePoint point) {
        return switch (point.kind()) {
            case ALL -> "everything";
            case AFTER -> "after " + named(point) + "(" + DATE.format(point.key().date()) + ")";
            case FROM -> "from " + named(point) + "(" + DATE.format(point.key().date()) + ")";
        };
    }
}
