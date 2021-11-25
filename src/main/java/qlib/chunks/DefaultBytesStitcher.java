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

package qlib.chunks;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.RemovalListener;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author Qingtian Wang
 */
public class DefaultBytesStitcher implements BytesStitcher {

    private static final Logger LOG = Logger.getLogger(DefaultBytesStitcher.class.getName());
    private static final long DEFAULT_MAX_CHUNKS_GROUP_COUNT = Long.MAX_VALUE;
    private static final long DEFAULT_MAX_STITCH_TIME_MILLIS = Long.MAX_VALUE;

    private static byte[] stitchAll(List<Chunk> group) {
        byte[] groupBytes = new byte[getGroupBytesSize(group)];
        int groupBytesPosition = 0;
        for (Chunk chunk : group) {
            final int chunkBytesLength = chunk.getBytes().length;
            System.arraycopy(chunk.getBytes(), 0, groupBytes, groupBytesPosition, chunkBytesLength);
            groupBytesPosition += chunkBytesLength;
        }
        return groupBytes;
    }

    private static int getGroupBytesSize(List<Chunk> group) {
        return group.stream()
                .mapToInt(chunk -> chunk.getBytes().length)
                .sum();
    }

    private final Cache<UUID, List<Chunk>> chunkGroups;

    private DefaultBytesStitcher(Builder builder) {
        final long maxStitchTimeMillis = builder.maxStitchTimeMillis == null ? DEFAULT_MAX_STITCH_TIME_MILLIS
                : builder.maxStitchTimeMillis;
        final long maxGroups = builder.maxGroups == null ? DEFAULT_MAX_CHUNKS_GROUP_COUNT : builder.maxGroups;
        this.chunkGroups = Caffeine.newBuilder()
                .evictionListener(new LoggingListener(maxStitchTimeMillis))
                .expireAfterWrite(maxStitchTimeMillis, TimeUnit.MILLISECONDS)
                .maximumSize(maxGroups)
                .build();
    }

    @Override
    public Optional<byte[]> stitch(Chunk chunk) {
        final UUID groupId = chunk.getGroupId();
        List<Chunk> chunks = chunkGroups.getIfPresent(groupId);
        if (chunks == null) {
            chunks = new ArrayList<>(List.of(chunk));
            chunkGroups.put(groupId, chunks);
        } else {
            chunks.add(chunk);
        }
        if (chunks.size() != chunk.getGroupSize()) {
            return Optional.empty();
        }
        chunkGroups.invalidate(groupId);
        Collections.sort(chunks, (c1, c2) -> {
            return c1.getChunkPosition() - c2.getChunkPosition();
        });
        return Optional.of(stitchAll(chunks));
    }

    static public class Builder {

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

        public DefaultBytesStitcher build() {
            return new DefaultBytesStitcher(this);
        }

    }

    private static class LoggingListener implements RemovalListener<UUID, List<Chunk>> {

        private final long maxStitchTimeMillis;

        public LoggingListener(long maxStitchTimeMillis) {
            this.maxStitchTimeMillis = maxStitchTimeMillis;
        }

        @Override
        public void onRemoval(UUID groupName, List<Chunk> chunks, RemovalCause cause) {
            if (cause == RemovalCause.EXPIRED) {
                LOG.log(Level.SEVERE,
                        "Chunk group {0} took too long to stitch and expired after {1} milliseconds, expected {2} chunks but only {3} received when expired",
                        new Object[] { groupName, maxStitchTimeMillis, chunks.get(0)
                                .getGroupSize(), chunks.size() });
            }
        }
    }

}
