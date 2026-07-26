package com.copybot.engine.resources;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Central arbiter for named resources.
 * acquireAll is all-or-nothing: a waiter never holds a permit while waiting for another,
 * so multi-resource acquisition cannot deadlock nor waste slots.
 *
 * <p>Wake-ups are event-driven (release / arrival / capacity registration) and waiters are
 * indexed by resource name, so releasing a resource nobody waits for costs nothing and the
 * grant scan stops as soon as no free capacity can serve anyone. Starvation age is evaluated
 * lazily at each grant decision — time passing cannot by itself make a new grant possible,
 * so no periodic polling is needed.
 */
public final class ResourceRegistry {

    static final int MAX_BYPASS = 5;
    static final long MAX_WAIT_MILLIS = 60_000;

    private static final class ResourceCount {
        final int capacity;
        int used;

        ResourceCount(int capacity) {
            this.capacity = capacity;
        }
    }

    private static final class Waiter {
        final Set<String> resources;
        final long since;
        int bypassCount;
        boolean granted;

        Waiter(Set<String> resources, long since) {
            this.resources = resources;
            this.since = since;
        }
    }

    private final ResourceSettings settings;
    private final Map<String, ResourceCount> counts = new HashMap<>();
    private final Deque<Waiter> waiters = new ArrayDeque<>(); // arrival order
    private final Map<String, Set<Waiter>> waitersByResource = new HashMap<>();
    private final Object lock = new Object();

    public ResourceRegistry(ResourceSettings settings) {
        this.settings = settings;
    }

    /**
     * Declares the capacity of a resource whose name is not covered by {@link ResourceSettings}
     * (typically the implicit {@code step:<n>} resource backing {@code maxConcurrency}, see design
     * spec §4.3): without it the resource would fall through to the default capacity of 1 and
     * silently serialize the step.
     *
     * <p>Idempotent and safe to call repeatedly: the capacity is installed when the resource is
     * unknown, refreshed when it is known but idle, and left untouched while permits are held
     * (changing the capacity of an in-flight resource would make {@code used > capacity} possible).
     */
    public void registerCapacity(String name, int capacity) {
        synchronized (lock) {
            String canonical = settings.canonical(name);
            ResourceCount existing = counts.get(canonical);
            if (existing == null || existing.used == 0) {
                // clamp: a capacity below 1 (e.g. a misconfigured maxConcurrency of 0) would make
                // fits() permanently false and hang every acquirer of this resource forever
                counts.put(canonical, new ResourceCount(Math.max(1, capacity)));
                if (waitersByResource.containsKey(canonical)) {
                    grantEligibleWaiters(); // a widened capacity may unblock waiters
                }
            }
        }
    }

    /**
     * Blocks until ALL requested resources are simultaneously available, then takes
     * one permit on each. Interruptible; on interruption nothing stays acquired.
     */
    public void acquireAll(Set<String> names) throws InterruptedException {
        Set<String> canonical = canonicalize(names);
        if (canonical.isEmpty()) {
            return; // nothing to arbitrate; an empty waiter would never be indexed, hence never scanned
        }
        Waiter me = new Waiter(canonical, System.currentTimeMillis());
        synchronized (lock) {
            canonical.forEach(this::countFor); // materialize so the scan sees their free capacity
            waiters.addLast(me);
            index(me);
            grantEligibleWaiters();
            try {
                while (!me.granted) {
                    lock.wait(); // every grant notifies; nothing else can make this waiter eligible
                }
            } catch (InterruptedException e) {
                waiters.remove(me);
                unindex(me);
                if (me.granted) { // granted between the interrupt and the catch: give it back
                    doRelease(canonical);
                    grantEligibleWaiters();
                }
                throw e;
            }
        }
    }

    /** Returns one permit on each named resource and wakes up eligible waiters. */
    public void releaseAll(Set<String> names) {
        synchronized (lock) {
            Set<String> canonical = canonicalize(names);
            doRelease(canonical);
            // a release can only unblock someone waiting on one of the released names
            if (canonical.stream().anyMatch(waitersByResource::containsKey)) {
                grantEligibleWaiters();
            }
        }
    }

    public List<ResourceSnapshot> snapshot() {
        synchronized (lock) {
            return counts.entrySet().stream()
                    .map(e -> new ResourceSnapshot(
                            e.getKey(),
                            e.getValue().capacity,
                            e.getValue().used,
                            waitersByResource.getOrDefault(e.getKey(), Set.of()).size()))
                    .sorted(Comparator.comparing(ResourceSnapshot::name))
                    .toList();
        }
    }

    // ---- all methods below are always called while holding `lock` ----

    /**
     * First-fit scan of the arrival queue. Anti-starvation is <em>scoped to the resources the
     * starved waiter actually needs</em>: those names are reserved (nobody behind it may take them
     * until it is served), but waiters whose footprint is disjoint from every reservation are still
     * granted. A global stop would make the registry degenerate into strict FIFO — in two-phase mode
     * every item is enqueued at once and shares the same age — and break the core promise that
     * independent resources (e.g. two distinct disks) keep running in parallel.
     *
     * <p>The scan aborts as soon as {@code grantable} — the names that are both free and awaited —
     * is exhausted: no waiter behind that point can fit (all-or-nothing needs every name free), so
     * a queue saturated on one disk costs O(1) per event instead of a full walk.
     */
    private void grantEligibleWaiters() {
        Set<String> grantable = new HashSet<>();
        for (Map.Entry<String, ResourceCount> entry : counts.entrySet()) {
            if (entry.getValue().used < entry.getValue().capacity && waitersByResource.containsKey(entry.getKey())) {
                grantable.add(entry.getKey());
            }
        }
        long now = System.currentTimeMillis();
        List<Waiter> skipped = new ArrayList<>();
        Set<String> reserved = new HashSet<>(); // resources held back for starved waiters
        Iterator<Waiter> it = waiters.iterator();
        while (it.hasNext() && !grantable.isEmpty()) {
            Waiter waiter = it.next();
            if (!Collections.disjoint(waiter.resources, reserved)) {
                continue; // would steal a resource reserved for a starved waiter ahead of it
            }
            if (fits(waiter.resources)) {
                take(waiter.resources);
                waiter.granted = true;
                it.remove();
                unindex(waiter);
                skipped.forEach(s -> s.bypassCount++);
                lock.notifyAll();
                waiter.resources.forEach(n -> {
                    ResourceCount count = countFor(n);
                    if (count.used >= count.capacity) {
                        grantable.remove(n);
                    }
                });
            } else if (waiter.bypassCount >= MAX_BYPASS || now - waiter.since >= MAX_WAIT_MILLIS) {
                reserved.addAll(waiter.resources); // starved: strict mode on ITS resources only
                grantable.removeAll(waiter.resources);
            } else {
                skipped.add(waiter);
            }
        }
    }

    private void index(Waiter waiter) {
        for (String name : waiter.resources) {
            waitersByResource.computeIfAbsent(name, n -> new HashSet<>()).add(waiter);
        }
    }

    private void unindex(Waiter waiter) {
        for (String name : waiter.resources) {
            Set<Waiter> interested = waitersByResource.get(name);
            if (interested != null) {
                interested.remove(waiter);
                if (interested.isEmpty()) {
                    waitersByResource.remove(name);
                }
            }
        }
    }

    private Set<String> canonicalize(Set<String> names) {
        return names.stream().map(settings::canonical).collect(Collectors.toUnmodifiableSet());
    }

    private ResourceCount countFor(String name) {
        return counts.computeIfAbsent(name, n -> new ResourceCount(settings.capacityFor(n)));
    }

    private boolean fits(Set<String> names) {
        return names.stream().allMatch(n -> countFor(n).used < countFor(n).capacity);
    }

    private void take(Set<String> names) {
        names.forEach(n -> countFor(n).used++);
    }

    private void doRelease(Set<String> names) {
        names.forEach(n -> countFor(n).used--);
    }
}
