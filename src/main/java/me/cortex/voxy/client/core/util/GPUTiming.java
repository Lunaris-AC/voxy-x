package me.cortex.voxy.client.core.util;

import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue;
import it.unimi.dsi.fastutil.objects.ObjectArrayFIFOQueue;
import me.cortex.voxy.common.util.TrackedObject;

import java.lang.reflect.Array;
import java.util.Arrays;

import static org.lwjgl.opengl.ARBTimerQuery.GL_TIMESTAMP;
import static org.lwjgl.opengl.ARBTimerQuery.glQueryCounter;
import static org.lwjgl.opengl.GL15.glDeleteQueries;
import static org.lwjgl.opengl.GL15.glGenQueries;
import static org.lwjgl.opengl.GL15C.*;
import static org.lwjgl.opengl.GL33.glGetQueryObjecti64;

public class GPUTiming {
    public static GPUTiming INSTANCE = new GPUTiming();

    private final GlTimestampQuerySet<String> timingSet = new GlTimestampQuerySet(String.class);

    // Rolling per-stage GPU times keyed by "viewTag·label" instead of the
    // old positional arrays: with Immersive Portals, renderOpaque runs once
    // for the main view and once per portal view within the same frame, and
    // positional storage made the two views' stage sets overwrite each
    // other every tick. Keyed storage lets both views' timings coexist so
    // the F3 readout can attribute cost per view type.
    private final java.util.Map<String, Float> rollingByLabel = new java.util.LinkedHashMap<>();

    // "M" while the main view renders, "P" during a portal view (set by
    // VoxyRenderSystem.renderOpaque from the imm_ptl portal-view token).
    private String viewTag = "M";

    private boolean enabled = false;

    public void setViewTag(String tag) {
        this.viewTag = tag;
    }

    public void marker() {
        this.marker(null);
    }

    public void marker(String lable) {
        if (this.enabled) {
            this.timingSet.capture(this.viewTag + "·" + (lable == null ? "-" : lable));
        }
    }

    public void setEnabled(boolean enable) {
        if (this.enabled != enable) {
            this.enabled = enable;
        }
    }

    public java.util.List<String> getDebugLines() {
        if (!this.enabled || this.rollingByLabel.isEmpty()) {
            return java.util.List.of();
        }
        StringBuilder main = new StringBuilder("GpuTime main: [");
        StringBuilder portal = new StringBuilder("GpuTime portal: [");
        boolean anyMain = false, anyPortal = false;
        float totalMain = 0, totalPortal = 0;
        for (var e : this.rollingByLabel.entrySet()) {
            String key = e.getKey();
            boolean isPortal = key.startsWith("P·");
            StringBuilder target = isPortal ? portal : main;
            if (isPortal ? anyPortal : anyMain) {
                target.append(", ");
            }
            target.append(key.substring(2)).append(':').append(String.format("%.2f", e.getValue()));
            if (isPortal) { anyPortal = true; totalPortal += e.getValue(); }
            else { anyMain = true; totalMain += e.getValue(); }
        }
        main.append(String.format(", Σ:%.2f]", totalMain));
        portal.append(String.format(", Σ:%.2f]", totalPortal));
        java.util.List<String> lines = new java.util.ArrayList<>(2);
        if (anyMain) lines.add(main.toString());
        if (anyPortal) lines.add(portal.toString());
        return lines;
    }

    public String getDebug() {
        return String.join(" | ", this.getDebugLines());
    }

    public void tick() {
        this.timingSet.download((meta,data)->{
            // Sum this set's deltas per label first (unlabeled separator
            // markers all share the "-" key), then fold into the rolling
            // averages with the same max(decay, spike) behavior as before.
            java.util.Map<String, Float> setSums = new java.util.LinkedHashMap<>();
            long current = data[0];
            for (int i = 1; i < meta.length; i++) {
                long next = data[i];
                float time = (float) (((double)(next - current))/1_000_000);
                setSums.merge(meta[i-1], time, Float::sum);
                current = next;
            }
            for (var e : setSums.entrySet()) {
                this.rollingByLabel.merge(
                    e.getKey(), e.getValue(),
                    (old, cur) -> Math.max(old*0.99f + cur*0.01f, cur)
                );
            }
        });
        this.timingSet.tick();
    }

    public void free() {
        this.timingSet.free();
    }

    public interface TimingDataConsumer <T> {
        void accept(T metadata, long[] timings);
    }
    private static final class GlTimestampQuerySet <T> extends TrackedObject {

        private record InflightRequest<T>(int[] queries, T[] meta, TimingDataConsumer<T[]> callback) {
            private boolean callbackIfReady(IntArrayFIFOQueue queryPool) {
                boolean ready = glGetQueryObjecti(this.queries[this.queries.length-1], GL_QUERY_RESULT_AVAILABLE) == GL_TRUE;
                if (!ready) {
                    return false;
                }
                long[] results = new long[this.queries.length];
                for (int i = 0; i < this.queries.length; i++) {
                    results[i] = glGetQueryObjecti64(this.queries[i], GL_QUERY_RESULT);
                    queryPool.enqueue(this.queries[i]);
                }
                this.callback.accept(this.meta, results);
                return true;
            }
        }
        private final IntArrayFIFOQueue POOL = new IntArrayFIFOQueue();
        private final ObjectArrayFIFOQueue<InflightRequest<T>> INFLIGHT = new ObjectArrayFIFOQueue();

        private final int[] queries = new int[64];
        private final T[] metadata;
        private int index;


        private GlTimestampQuerySet(Class<T> metaClass) {
            this.metadata = (T[]) Array.newInstance(metaClass, 64);
        }

        public void capture(T metadata) {
            if (this.index > this.metadata.length) {
                throw new IllegalStateException();
            }
            int slot = this.index++;
            this.metadata[slot] = metadata;
            int query = this.getQuery();
            glQueryCounter(query, GL_TIMESTAMP);
            this.queries[slot] = query;

        }

        public void download(TimingDataConsumer<T[]> consumer) {
            if (this.index != 0) {
                var queries = Arrays.copyOf(this.queries, this.index);
                var metadata = Arrays.copyOf(this.metadata, this.index);
                Arrays.fill(this.metadata, null);
                this.index = 0;
                this.INFLIGHT.enqueue(new InflightRequest(queries, metadata, consumer));
            }
        }

        public void tick() {
            while (!INFLIGHT.isEmpty()) {
                if (INFLIGHT.first().callbackIfReady(POOL)) {
                    INFLIGHT.dequeue();
                } else {
                    break;
                }
            }
        }

        private int getQuery() {
            if (POOL.isEmpty()) {
                return glGenQueries();
            } else {
                return POOL.dequeueInt();
            }
        }

        @Override
        public void free() {
            super.free0();
            while (!POOL.isEmpty()) {
                glDeleteQueries(POOL.dequeueInt());
            }
            while (!INFLIGHT.isEmpty()) {
                glDeleteQueries(INFLIGHT.dequeue().queries);
            }
        }
    }
    /*
    private static final class GlTimestampQuerySet extends TrackedObject {
        private final int query = glGenQueries();
        public final GlBuffer store;
        public final int[] metadata;
        public int index;
        public GlTimestampQuerySet(int maxCount) {
            this.store = new GlBuffer(maxCount*8L);
            this.metadata = new int[maxCount];
        }

        public void capture(int metadata) {
            if (this.index>this.metadata.length) {
                throw new IllegalStateException();
            }
            int slot = this.index++;
            this.metadata[slot] = metadata;
            glQueryCounter(this.query, GL_TIMESTAMP);//This should be gpu side, so should be fast
            glFinish();
            glGetQueryBufferObjectui64v(this.query, this.store.id, GL_QUERY_RESULT_NO_WAIT, slot*8L);
            glMemoryBarrier(-1);
        }

        public void download(TimingDataConsumer consumer) {
            var meta = Arrays.copyOf(this.metadata, this.index);
            this.index = 0;
            //DownloadStream.INSTANCE.download(this.store, buffer->consumer.accept(meta, buffer));
        }

        @Override
        public void free() {
            super.free0();
            glDeleteQueries(this.query);
            this.store.free();
        }
    }*/
}
