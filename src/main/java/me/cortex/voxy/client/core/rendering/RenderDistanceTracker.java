package me.cortex.voxy.client.core.rendering;

import me.cortex.voxy.client.core.util.RingTracker;
import me.cortex.voxy.common.world.WorldEngine;

import java.util.function.Consumer;

public class RenderDistanceTracker {
    private static final int CHECK_DISTANCE_BLOCKS = 128;
    private final Consumer<long[]> addTopLevelNode;
    private final Consumer<long[]> removeTopLevelNode;
    private final int processRate;
    private final int minSec;
    private final int maxSec;
    private RingTracker tracker;
    private int renderDistance;
    private double posX;
    private double posZ;
    public RenderDistanceTracker(int rate, int minSec, int maxSec, Consumer<long[]> addTopLevelNode, Consumer<long[]> removeTopLevelNode) {
        this.addTopLevelNode = addTopLevelNode;
        this.removeTopLevelNode = removeTopLevelNode;
        this.renderDistance = 2;
        this.tracker = new RingTracker(this.renderDistance, 0, 0, true);
        this.processRate = rate;
        this.minSec = minSec;
        this.maxSec = maxSec;
    }

    public void setRenderDistance(int renderDistance) {
        if (renderDistance == this.renderDistance) {
            return;
        }
        this.renderDistance = renderDistance;
        this.tracker.unload();//Mark all as unload
        this.tracker = new RingTracker(this.tracker, renderDistance, ((int)this.posX)>>9, ((int)this.posZ)>>9, true);//Steal from previous tracker
    }

    public boolean setCenterAndProcess(double x, double z) {
        return this.setCenterAndProcess(x, z, Long.MAX_VALUE);
    }

    /**
     * CPU wall time (ns) of the last setCenterAndProcess call's two phases
     * (ring recenter vs. add/remove processing), for the spike log.
     */
    public static long SPIKE_MOVE_NS = 0;
    public static long SPIKE_PROCESS_NS = 0;

    public boolean setCenterAndProcess(double x, double z, long budgetNanos) {
        long t0 = System.nanoTime();
        double dx = this.posX-x;
        double dz = this.posZ-z;
        if (CHECK_DISTANCE_BLOCKS*CHECK_DISTANCE_BLOCKS<dx*dx+dz*dz) {
            this.posX = x;
            this.posZ = z;
            this.tracker.moveCenter(((int)x)>>9, ((int)z)>>9);
        }
        SPIKE_MOVE_NS = System.nanoTime() - t0;

        // Time-boxed only: the per-column callbacks are cheap and even now
        // (the RingTracker zero-entry sweep is fixed at the write site), so
        // the old 40-columns-per-frame count cap only slowed the initial
        // ring fill at world join (~3200 columns = 80+ frames of trickle
        // before the LOD tree even knew its top-level nodes). The time
        // budget alone protects frame time; the ring tracker resumes where
        // it left off next frame if it runs out.
        long start = System.nanoTime();
        boolean more = true;
        while (more) {
            more = this.tracker.process(16, this::add, this::rem) != 0;
            if (System.nanoTime() - start > budgetNanos) {
                break;
            }
        }
        SPIKE_PROCESS_NS = System.nanoTime() - start;
        return more;
    }

    private void add(int x, int z) {
        long[] sections = new long[this.maxSec - this.minSec + 1];
        int idx = 0;
        for (int y = this.minSec; y <= this.maxSec; y++) {
            sections[idx++] = WorldEngine.getWorldSectionId(4, x, y, z);
        }
        this.addTopLevelNode.accept(sections);
    }

    private void rem(int x, int z) {
        long[] sections = new long[this.maxSec - this.minSec + 1];
        int idx = 0;
        for (int y = this.minSec; y <= this.maxSec; y++) {
            sections[idx++] = WorldEngine.getWorldSectionId(4, x, y, z);
        }
        this.removeTopLevelNode.accept(sections);
    }
}
