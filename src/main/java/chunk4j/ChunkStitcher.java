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
import lombok.Data;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @author Qingtian Wang
 */
@Slf4j
public final class ChunkStitcher implements Stitcher {

    private static final long DEFAULT_MAX_CHUNK_GROUP_COUNT = Long.MAX_VALUE;
    private static final long DEFAULT_MAX_STITCH_TIME_NANOS = Long.MAX_VALUE;

    private final Cache<UUID, Set<Chunk>> chunkGroups;
    private final Duration maxStitchTime;
    private final Long maxGroups;

    private ChunkStitcher(Builder builder) {
        maxStitchTime =
                builder.maxStitchTime == null ? Duration.ofNanos(DEFAULT_MAX_STITCH_TIME_NANOS) : builder.maxStitchTime;
        maxGroups = builder.maxGroups == null ? DEFAULT_MAX_CHUNK_GROUP_COUNT : builder.maxGroups;
        this.chunkGroups = Caffeine.newBuilder()
                .expireAfter(new SinceCreation(maxStitchTime))
                .maximumSize(maxGroups)
                .evictionListener(new InvoluntaryEvictionLogger())
                .build();
    }

    private static byte[] stitchAll(Set<Chunk> group) {
        UUID groupId = requireSameId(group);
        byte[] groupBytes = new byte[getTotalByteSize(group)];
        List<Chunk> orderedGroup = new ArrayList<>(group);
        orderedGroup.sort(Comparator.comparingInt(Chunk::getIndex));
        int groupBytesPosition = 0;
        for (Chunk chunk : orderedGroup) {
            byte[] chunkBytes = chunk.getBytes();
            final int chunkBytesLength = chunkBytes.length;
            System.arraycopy(chunkBytes, 0, groupBytes, groupBytesPosition, chunkBytesLength);
            groupBytesPosition += chunkBytesLength;
        }
        log.atDebug().log("stitched all chunks in group [{}]", groupId);
        return groupBytes;
    }

    private static int getTotalByteSize(@NonNull Set<Chunk> group) {
        return group.stream().mapToInt(chunk -> chunk.getBytes().length).sum();
    }

    private static UUID requireSameId(@NonNull Set<Chunk> group) {
        List<UUID> distinctIds = group.parallelStream().map(Chunk::getGroupId).distinct().collect(Collectors.toList());
        if (distinctIds.size() != 1) {
            throw new IllegalArgumentException(
                    "expecting one single group id for all chunks but got more: " + distinctIds.size());
        }
        return distinctIds.get(0);
    }

    @Override
    public Optional<byte[]> stitch(Chunk chunk) {
        log.atTrace().log("received: {}", chunk);
        final UUID groupId = chunk.getGroupId();
        CompleteGroupHolder completeGroupHolder = new CompleteGroupHolder();
        chunkGroups.asMap().compute(groupId, (gid, group) -> {
            if (group == null) {
                group = new HashSet<>();
            }
            if (!group.add(chunk)) {
                log.atWarn().log("received duplicate chunk: {}", chunk);
            }
            int received = group.size();
            int expected = chunk.getGroupSize();
            if (received != expected) {
                log.atDebug()
                        .log("received [{}] chunks while expecting [{}], keeping group [{}] in cache",
                                received,
                                expected,
                                groupId);
                return group;
            }
            log.atDebug()
                    .log("received all [{}] expected chunks, starting to stitch and restore original data and evicting group [{}] from cache",
                            expected,
                            groupId);
            completeGroupHolder.setCompleteGroupOfChunks(group);
            return null;
        });
        Set<Chunk> groupChunks = completeGroupHolder.getCompleteGroupOfChunks();
        if (groupChunks == null) {
            return Optional.empty();
        }
        return Optional.of(stitchAll(groupChunks));
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

        /**
         * @return chunk stitcher built
         */
        public ChunkStitcher build() {
            return new ChunkStitcher(this);
        }
    }

    @Data
    private static class CompleteGroupHolder {

        Set<Chunk> completeGroupOfChunks;
    }

    private class InvoluntaryEvictionLogger implements RemovalListener<UUID, Set<Chunk>> {

        @Override
        public void onRemoval(UUID groupId, Set<Chunk> chunks, @Nonnull RemovalCause cause) {
            switch (cause) {
                case EXPIRED:
                    log.atError()
                            .log("chunk group [{}] took too long to stitch and expired after [{}], expecting [{}] chunks but only received [{}] when expired",
                                    groupId,
                                    maxStitchTime,
                                    chunks.stream().findFirst().orElseThrow(NoSuchElementException::new).getGroupSize(),
                                    chunks.size());
                    break;
                case SIZE:
                    log.atError()
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
