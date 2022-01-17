/*
 * The MIT License
 * Copyright 2021 Qingtian Wang.
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package chunk4j;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import static com.github.benmanes.caffeine.cache.RemovalCause.EXPIRED;
import static com.github.benmanes.caffeine.cache.RemovalCause.SIZE;
import com.github.benmanes.caffeine.cache.RemovalListener;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author Qingtian Wang
 */
public final class ChunkStitcher implements Stitcher {

    private static final Logger LOG = Logger.getLogger(ChunkStitcher.class.getName());
    private static final long DEFAULT_MAX_CHUNK_GROUP_COUNT = Long.MAX_VALUE;
    private static final long DEFAULT_MAX_STITCH_TIME_MILLIS = Long.MAX_VALUE;

    private static byte[] stitchAll(Set<Chunk> group) {
        LOG.log(Level.FINER, () -> "Start stitching all chunks in group: " + group);
        assert ofSameId(group);
        byte[] groupBytes = new byte[getTotalByteSize(group)];
        List<Chunk> orderedGroup = new ArrayList<>(group);
        Collections.sort(orderedGroup, (chunk1, chunk2) -> chunk1.getIndex() - chunk2.getIndex());
        int groupBytesPosition = 0;
        for (Chunk chunk : orderedGroup) {
            final int chunkBytesLength = chunk.getBytes().length;
            System.arraycopy(chunk.getBytes(), 0, groupBytes, groupBytesPosition, chunkBytesLength);
            groupBytesPosition += chunkBytesLength;
        }
        LOG.log(Level.FINER, () -> "End stitching all chunks in group: " + group);
        return groupBytes;
    }

    private static int getTotalByteSize(Set<Chunk> group) {
        return group.stream()
                .mapToInt(chunk -> chunk.getBytes().length)
                .sum();
    }

    private static boolean ofSameId(Set<Chunk> group) {
        return group.stream()
                .map(chunk -> chunk.getGroupId())
                .distinct()
                .count() == 1;
    }

    private final Cache<UUID, Set<Chunk>> chunkGroups;

    private ChunkStitcher(Builder builder) {
        final long maxStitchTimeMillis = builder.maxStitchTimeMillis == null ? DEFAULT_MAX_STITCH_TIME_MILLIS
                : builder.maxStitchTimeMillis;
        final long maxGroups = builder.maxGroups == null ? DEFAULT_MAX_CHUNK_GROUP_COUNT : builder.maxGroups;
        this.chunkGroups = Caffeine.newBuilder()
                .expireAfterWrite(maxStitchTimeMillis, TimeUnit.MILLISECONDS)
                .maximumSize(maxGroups)
                .evictionListener(new InvoluntaryEvictionLogger(maxStitchTimeMillis, maxGroups))
                .build();
    }

    @Override
    public Optional<byte[]> stitch(Chunk chunk) {
        LOG.log(Level.FINER, () -> "Received chunk: " + chunk);
        final UUID groupId = chunk.getGroupId();
        synchronized (groupId) {
            final Set<Chunk> chunks = chunkGroups.get(groupId, key -> new HashSet<>());
            if (!chunks.add(chunk)) {
                LOG.log(Level.WARNING, "Ignoring duplicate chunk: {0}", chunk);
                return Optional.empty();
            }
            final int receivedTotal = chunks.size();
            final int expectedTotal = chunk.getGroupSize();
            if (receivedTotal != expectedTotal) {
                LOG.log(Level.FINEST, () -> "Received: " + receivedTotal + " chunks, awaiting expected: "
                        + expectedTotal + " chunks to start restoring");
                return Optional.empty();
            }
            LOG.log(Level.FINE, () -> "Received all: " + expectedTotal
                    + " expected chunks, starting to restore orginal data...");
            chunkGroups.invalidate(groupId);
            return Optional.of(stitchAll(chunks));
        }
    }

    public static class Builder {

        private Long maxStitchTimeMillis;
        private Long maxGroups;

        public Builder maxStitchTimeMillis(long maxStitchTimeMillis) {
            this.maxStitchTimeMillis = maxStitchTimeMillis;
            return this;
        }

        public Builder maxGroups(long maxGroups) {
            this.maxGroups = maxGroups;
            return this;
        }

        public ChunkStitcher build() {
            return new ChunkStitcher(this);
        }

    }

    private static final class InvoluntaryEvictionLogger implements RemovalListener<UUID, Set<Chunk>> {

        private final long maxStitchTimeMillis;
        private final long maxGroups;

        public InvoluntaryEvictionLogger(long maxStitchTimeMillis, long maxGroups) {
            this.maxStitchTimeMillis = maxStitchTimeMillis;
            this.maxGroups = maxGroups;
        }

        @Override
        public void onRemoval(UUID groupId, Set<Chunk> chunks, RemovalCause cause) {
            Objects.requireNonNull(cause);
            switch (cause) {
                case EXPIRED:
                    LOG.log(Level.SEVERE,
                            "Chunk group {0} took too long to stitch and expired after {1} milliseconds, expected {2} chunks but only received {3} when expired",
                            new Object[] { groupId, maxStitchTimeMillis, chunks.stream()
                                    .findFirst()
                                    .get()
                                    .getGroupSize(), chunks.size() });
                    break;
                case SIZE:
                    LOG.log(Level.SEVERE, "Chunk group {0} was removed due to exceeding max group count {1}",
                            new Object[] { groupId, maxGroups });
                    break;
                case EXPLICIT:
                    break;
                case REPLACED:
                    break;
                case COLLECTED:
                    break;
                default:
                    throw new AssertionError("Unexpected eviction cause: " + cause.name());
            }
        }
    }

}
