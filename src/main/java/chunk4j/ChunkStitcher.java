/*
 * MIT License
 *
 * Copyright (c) 2021 Qingtian Wang
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
import lombok.ToString;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;

/**
 * @author Qingtian Wang
 */
@ThreadSafe
public final class ChunkStitcher implements Stitcher {
    private static final int DEFAULT_MAX_RESTORE_BYTE_SIZE = Integer.MAX_VALUE;
    private static final long DEFAULT_MAX_STITCHING_GROUPS = Long.MAX_VALUE;
    private static final long DEFAULT_MAX_STITCH_TIME_NANOS = Long.MAX_VALUE;
    private static final Logger logger = Logger.instance();
    private final Cache<UUID, ChunkStitchingGroup> chunkGroups;
    private final int maxRestoreByteSize;
    private final Duration maxStitchTime;
    private final long maxStitchingGroups;

    private ChunkStitcher(@NonNull Builder builder) {
        maxStitchTime = builder.maxStitchTime;
        maxStitchingGroups = builder.maxStitchingGroups;
        maxRestoreByteSize = builder.maxRestoreByteSize;
        this.chunkGroups = Caffeine.newBuilder()
                .expireAfter(new SinceCreation(maxStitchTime))
                .maximumSize(maxStitchingGroups)
                .evictionListener(new InvoluntaryEvictionLogger())
                .build();
    }

    private static Optional<byte[]> optionalOf(@Nullable byte[] bytes) {
        return bytes == null ? Optional.empty() : Optional.of(bytes);
    }

    @Override
    public Optional<byte[]> stitch(@NonNull Chunk chunk) {
        logger.atTrace().log((Supplier) () -> "received: " + chunk);
        final UUID groupId = chunk.getGroupId();
        RestoredBytesHolder restoredBytesHolder = new RestoredBytesHolder();
        chunkGroups.asMap().compute(groupId, (gid, group) -> {
            if (group == null) {
                group = new ChunkStitchingGroup(chunk.getGroupId(), chunk.getGroupSize());
            }
            checkStitchingGroupByteSize(chunk, group);
            byte[] restoredBytes = group.addAndStitch(chunk);
            restoredBytesHolder.setRestoredBytes(restoredBytes);
            return restoredBytes == null ? group : null;
        });
        return optionalOf(restoredBytesHolder.getRestoredBytes());
    }

    private void checkStitchingGroupByteSize(Chunk chunk, ChunkStitchingGroup group) {
        if (maxRestoreByteSize == DEFAULT_MAX_RESTORE_BYTE_SIZE) {
            return;
        }
        if (chunk.getBytes().length + group.getCurrentGroupByteSize() > maxRestoreByteSize) {
            logger.atWarn()
                    .log("By adding {}, stitching group {} would have exceeded safe-guarding byte size {} for restore data",
                            chunk,
                            group.groupId,
                            maxRestoreByteSize);
            throw new IllegalArgumentException("Group restore data bytes exceeding configured max size");
        }
    }

    /**
     * The stitcher builder.
     */
    public static class Builder {
        private int maxRestoreByteSize = DEFAULT_MAX_RESTORE_BYTE_SIZE;
        private Duration maxStitchTime = Duration.ofNanos(DEFAULT_MAX_STITCH_TIME_NANOS);
        private long maxStitchingGroups = DEFAULT_MAX_STITCHING_GROUPS;

        /**
         * @return chunk stitcher built
         */
        public ChunkStitcher build() {
            return new ChunkStitcher(this);
        }

        /**
         * @param v Optional safeguard against excessive large size of target restore data - either by mistake or
         *          malicious attack. Default to no size limit.
         * @return same builder instance
         */
        public Builder maxRestoreByteSize(int v) {
            this.maxRestoreByteSize = v;
            return this;
        }

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
        public Builder maxStitchingGroups(long maxGroups) {
            this.maxStitchingGroups = maxGroups;
            return this;
        }
    }

    @NotThreadSafe
    @ToString
    private static class ChunkStitchingGroup {
        private final Set<Chunk> chunks = new HashSet<>();
        private final UUID groupId;
        private final int targetChunkTotal;

        ChunkStitchingGroup(UUID groupId, int targetChunkTotal) {
            this.groupId = groupId;
            this.targetChunkTotal = targetChunkTotal;
        }

        @Nullable
        public byte[] addAndStitch(Chunk chunk) {
            if (!chunk.getGroupId().equals(groupId) || chunk.getGroupSize() != getTargetChunkTotal()) {
                logger.atWarn().log("Mismatched chunk {} cannot be added to group {}", chunk, this);
                throw new IllegalArgumentException("Mismatch while adding chunk to corresponding group");
            }
            if (!chunks.add(chunk)) {
                logger.atWarn().log("Duplicate chunk {} received and ignored", chunk);
                return null;
            }
            if (getCurrentChunkTotal() == getTargetChunkTotal()) {
                return this.stitchAll();
            }
            return null;
        }

        int getCurrentChunkTotal() {
            return chunks.size();
        }

        int getCurrentGroupByteSize() {
            return chunks.stream().mapToInt(chunk -> chunk.getBytes().length).sum();
        }

        int getTargetChunkTotal() {
            return targetChunkTotal;
        }

        private byte[] stitchAll() {
            if (logger.atDebug().isEnabled()) {
                logger.atDebug().log("Stitching all [{}] chunks in group [{}]...", chunks.size(), groupId);
            }
            byte[] stitchedBytes = new byte[getCurrentGroupByteSize()];
            List<Chunk> orderedGroup = new ArrayList<>(chunks);
            orderedGroup.sort(Comparator.comparingInt(Chunk::getIndex));
            int chunkStartPosition = 0;
            for (Chunk chunk : orderedGroup) {
                byte[] chunkBytes = chunk.getBytes();
                System.arraycopy(chunkBytes, 0, stitchedBytes, chunkStartPosition, chunkBytes.length);
                chunkStartPosition += chunkBytes.length;
            }
            return stitchedBytes;
        }
    }

    @Data
    private static class RestoredBytesHolder {
        @Nullable byte[] restoredBytes;
    }

    private static class SinceCreation implements Expiry<UUID, ChunkStitchingGroup> {

        private final Duration duration;

        SinceCreation(Duration duration) {
            this.duration = duration;
        }

        @Override
        public long expireAfterCreate(@NonNull UUID uuid,
                @NonNull ChunkStitcher.ChunkStitchingGroup chunks,
                long currentTime) {
            return duration.toNanos();
        }

        @Override
        public long expireAfterUpdate(@NonNull UUID uuid,
                @NonNull ChunkStitcher.ChunkStitchingGroup chunks,
                long currentTime,
                long currentDuration) {
            return currentDuration;
        }

        @Override
        public long expireAfterRead(@NonNull UUID uuid,
                @NonNull ChunkStitcher.ChunkStitchingGroup chunks,
                long currentTime,
                long currentDuration) {
            return currentDuration;
        }
    }

    private class InvoluntaryEvictionLogger implements RemovalListener<UUID, ChunkStitchingGroup> {

        @Override
        public void onRemoval(UUID groupId, ChunkStitchingGroup chunkStitchingGroup, @Nonnull RemovalCause cause) {
            switch (cause) {
                case EXPIRED:
                    logger.atWarn()
                            .log("chunk group [{}] took too long to stitch and expired after [{}], expecting [{}] chunks but only received [{}] when expired",
                                    groupId,
                                    maxStitchTime,
                                    chunkStitchingGroup.getTargetChunkTotal(),
                                    chunkStitchingGroup.getCurrentChunkTotal());
                    break;
                case SIZE:
                    logger.atWarn()
                            .log("chunk group [{}] was removed due to exceeding max group count [{}]",
                                    groupId,
                                    maxStitchingGroups);
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
