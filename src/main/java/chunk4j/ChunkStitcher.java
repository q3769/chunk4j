/*
 * MIT License
 *
 * Copyright (c) 2022 Qingtian Wang
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package chunk4j;

import com.github.benmanes.caffeine.cache.*;
import elf4j.Logger;
import lombok.Data;
import lombok.NonNull;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;
import java.time.Duration;
import java.util.*;

/**
 * @author Qingtian Wang
 */
@ThreadSafe
public final class ChunkStitcher implements Stitcher {
    public static final boolean DEFAULT_VERIFY_BEFORE_STITCH = false;
    private static final long DEFAULT_MAX_CHUNK_GROUP_COUNT = Long.MAX_VALUE;
    private static final long DEFAULT_MAX_STITCH_TIME_NANOS = Long.MAX_VALUE;
    private static final Logger log = Logger.instance(ChunkStitcher.class);
    private final Cache<UUID, Set<Chunk>> chunkGroups;
    private final Duration maxStitchTime;
    private final Long maxGroups;
    private final boolean verifyBeforeStitch;

    private ChunkStitcher(Builder builder) {
        maxStitchTime =
                builder.maxStitchTime == null ? Duration.ofNanos(DEFAULT_MAX_STITCH_TIME_NANOS) : builder.maxStitchTime;
        maxGroups = builder.maxGroups == null ? DEFAULT_MAX_CHUNK_GROUP_COUNT : builder.maxGroups;
        verifyBeforeStitch =
                builder.verifyBeforeStitch == null ? DEFAULT_VERIFY_BEFORE_STITCH : builder.verifyBeforeStitch;
        this.chunkGroups = Caffeine.newBuilder()
                .expireAfter(new SinceCreation(maxStitchTime))
                .maximumSize(maxGroups)
                .evictionListener(new InvoluntaryEvictionLogger())
                .build();
    }

    private static int getTotalByteSize(@NonNull Set<Chunk> group) {
        return group.stream().mapToInt(chunk -> chunk.getBytes().length).sum();
    }

    private byte[] stitchAll(@NonNull Set<Chunk> group) {
        verifyStitchability(group);
        byte[] stitchedBytes = new byte[getTotalByteSize(group)];
        List<Chunk> orderedGroup = new ArrayList<>(group);
        orderedGroup.sort(Comparator.comparingInt(Chunk::getIndex));
        int chunkStartPosition = 0;
        for (Chunk chunk : orderedGroup) {
            byte[] chunkBytes = chunk.getBytes();
            System.arraycopy(chunkBytes, 0, stitchedBytes, chunkStartPosition, chunkBytes.length);
            chunkStartPosition += chunkBytes.length;
        }
        log.atDebug().log("stitched all [{}] chunks in group [{}]", group.size(), orderedGroup.get(0).getGroupId());
        return stitchedBytes;
    }

    private void verifyStitchability(@NonNull Set<Chunk> group) {
        if (!this.verifyBeforeStitch) {
            return;
        }
        int groupSize = group.size();
        UUID groupId = group.stream().findAny().orElseThrow(NoSuchElementException::new).getGroupId();
        if (group.stream()
                .anyMatch(chunk -> !chunk.getGroupId().equals(groupId) || chunk.getGroupSize() != groupSize)) {
            throw new IllegalArgumentException("mismatched group id or size found in chunk");
        }
    }

    @Override
    public Optional<byte[]> stitch(@NonNull Chunk chunk) {
        log.atTrace().log(() -> "received: " + chunk);
        final UUID groupId = chunk.getGroupId();
        CompleteChunkGroupHolder completeChunkGroupHolder = new CompleteChunkGroupHolder();
        chunkGroups.asMap().compute(groupId, (gid, group) -> {
            if (group == null) {
                group = new HashSet<>();
            }
            if (!group.add(chunk)) {
                log.atWarn().log("received duplicate chunk: {}", chunk);
            }
            int received = group.size();
            int expected = chunk.getGroupSize();
            Logger debug = log.atDebug();
            if (received != expected) {
                if (debug.isEnabled()) {
                    debug.log(
                            "received [{}] chunks while expecting [{}], keeping group [{}] in cache, not ready to stitch",
                            received,
                            expected,
                            groupId);
                }
                return group;
            }
            if (debug.isEnabled()) {
                debug.log("all [{}] expected chunks received, evicting group [{}] from cache, ready to stitch",
                        expected,
                        groupId);
            }
            completeChunkGroupHolder.setCompleteGroupOfChunks(group);
            return null;
        });
        Set<Chunk> completeGroupOfChunks = completeChunkGroupHolder.getCompleteGroupOfChunks();
        return completeGroupOfChunks == null ? Optional.empty() : Optional.of(stitchAll(completeGroupOfChunks));
    }

    private static class SinceCreation implements Expiry<UUID, Set<Chunk>> {

        private final Duration duration;

        SinceCreation(Duration duration) {
            this.duration = duration;
        }

        @Override
        public long expireAfterCreate(UUID uuid, Set<Chunk> chunks, long currentTime) {
            return duration.toNanos();
        }

        @Override
        public long expireAfterUpdate(UUID uuid, Set<Chunk> chunks, long currentTime, long currentDuration) {
            return currentDuration;
        }

        @Override
        public long expireAfterRead(UUID uuid, Set<Chunk> chunks, long currentTime, long currentDuration) {
            return currentDuration;
        }
    }

    /**
     * The stitcher builder.
     */
    public static class Builder {

        private Duration maxStitchTime;
        private Long maxGroups;

        private Boolean verifyBeforeStitch;

        /**
         * @param maxStitchTime max duration from the very first chunk received by the stitcher to the original data is
         *                      restored completely
         * @return the fluent builder
         */
        public Builder maxStitchTime(Duration maxStitchTime) {
            this.maxStitchTime = maxStitchTime;
            return this;
        }

        /**
         * @param maxGroups max number of pending stitch groups. These groups will take up memory at runtime.
         * @return the fluent builder
         */
        public Builder maxGroups(long maxGroups) {
            this.maxGroups = maxGroups;
            return this;
        }

        public Builder verifyBeforeStitch(boolean verifyBeforeStitch) {
            this.verifyBeforeStitch = verifyBeforeStitch;
            return this;
        }

        /**
         * @return chunk stitcher built
         */
        public ChunkStitcher build() {
            return new ChunkStitcher(this);
        }
    }

    @Data
    private static class CompleteChunkGroupHolder {
        Set<Chunk> completeGroupOfChunks;
    }

    private class InvoluntaryEvictionLogger implements RemovalListener<UUID, Set<Chunk>> {

        @Override
        public void onRemoval(UUID groupId, Set<Chunk> chunks, @Nonnull RemovalCause cause) {
            switch (cause) {
                case EXPIRED:
                    log.atWarn()
                            .log("chunk group [{}] took too long to stitch and expired after [{}], expecting [{}] chunks but only received [{}] when expired",
                                    groupId,
                                    maxStitchTime,
                                    chunks.stream().findFirst().orElseThrow(NoSuchElementException::new).getGroupSize(),
                                    chunks.size());
                    break;
                case SIZE:
                    log.atWarn()
                            .log("chunk group [{}] was removed due to exceeding max group count [{}]",
                                    groupId,
                                    maxGroups);
                    break;
                case EXPLICIT:
                case REPLACED:
                case COLLECTED:
                    break;
                default:
                    throw new AssertionError("Unexpected eviction cause: " + cause.name());
            }
        }
    }
}
