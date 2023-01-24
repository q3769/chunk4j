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
import java.util.stream.Collectors;

/**
 * @author Qingtian Wang
 */
@ThreadSafe
public final class ChunkStitcher implements Stitcher {
    private static final int DEFAULT_MAX_STITCHED_BYTE_SIZE = Integer.MAX_VALUE;
    private static final long DEFAULT_MAX_STITCHING_GROUPS = Long.MAX_VALUE;
    private static final long DEFAULT_MAX_STITCH_TIME_NANOS = Long.MAX_VALUE;
    private static final Logger logger = Logger.instance();
    private final Cache<UUID, ChunkStitchingGroup> chunkGroups;
    private final Duration maxStitchTime;
    private final int maxStitchedByteSize;
    private final long maxStitchingGroups;

    private ChunkStitcher(@NonNull Builder builder) {
        maxStitchTime = builder.maxStitchTime;
        maxStitchingGroups = builder.maxStitchingGroups;
        maxStitchedByteSize = builder.maxStitchedByteSize;
        this.chunkGroups = Caffeine.newBuilder()
                .expireAfter(new SinceCreation(maxStitchTime))
                .maximumSize(maxStitchingGroups)
                .evictionListener(new InvoluntaryEvictionLogger())
                .build();
    }

    private static Optional<byte[]> optionalOf(@Nullable byte[] bytes) {
        return bytes == null ? Optional.empty() : Optional.of(bytes);
    }

    /**
     * @param chunk to be added to its corresponding chunk group, possibly stitched to restore the original data bytes
     *              if this is the last chunk the group is expecting.
     * @return non-empty <code>Optional</code> containing the original data bytes restored by the stitcher if the input
     *         chunk is the last missing piece of the entire chunk group representing the original data; otherwise, if
     *         the input chunk is not the last one expected, empty <code>Optional</code>.
     */
    @Override
    public Optional<byte[]> stitch(@NonNull Chunk chunk) {
        logger.atTrace().log((Supplier) () -> "Received: " + chunk);
        StitchedBytesHolder stitchedBytesHolder = new StitchedBytesHolder();
        chunkGroups.asMap().compute(chunk.getGroupId(), (k, group) -> {
            if (group == null) {
                group = new ChunkStitchingGroup(chunk.getGroupSize());
            }
            checkStitchingGroupByteSize(chunk, group);
            byte[] stitchedBytes = group.addAndStitch(chunk);
            stitchedBytesHolder.setStitchedBytes(stitchedBytes);
            return stitchedBytes == null ? group : null;
        });
        return optionalOf(stitchedBytesHolder.getStitchedBytes());
    }

    private void checkStitchingGroupByteSize(Chunk chunk, ChunkStitchingGroup group) {
        if (maxStitchedByteSize == DEFAULT_MAX_STITCHED_BYTE_SIZE) {
            return;
        }
        if (chunk.getBytes().length + group.getCurrentGroupByteSize() > maxStitchedByteSize) {
            logger.atWarn()
                    .log("By adding {}, stitching group {} would have exceeded safe-guarding byte size {}",
                            chunk,
                            chunk.getGroupId(),
                            maxStitchedByteSize);
            throw new IllegalArgumentException("Stitched bytes in group exceeding configured max size");
        }
    }

    /**
     * The stitcher builder.
     */
    public static class Builder {
        private Duration maxStitchTime = Duration.ofNanos(DEFAULT_MAX_STITCH_TIME_NANOS);
        private int maxStitchedByteSize = DEFAULT_MAX_STITCHED_BYTE_SIZE;
        private long maxStitchingGroups = DEFAULT_MAX_STITCHING_GROUPS;

        /**
         * @return chunk stitcher built
         */
        public ChunkStitcher build() {
            return new ChunkStitcher(this);
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
         * @param v Optional safeguard against excessive large size of target restore data - either by mistake or
         *          malicious attack. Default to no size limit.
         * @return same builder instance
         */
        public Builder maxStitchedByteSize(int v) {
            this.maxStitchedByteSize = v;
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
        private final int expectedChunkTotal;

        ChunkStitchingGroup(int expectedChunkTotal) {
            this.expectedChunkTotal = expectedChunkTotal;
        }

        private static List<Chunk> sorted(Set<Chunk> chunks) {
            return chunks.stream().sorted(Comparator.comparingInt(Chunk::getIndex)).collect(Collectors.toList());
        }

        @Nonnull
        private static byte[] stitchToBytes(@NonNull Set<Chunk> chunks) {
            byte[] stitchedBytes = new byte[totalByteSizeOf(chunks)];
            int chunkStartPosition = 0;
            for (Chunk chunk : sorted(chunks)) {
                byte[] chunkBytes = chunk.getBytes();
                System.arraycopy(chunkBytes, 0, stitchedBytes, chunkStartPosition, chunkBytes.length);
                chunkStartPosition += chunkBytes.length;
            }
            return stitchedBytes;
        }

        private static int totalByteSizeOf(@NonNull Collection<Chunk> chunks) {
            return chunks.stream().mapToInt(chunk -> chunk.getBytes().length).sum();
        }

        /**
         * @param chunk to be added in the stitching group, possibly stitched if this is the last chunk the group is
         *              expecting.
         * @return the bytes by stitching together all the chunks in the group if the passed-in chunk is the last one
         *         the group is expecting; otherwise, <code>null</code>.
         */
        @Nullable
        public byte[] addAndStitch(Chunk chunk) {
            if (!chunks.add(chunk)) {
                logger.atWarn().log("Duplicate chunk {} received and ignored", chunk);
                return null;
            }
            if (getCurrentChunkTotal() == getExpectedChunkTotal()) {
                logger.atDebug().log((Supplier) () -> "Stitching all " + chunks.size() + " chunks in group " + this);
                return stitchToBytes(chunks);
            }
            return null;
        }

        int getCurrentChunkTotal() {
            return chunks.size();
        }

        int getCurrentGroupByteSize() {
            return totalByteSizeOf(chunks);
        }

        int getExpectedChunkTotal() {
            return expectedChunkTotal;
        }
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

    @Data
    private static class StitchedBytesHolder {
        @Nullable byte[] stitchedBytes;
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
                                    chunkStitchingGroup.getExpectedChunkTotal(),
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
            }
        }
    }
}
