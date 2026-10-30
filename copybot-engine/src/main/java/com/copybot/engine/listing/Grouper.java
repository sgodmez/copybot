package com.copybot.engine.listing;

import com.copybot.engine.pipeline.GroupingConfig;
import com.copybot.engine.pipeline.GroupingRuleConfig;
import com.copybot.plugin.api.action.Companion;
import com.copybot.plugin.api.action.CompanionLink;
import com.copybot.plugin.api.action.ListingSink;
import com.copybot.plugin.api.action.WorkItem;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Attaches companion files to their main file (spec grouping/archives, "Regroupements"). The items are held per
 * folder until the folder is done, then decided and emitted: the mains with their companions, the others as they are.
 * <p>
 * A {@code folder} rule sub-folder ({@code <base>_files}) is held until its parent folder is done, where its
 * {@code <base>.html} is decided. A folder is only known to be inside the listing once it has received its own
 * {@code folderDone}: until then a folder with such an ancestor is kept tentatively (the ancestor may lie above the
 * root of the source), and {@link #finish()} decides what stays tentative as ordinary folders. Called by one
 * listing thread: no lock.
 */
public final class Grouper implements ListingSink {

    /** A listed item while it is decided. */
    private static final class Entry {
        final WorkItem item;
        final String name;
        final String lower;
        final int pos;
        boolean attached;
        final List<Companion> companions = new ArrayList<>();

        Entry(WorkItem item, int pos) {
            this.item = item;
            this.name = item.getNameDisplay();
            this.lower = name.toLowerCase(Locale.ROOT);
            this.pos = pos;
        }

        /** Free to become a companion: neither attached nor a main with companions. */
        boolean isFree() {
            return !attached && companions.isEmpty();
        }

        int dot() {
            return lower.lastIndexOf('.');
        }

        String ext() {
            return lower.substring(dot() + 1);
        }

        String base() {
            return lower.substring(0, dot());
        }
    }

    /** The held {@code folder} sub-folders of the folder being decided. */
    private static final class Held {
        /** Root path of a held subtree -> the folders (root included) with items below it. */
        final Map<String, List<String>> keysByRoot = new LinkedHashMap<>();
        final Map<String, List<WorkItem>> items = new HashMap<>();
        final Map<String, String> rootOfKey = new HashMap<>();
        final Set<String> claimed = new HashSet<>();
    }

    private final List<GroupingRule> rules = new ArrayList<>();
    private final ListingSink downstream;
    private final Map<String, List<WorkItem>> buffers = new LinkedHashMap<>();
    /** The {@code *_files} folders that received their folderDone: proven to be inside the listing. */
    private final Set<String> confirmed = new HashSet<>();

    /** The rules as written in the pipeline (presets included, expanded here). */
    public Grouper(List<GroupingRuleConfig> ruleConfigs, ListingSink downstream) {
        for (GroupingRuleConfig config : GroupingPresets.expand(new GroupingConfig(ruleConfigs))) {
            rules.add(new GroupingRule(config));
        }
        this.downstream = downstream;
    }

    @Override
    public void item(WorkItem item) {
        if (item.getNameDisplay() == null || item.getListedFolder() == null) {
            downstream.item(item); // no name or no folder to group by: passed as it is
            return;
        }
        buffers.computeIfAbsent(item.getListedFolder(), k -> new ArrayList<>()).add(item);
    }

    @Override
    public void folderDone(String folder) {
        if (isHeldName(name(folder))) {
            confirmed.add(folder); // waits for the end of its parent
        } else if (!hasHeldAncestor(folder)) {
            decide(folder);
        } // else tentative: an ancestor may be a folder of a rule, once it is done
    }

    /** Decides everything still buffered (a source without signal, the end of the listing), outer folders last. */
    public void finish() {
        Set<String> targets = new LinkedHashSet<>();
        for (String key : buffers.keySet()) {
            String root = confirmedRoot(key);
            String parent = root == null ? null : parent(root);
            targets.add(parent != null && buffers.containsKey(parent) ? parent : key);
        }
        List<String> ordered = new ArrayList<>(targets);
        ordered.sort(Comparator.comparingInt(Grouper::depth).reversed());
        for (String target : ordered) {
            if (buffers.containsKey(target)) {
                decide(target);
            }
        }
        while (!buffers.isEmpty()) {
            decide(buffers.keySet().iterator().next());
        }
        confirmed.clear();
    }

    private void decide(String folder) {
        List<Entry> own = entries(buffers.remove(folder));
        Held held = takeHeld(folder);
        applyRules(own, held);
        own.forEach(this::emit);
        List<String> keys = new ArrayList<>();
        held.keysByRoot.values().forEach(keys::addAll);
        keys.sort(Comparator.comparingInt(Grouper::depth).reversed());
        for (String key : keys) {
            if (!held.claimed.contains(held.rootOfKey.get(key))) {
                // nobody claimed this subtree: its folders are ordinary folders
                List<Entry> ordinary = entries(held.items.get(key));
                applyRules(ordinary, new Held());
                ordinary.forEach(this::emit);
            }
            downstream.folderDone(key);
        }
        confirmed.removeAll(held.keysByRoot.keySet());
        downstream.folderDone(folder);
    }

    /** Takes out of the buffers the confirmed {@code folder} subtrees whose parent is {@code folder}. */
    private Held takeHeld(String folder) {
        Held held = new Held();
        if (confirmed.isEmpty()) {
            return held;
        }
        for (String key : List.copyOf(buffers.keySet())) {
            String root = confirmedRoot(key);
            if (root != null && folder.equals(parent(root))) {
                held.keysByRoot.computeIfAbsent(root, k -> new ArrayList<>()).add(key);
                held.items.put(key, buffers.remove(key));
                held.rootOfKey.put(key, root);
            }
        }
        return held;
    }

    private static List<Entry> entries(List<WorkItem> items) {
        List<Entry> entries = new ArrayList<>();
        if (items != null) {
            for (WorkItem item : items) {
                entries.add(new Entry(item, entries.size()));
            }
        }
        return entries;
    }

    private void applyRules(List<Entry> own, Held held) {
        for (GroupingRule rule : rules) {
            switch (rule.type) {
                case SAME_BASE -> applySameBase(rule, own);
                case DOUBLE_EXTENSION -> applyDoubleExtension(rule, own);
                case REGEX -> applyRegex(rule, own);
                case FOLDER -> applyFolder(rule, own, held);
            }
        }
    }

    private void emit(Entry entry) {
        if (entry.attached) {
            return;
        }
        downstream.item(entry.companions.isEmpty() ? entry.item : entry.item.withCompanions(entry.companions));
    }

    /**
     * Attaches the companion, unless its target would be the main file's own ({@code IMG_1.JPG} next to
     * {@code IMG_1.jpg}, one file on a destination that ignores case): it then stays an ordinary item.
     */
    private static void attach(Entry main, Entry companion, GroupingRule.Role role, CompanionLink link, String suffix) {
        if (suffix.equalsIgnoreCase(ownSuffix(main, link))) {
            return;
        }
        companion.attached = true;
        if (role.companionRole != null) {
            main.companions.add(new Companion(companion.item, role.companionRole, link, suffix));
        }
    }

    /** The suffix that gives back the main file's own target name: empty by name, its extension by base. */
    private static String ownSuffix(Entry main, CompanionLink link) {
        int dot = main.name.lastIndexOf('.');
        return link == CompanionLink.NAME || dot <= 0 ? "" : main.name.substring(dot);
    }

    private void applySameBase(GroupingRule rule, List<Entry> entries) {
        Map<String, List<Entry>> byBase = new HashMap<>();
        Map<String, List<Entry>> byExt = new HashMap<>();
        for (Entry entry : entries) {
            if (entry.dot() > 0) {
                byBase.computeIfAbsent(entry.base(), k -> new ArrayList<>()).add(entry);
                byExt.computeIfAbsent(entry.ext(), k -> new ArrayList<>()).add(entry);
            }
        }
        // mains in priority order, then in listing order
        for (String ext : new LinkedHashSet<>(rule.mainExtensions)) {
            for (Entry main : byExt.getOrDefault(ext, List.of())) {
                if (main.attached) {
                    continue;
                }
                for (Entry candidate : byBase.get(main.base())) {
                    if (candidate == main || !candidate.isFree()) {
                        continue;
                    }
                    GroupingRule.Role role = rule.companionRoles.get(candidate.ext());
                    if (role != null) {
                        attach(main, candidate, role, CompanionLink.BASE, candidate.name.substring(main.base().length()));
                    }
                }
            }
        }
    }

    private void applyDoubleExtension(GroupingRule rule, List<Entry> entries) {
        Map<String, List<Entry>> byName = new HashMap<>();
        for (Entry entry : entries) {
            byName.computeIfAbsent(entry.lower, k -> new ArrayList<>()).add(entry);
        }
        for (Entry candidate : entries) {
            if (!candidate.isFree() || candidate.dot() <= 0) {
                continue;
            }
            GroupingRule.Role role = rule.companionRoles.get(candidate.ext());
            if (role == null) {
                continue;
            }
            String mainName = candidate.base();
            for (Entry main : byName.getOrDefault(mainName, List.of())) {
                if (main != candidate && !main.attached) {
                    attach(main, candidate, role, CompanionLink.NAME, candidate.name.substring(mainName.length()));
                    break;
                }
            }
        }
    }

    private void applyRegex(GroupingRule rule, List<Entry> entries) {
        // a companion expression starting with \1 only matches names that start with the captured text: look those up
        TreeMap<String, List<Entry>> byName = null;
        if (rule.companionTemplate.startsWith("\\1")) {
            byName = new TreeMap<>();
            for (Entry entry : entries) {
                byName.computeIfAbsent(entry.lower, k -> new ArrayList<>()).add(entry);
            }
        }
        for (Entry main : entries) {
            if (main.attached) {
                continue;
            }
            Matcher captured = rule.mainRegex.matcher(main.name);
            if (!captured.matches()) {
                continue;
            }
            Pattern companionPattern = rule.companionPattern(captured);
            int prefix = captured.groupCount() >= 1 ? captured.group(1).length() : captured.group().length();
            List<Entry> candidates = entries;
            if (byName != null && captured.groupCount() >= 1) {
                String start = captured.group(1).toLowerCase(Locale.ROOT);
                candidates = new ArrayList<>();
                for (List<Entry> same : byName.subMap(start, true, start + Character.MAX_VALUE, true).values()) {
                    candidates.addAll(same);
                }
                candidates.sort(Comparator.comparingInt(e -> e.pos));
            }
            for (Entry candidate : candidates) {
                if (candidate != main && candidate.isFree() && companionPattern.matcher(candidate.name).matches()) {
                    attach(main, candidate, rule.role, rule.link,
                            candidate.name.substring(Math.min(prefix, candidate.name.length())));
                }
            }
        }
    }

    private void applyFolder(GroupingRule rule, List<Entry> entries, Held held) {
        if (rule.folderName == null) {
            return;
        }
        for (Entry main : entries) {
            if (main.attached || main.dot() <= 0 || !rule.mainExtensions.contains(main.ext())) {
                continue;
            }
            for (String root : held.keysByRoot.keySet()) {
                String rootBase = held.claimed.contains(root) ? null : rule.folderBase(name(root));
                if (rootBase == null || !rootBase.toLowerCase(Locale.ROOT).equals(main.base())) {
                    continue;
                }
                held.claimed.add(root);
                for (String key : held.keysByRoot.get(root)) {
                    for (WorkItem inside : held.items.get(key)) {
                        if (rule.role.companionRole != null) {
                            main.companions.add(new Companion(inside, rule.role.companionRole, CompanionLink.BASE,
                                    rule.folderSuffix + "/" + relative(root, key, inside.getNameDisplay())));
                        }
                    }
                }
            }
        }
    }

    /** Whether the folder name is the one of a {@code folder} rule for some base. */
    private boolean isHeldName(String folderName) {
        for (GroupingRule rule : rules) {
            if (rule.folderBase(folderName) != null) {
                return true;
            }
        }
        return false;
    }

    private boolean hasHeldAncestor(String folder) {
        if (rules.stream().noneMatch(rule -> rule.folderName != null)) {
            return false;
        }
        for (String current = parent(folder); current != null; current = parent(current)) {
            if (isHeldName(name(current))) {
                return true;
            }
        }
        return false;
    }

    /** The outermost confirmed {@code folder} root among the folder and its ancestors, null: none. */
    private String confirmedRoot(String folder) {
        if (confirmed.isEmpty()) {
            return null;
        }
        String root = null;
        for (String current = folder; current != null; current = parent(current)) {
            if (confirmed.contains(current)) {
                root = current;
            }
        }
        return root;
    }

    private static String relative(String root, String key, String name) {
        String below = key.substring(root.length()).replace('\\', '/');
        while (below.startsWith("/")) {
            below = below.substring(1);
        }
        return below.isEmpty() ? name : below + "/" + name;
    }

    private static int depth(String folder) {
        int depth = 0;
        for (int i = 0; i < folder.length(); i++) {
            if (folder.charAt(i) == '/' || folder.charAt(i) == '\\') {
                depth++;
            }
        }
        return depth;
    }

    private static int lastSeparator(String folder) {
        return Math.max(folder.lastIndexOf('/'), folder.lastIndexOf('\\'));
    }

    /**
     * The folder containing this one: cut at the last separator. The root of an archive ("C:\d\x.zip!") thus has the
     * folder of the archive as parent. Null at the top.
     */
    static String parent(String folder) {
        int cut = lastSeparator(folder);
        if (cut < 0) {
            return null;
        }
        if (cut == 0) {
            return folder.length() > 1 ? "/" : null;
        }
        return folder.substring(0, cut);
    }

    private static String name(String folder) {
        return folder.substring(lastSeparator(folder) + 1);
    }
}
