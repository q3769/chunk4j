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
import elf4j.Level;
import elf4j.Logger;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import lombok.Data;
import lombok.NonNull;
import lombok.ToString;

/**
 * The ChunkStitcher class is responsible for stitching together chunks of data. It is thread-safe and uses a cache to
 * store chunk groups. The class also provides a Builder for easy configuration.
 *
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

    /**
     * Private constructor for the ChunkStitcher class. It is used by the Builder class to create a new instance of
     * ChunkStitcher.
     *
     * @param builder The builder used to configure the ChunkStitcher.
     */
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

    /**
     * Helper method to create an Optional from a byte array. If the byte array is null, an empty Optional is returned.
     * Otherwise, an Optional containing the byte array is returned.
     *
     * @param bytes The byte array to wrap in an Optional.
     * @return An Optional containing the byte array, or an empty Optional if the byte array is null.
     */
    private static Optional<byte[]> optionalOf(@Nullable byte[] bytes) {
        return bytes == null ? Optional.empty() : Optional.of(bytes);
    }

    /**
     * Adds a chunk to its corresponding chunk group. If the chunk is the last one expected by the group, the original
     * data bytes are restored and returned.
     *
     * @param chunk The chunk to be added to its corresponding chunk group.
     * @return An Optional containing the original data bytes if the chunk is the last one expected by the group, or an
     *     empty Optional otherwise.
     */
    @Override
    public Optional<byte[]> stitch(@NonNull Chunk chunk) {
        logger.atTrace().log(() -> "Received: " + chunk);
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

    /**
     * Checks if the byte size of the stitching group would exceed the maximum allowed size after adding the given
     * chunk. If the size would be exceeded, an IllegalArgumentException is thrown.
     *
     * @param chunk The chunk to be added to the stitching group.
     * @param group The stitching group to which the chunk is to be added.
     */
    private void checkStitchingGroupByteSize(Chunk chunk, ChunkStitchingGroup group) {
        if (maxStitchedByteSize == DEFAULT_MAX_STITCHED_BYTE_SIZE) {
            return;
        }
        if (chunk.getBytes().length + group.getCurrentGroupByteSize() > maxStitchedByteSize) {
            logger.atWarn()
                    .log(
                            "By adding {}, stitching group {} would have exceeded safe-guarding byte size {}",
                            chunk,
                            chunk.getGroupId(),
                            maxStitchedByteSize);
            throw new IllegalArgumentException("Stitched bytes in group exceeding configured max size");
        }
    }

    /**
     * The Builder class for the ChunkStitcher class. It provides a fluent interface for configuring a ChunkStitcher.
     */
    public static class Builder {
        private Duration maxStitchTime = Duration.ofNanos(DEFAULT_MAX_STITCH_TIME_NANOS);
        private int maxStitchedByteSize = DEFAULT_MAX_STITCHED_BYTE_SIZE;
        private long maxStitchingGroups = DEFAULT_MAX_STITCHING_GROUPS;

        /**
         * Builds a new ChunkStitcher with the current configuration of the Builder.
         *
         * @return A new ChunkStitcher.
         */
        public ChunkStitcher build() {
            return new ChunkStitcher(this);
        }

        /**
         * Sets the maximum duration from the first chunk received to the complete restoration of the original data.
         *
         * @param maxStitchTime The maximum duration.
         * @return The Builder, for method chaining.
         */
        public Builder maxStitchTime(Duration maxStitchTime) {
            this.maxStitchTime = maxStitchTime;
            return this;
        }

        /**
         * Sets the maximum byte size of the restored data. This can be used as a safeguard against excessively large
         * data sizes.
         *
         * @param v The maximum byte size.
         * @return The Builder, for method chaining.
         */
        public Builder maxStitchedByteSize(int v) {
            this.maxStitchedByteSize = v;
            return this;
        }

        /**
         * Sets the maximum number of pending stitch groups. These groups will take up memory at runtime.
         *
         * @param maxGroups The maximum number of groups.
         * @return The Builder, for method chaining.
         */
        public Builder maxStitchingGroups(long maxGroups) {
            this.maxStitchingGroups = maxGroups;
            return this;
        }
    }

    /**
     * The ChunkStitchingGroup class represents a group of chunks that are to be stitched together. It is not
     * thread-safe.
     */
    @NotThreadSafe
    @ToString
    private static class ChunkStitchingGroup {
        private final Set<Chunk> chunks = new HashSet<>();
        private final int expectedChunkTotal;

        /**
         * Constructor for the ChunkStitchingGroup class.
         *
         * @param expectedChunkTotal The total number of chunks expected by the group.
         */
        ChunkStitchingGroup(int expectedChunkTotal) {
            this.expectedChunkTotal = expectedChunkTotal;
        }

        /**
         * Sorts a set of chunks by their index.
         *
         * @param chunks The set of chunks to sort.
         * @return A list of chunks, sorted by their index.
         */
        private static List<Chunk> sorted(@NonNull Set<Chunk> chunks) {
            return chunks.stream()
                    .sorted(Comparator.comparingInt(Chunk::getIndex))
                    .collect(Collectors.toList());
        }

        /**
         * Stitches a set of chunks together into a byte array.
         *
         * @param chunks The set of chunks to stitch together.
         * @return A byte array containing the stitched chunks.
         */
        private static byte @NonNull [] stitchToBytes(@NonNull Set<Chunk> chunks) {
            byte[] stitchedBytes = new byte[totalByteSizeOf(chunks)];
            int chunkStartPosition = 0;
            for (Chunk chunk : sorted(chunks)) {
                byte[] chunkBytes = chunk.getBytes();
                System.arraycopy(chunkBytes, 0, stitchedBytes, chunkStartPosition, chunkBytes.length);
                chunkStartPosition += chunkBytes.length;
            }
            return stitchedBytes;
        }

        /**
         * Calculates the total byte size of a collection of chunks.
         *
         * @param chunks The collection of chunks.
         * @return The total byte size.
         */
        private static int totalByteSizeOf(@NonNull Collection<Chunk> chunks) {
            return chunks.stream().mapToInt(chunk -> chunk.getBytes().length).sum();
        }

        /**
         * Adds a chunk to the stitching group. If the chunk is the last one expected by the group, all chunks in the
         * group are stitched together and returned.
         *
         * @param chunk The chunk to be added to the group.
         * @return A byte array containing the stitched chunks if the chunk is the last one expected by the group, or
         *     null otherwise.
         */
        @Nullable public byte[] addAndStitch(Chunk chunk) {
            if (!chunks.add(chunk)) {
                logger.atWarn().log("Duplicate chunk {} received and ignored", chunk);
                return null;
            }
            if (getCurrentChunkTotal() == getExpectedChunkTotal()) {
                logger.atDebug().log(() -> "Stitching all " + chunks.size() + " chunks in group " + this);
                return stitchToBytes(chunks);
            }
            return null;
        }

        /**
         * Returns the current total number of chunks in the group.
         *
         * @return The current total number of chunks.
         */
        int getCurrentChunkTotal() {
            return chunks.size();
        }

        /**
         * Returns the current total byte size of the group.
         *
         * @return The current total byte size.
         */
        int getCurrentGroupByteSize() {
            return totalByteSizeOf(chunks);
        }

        /**
         * Returns the total number of chunks expected by the group.
         *
         * @return The total number of chunks expected.
         */
        int getExpectedChunkTotal() {
            return expectedChunkTotal;
        }
    }

    /**
     * The SinceCreation class is used to determine the expiry time of a chunk group in the cache.
     *
     * @param duration The duration after which a chunk group should expire.
     */
    private record SinceCreation(Duration duration) implements Expiry<UUID, ChunkStitchingGroup> {

        @Override
        public long expireAfterCreate(
                @NonNull UUID uuid, @NonNull ChunkStitcher.ChunkStitchingGroup chunks, long currentTime) {
            return duration.toNanos();
        }

        @Override
        public long expireAfterUpdate(
                @NonNull UUID uuid,
                @NonNull ChunkStitcher.ChunkStitchingGroup chunks,
                long currentTime,
                long currentDuration) {
            return currentDuration;
        }

        @Override
        public long expireAfterRead(
                @NonNull UUID uuid,
                @NonNull ChunkStitcher.ChunkStitchingGroup chunks,
                long currentTime,
                long currentDuration) {
            return currentDuration;
        }
    }

    /** The StitchedBytesHolder class is a simple data holder for a byte array. */
    @Data
    private static class StitchedBytesHolder {
        @Nullable byte[] stitchedBytes;
    }

    /**
     * The InvoluntaryEvictionLogger class is used to log when a chunk group is involuntarily evicted from the cache.
     */
    private class InvoluntaryEvictionLogger implements RemovalListener<UUID, ChunkStitchingGroup> {

        @Override
        public void onRemoval(UUID groupId, ChunkStitchingGroup chunkStitchingGroup, @NonNull RemovalCause cause) {
            switch (cause) {
                case EXPIRED ->
                    logger.atWarn()
                            .log(
                                    "chunk group [{}] took too long to stitch and expired after [{}], expecting [{}] chunks but only received [{}] when expired",
                                    groupId,
                                    maxStitchTime,
                                    chunkStitchingGroup.getExpectedChunkTotal(),
                                    chunkStitchingGroup.getCurrentChunkTotal());
                case SIZE ->
                    logger.atWarn()
                            .log(
                                    "chunk group [{}] was removed due to exceeding max group count [{}]",
                                    groupId,
                                    maxStitchingGroups);
                default -> logger.atLevel(Level.OFF).log("Manual removals logging is OFF");
            }
        }
    }
}
