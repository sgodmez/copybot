package com.copybot.plugin.embedded.actions;

import com.copybot.exception.CopybotException;
import com.copybot.plugin.api.action.Companion;
import com.copybot.plugin.api.action.CompanionRole;
import com.copybot.plugin.api.action.TargetCheck;
import com.copybot.plugin.api.action.TargetCheck.Kind;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WorkItemMetadata;
import com.copybot.plugin.api.action.WorkStatus;
import com.copybot.plugin.api.action.WriteContext;
import com.copybot.plugin.api.action.WriteResult;
import com.copybot.plugin.embedded.actions.FileWriteSettings.Compare;
import com.copybot.plugin.embedded.actions.FileWriteSettings.Policy;
import com.copybot.resources.ResourcesEngine;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Writes a main item with its COPY companions as one group (spec grouping-archives, "Ce que le groupe partage"):
 * one conflict decision for the whole group, the main file first, then the companions under its final name; the
 * sources are deleted only once every member is written and verified. READ companions are never written nor deleted.
 */
final class GroupWriter {

    private final FileWriteSettings settings;
    private final ConflictResolver conflicts;
    private final SafeFileWriter writer;

    GroupWriter(FileWriteSettings settings, ConflictResolver conflicts, SafeFileWriter writer) {
        this.settings = settings;
        this.conflicts = conflicts;
        this.writer = writer;
    }

    /** The companions of the item that are written with it. */
    static List<Companion> copies(WorkItem item) {
        return item.getCompanions().stream().filter(c -> c.role() == CompanionRole.COPY).toList();
    }

    /**
     * @throws FileWriteAction.TargetTaken the main target was taken meanwhile: resolved again
     * @throws CopybotException            the group fails (conflict "error", a member not written or not verified, a
     *                                     source not deleted): the main file already written stays
     */
    WriteResult write(WorkItem main, List<Companion> copies, Path target, WriteContext context) throws IOException {
        context.progress().report(new WorkStatus("Copy file " + main.getSourceLocationDisplay(), -1));

        ConflictResolver.GroupDecision decision = conflicts.resolveGroup(main, copies, target);
        if (decision.isSkip()) {
            if (decision.sameFile()) {
                return WriteResult.skipped(decision.mainTarget(), decision.skipReason()); // the only copy: never deleted
            }
            writeCompanions(copies, decision, decision.missingCompanions(), context);
            deleteSources(main, false, copies, decision.missingCompanions(), decision);
            return WriteResult.skipped(decision.mainTarget(), decision.skipReason());
        }

        Path mainTarget = decision.mainTarget();
        SafeFileWriter.Written written;
        try {
            written = writer.write(main, mainTarget, decision.replaceExisting(), context.runId(),
                    context.progress()::percent, context::directoryCreated);
        } catch (FileAlreadyExistsException e) {
            if (FileWriteAction.isFile(e, mainTarget)) {
                throw new FileWriteAction.TargetTaken(e); // resolved again; a temporary file name collision is not
            }
            throw e;
        }
        main.getMetadatas().raw().put(WorkItemMetadata.SHA256, written.sha256());
        List<Integer> toWrite = decision.replaceExisting()
                ? IntStream.range(0, copies.size()).boxed().toList() : decision.missingCompanions();
        writeCompanions(copies, decision, toWrite, context);
        deleteSources(main, true, copies, toWrite, decision);
        return WriteResult.written(mainTarget);
    }

    /** Writes these companions after the main file; a member that fails stops the group. */
    private void writeCompanions(List<Companion> copies, ConflictResolver.GroupDecision decision,
                                          List<Integer> indices, WriteContext context) {
        for (int i : indices) {
            WorkItem item = copies.get(i).item();
            Path target = decision.companionTargets().get(i);
            try {
                SafeFileWriter.Written written = writer.write(item, target, decision.replaceExisting(), context.runId(),
                        percent -> { }, context::directoryCreated); // the progress is the main file's
                item.getMetadatas().raw().put(WorkItemMetadata.SHA256, written.sha256());
            } catch (IOException e) {
                throw CopybotException.ofResource(e, "write.error.io-detail", target, FileWriteAction.detail(e));
            }
        }
    }

    /**
     * deleteSource, once the whole group is written, all or nothing: only when every member was written and verified,
     * or is already at the destination and compared with fullHash (as a single item skipped as identical); then the
     * main item, then the companions. Otherwise every source stays, so the group stays whole for the next run.
     */
    private void deleteSources(WorkItem main, boolean mainWritten, List<Companion> copies, List<Integer> written,
                               ConflictResolver.GroupDecision decision) {
        boolean provenIdentical = decision.identical() && settings.compare() == Compare.FULL_HASH;
        boolean allQualify = (mainWritten || provenIdentical)
                && (provenIdentical || written.size() == copies.size());
        if (!allQualify) {
            return;
        }
        FileWriteAction.deleteSource(settings, main, decision.mainTarget());
        for (int i = 0; i < copies.size(); i++) {
            FileWriteAction.deleteSource(settings, copies.get(i).item(), decision.companionTargets().get(i));
        }
    }

    /**
     * What the write would find for the group (spec conflict-check §3): the most severe check over the main item and
     * its COPY companions, naming that member; with compareContent, the outcome is the group's, as the write decides.
     */
    TargetCheck check(WorkItem main, List<Companion> copies, Path target, boolean compareContent) throws IOException {
        String mainName = target.getFileName().toString();
        TargetCheck worst = null;
        boolean different = false;
        for (int i = -1; i < copies.size(); i++) {
            WorkItem item = i < 0 ? main : copies.get(i).item();
            Path path = i < 0 ? target : target.resolveSibling(copies.get(i).targetName(mainName));
            BasicFileAttributes existing;
            try {
                existing = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException e) {
                worst = TargetCheck.mostSevere(worst, TargetCheck.free());
                continue;
            }
            if (ConflictResolver.isSource(item, path)) {
                return new TargetCheck(Kind.IDENTICAL, path, ResourcesEngine.getString("write.check.same-file"), true);
            }
            if (!compareContent) {
                worst = TargetCheck.mostSevere(worst, FileWriteAction.quickCheck(settings, item, path, existing));
                continue;
            }
            // as ConflictResolver: a directory is never identical, and is never read
            boolean identical = !existing.isDirectory() && FileComparison.identical(item, path, settings.compare());
            different |= !identical;
            worst = TargetCheck.mostSevere(worst, new TargetCheck(identical ? Kind.IDENTICAL : Kind.DIFFERENT, path, null));
        }
        if (!compareContent || !worst.exists()) {
            return worst;
        }
        Policy policy = different ? settings.ifDifferent() : settings.ifIdentical();
        String outcome = FileWriteAction.policyText(policy);
        // an identical group whose main target is free has its main file written
        boolean skipped = policy == Policy.SKIP && (different || Files.exists(target, LinkOption.NOFOLLOW_LINKS));
        if (policy == Policy.RENAME) {
            try {
                ConflictResolver.GroupDecision decision = conflicts.resolveGroup(main, copies, target); // reads only
                skipped = decision.isSkip(); // a renamed group identical to the items
                outcome = decision.isSkip() ? decision.skipReason()
                        : ResourcesEngine.getString("write.check.renamed", decision.mainTarget().getFileName());
            } catch (CopybotException e) {
                outcome = e.getMessage(); // a later candidate with the policy "error"
            }
        }
        return new TargetCheck(worst.kind(), worst.existing(), ResourcesEngine.getString(
                different ? "write.check.different" : "write.check.identical", worst.existing(), outcome), skipped);
    }
}
