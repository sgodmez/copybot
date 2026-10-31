package com.copybot.engine;

import com.copybot.engine.pipeline.PipelineStep;
import com.copybot.engine.pipeline.SecondaryOutputConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * Where each part of the item steps sits in their flat list (spec secondary-outputs §2): the analyses
 * {@code [0, barrier)}, then each secondary output {@code [from, to)} (its process steps, then its out step at
 * {@code to - 1}), then the main branch {@code [mainFrom, steps.size())}. Every step keeps a unique index: the
 * resources {@code step:<i>}, the lifecycle calls and the progress of an item use it.
 *
 * @param steps    the item steps, flat, in that order
 * @param barrier  the number of analyses: the steps before the preparation barrier
 * @param branches the secondary outputs, in order; empty without any
 * @param mainFrom the first step of the main branch (its process steps, then its out step if any)
 */
record ItemLayout(List<PipelineStep<?>> steps, int barrier, List<BranchRange> branches, int mainFrom) {

    /** A secondary output in the flat list: its steps are {@code [from, to)}, its out step is the last of them. */
    record BranchRange(String name, SecondaryOutputConfig.OnError onError, int from, int to) {

        int outIndex() {
            return to - 1;
        }
    }

    ItemLayout {
        steps = List.copyOf(steps);
        branches = List.copyOf(branches);
    }

    /** Item steps without secondary output: the main branch starts at the barrier. */
    static ItemLayout withoutBranches(List<PipelineStep<?>> steps, int barrier) {
        return new ItemLayout(steps, barrier, List.of(), barrier);
    }

    /**
     * The secondary outputs laid between the analyses and the main branch.
     *
     * @param steps   the item steps without the branches: analyses, main process steps, main out step
     * @param barrier the number of analyses at the head of {@code steps}
     */
    static ItemLayout of(List<PipelineStep<?>> steps, int barrier, List<StepResolver.Branch> branches) {
        List<PipelineStep<?>> flat = new ArrayList<>(steps.subList(0, barrier));
        List<BranchRange> ranges = new ArrayList<>();
        for (StepResolver.Branch branch : branches) {
            int from = flat.size();
            flat.addAll(branch.steps());
            flat.add(branch.out());
            ranges.add(new BranchRange(branch.name(), branch.onError(), from, flat.size()));
        }
        int mainFrom = flat.size();
        flat.addAll(steps.subList(barrier, steps.size()));
        return new ItemLayout(flat, barrier, ranges, mainFrom);
    }

    /** The secondary output whose first step is {@code index}, null when none starts there. */
    BranchRange branchAt(int index) {
        for (BranchRange branch : branches) {
            if (branch.from() == index) {
                return branch;
            }
        }
        return null;
    }

    /** The steps of the main branch: its process steps, then its out step if any. */
    List<PipelineStep<?>> mainSteps() {
        return steps.subList(mainFrom, steps.size());
    }
}
