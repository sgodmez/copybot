package com.copybot.engine.resume;

import com.copybot.resources.ResourcesEngine;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * "Resume from destination": an item counts as imported when its target directory exists (one
 * directory per day: deleting photos inside it afterwards does not move the resume point back).
 * The destination is assumed filled up to a point, so a dichotomy finds it in ~log2(n) checks.
 */
public final class DestinationProbe {

    public record Candidate(ItemKey key, Path targetDir) {
    }

    /** @param warning message to show the user, null when none */
    public record Result(ResumePoint point, String warning) {
    }

    private DestinationProbe() {
    }

    /** @param ordered candidates sorted by key */
    public static Result probe(List<Candidate> ordered, Predicate<Path> dirExists) {
        if (ordered.isEmpty()) {
            return new Result(ResumePoint.all(), null);
        }
        Path first = ordered.getFirst().targetDir();
        if (ordered.size() >= 2 && ordered.stream().allMatch(c -> Objects.equals(c.targetDir(), first))) {
            // the out pattern has no variable directory: existence of the only directory tells nothing
            return new Result(ResumePoint.all(), ResourcesEngine.getString("resume.warn.single-directory", first));
        }
        int lastExisting = -1;
        int firstMissing = ordered.size();
        while (firstMissing - lastExisting > 1) {
            int mid = (lastExisting + firstMissing) >>> 1;
            if (dirExists.test(ordered.get(mid).targetDir())) {
                lastExisting = mid;
            } else {
                firstMissing = mid;
            }
        }
        return new Result(lastExisting < 0 ? ResumePoint.all() : ResumePoint.after(ordered.get(lastExisting).key()), null);
    }
}
