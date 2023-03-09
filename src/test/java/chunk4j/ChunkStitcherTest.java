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

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class ChunkStitcherTest {

    static final byte[] BYTES = new byte[1000];
    static final int CHUNK_BYTE_SIZE = 10;

    @Nested
    class maxGroups {

        @Test
        void lessThanNeeded() throws InterruptedException, ExecutionException {
            int originalDataItems = 1000;
            int insufficientMaxGroups = originalDataItems / 500;
            ChunkStitcher tot = new ChunkStitcher.Builder().maxStitchingGroups(insufficientMaxGroups).build();
            List<Chunk> chunksOfAllItems = new ArrayList<>();
            ChunkChopper chunkChopper = ChunkChopper.ofByteSize(CHUNK_BYTE_SIZE);
            for (int i = 0; i < originalDataItems; i++) {
                chunksOfAllItems.addAll(chunkChopper.chop(BYTES));
            }

            ExecutorService executorService = Executors.newFixedThreadPool(insufficientMaxGroups * 100);
            List<Future<Optional<byte[]>>> allStitchedFutures = new ArrayList<>();
            for (Chunk chunk : chunksOfAllItems) {
                allStitchedFutures.add(executorService.submit(() -> tot.stitch(chunk)));
            }
            List<Optional<byte[]>> allStitchedOptionals = new ArrayList<>();
            for (Future<Optional<byte[]>> f : allStitchedFutures) {
                allStitchedOptionals.add(f.get());
            }

            long allStitchedItems = allStitchedOptionals.stream().filter(Optional::isPresent).count();
            assertTrue(allStitchedItems < originalDataItems,
                    "stitched and restored items [" + allStitchedItems + "] should be less than original items ["
                            + originalDataItems + "] due to insufficient maxGroups [" + insufficientMaxGroups
                            + "] being too much less than original items");
        }
    }

    @Nested
    class maxStitchTime {

        static final long TOTAL_STITCH_TIME_MILLIS = 500;

        @Test
        void lessThanNeeded() throws ExecutionException, InterruptedException {
            List<Chunk> chunks = ChunkChopper.ofByteSize(CHUNK_BYTE_SIZE).chop(BYTES);
            long maxTimePerStitch = TOTAL_STITCH_TIME_MILLIS / chunks.size();
            Duration lessThanNeededToStitchAll = Duration.ofMillis(TOTAL_STITCH_TIME_MILLIS - maxTimePerStitch);
            ChunkStitcher tot = new ChunkStitcher.Builder().maxStitchTime(lessThanNeededToStitchAll).build();
            Optional<byte[]> stitched = Optional.of(new byte[42]);

            ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
            for (Chunk chunk : chunks) {
                stitched = scheduledExecutorService.schedule(() -> tot.stitch(chunk),
                        maxTimePerStitch,
                        TimeUnit.MILLISECONDS).get();
            }

            assertFalse(stitched.isPresent(), "stitcher should have expired in [" + lessThanNeededToStitchAll + "]");
        }
    }

    @Nested
    class maxStitchingSize {

        final int maxStitchedByteSize = BYTES.length / 2;

        @Test
        void exceedingMaxStitchingSize() {
            ChunkStitcher tot = new ChunkStitcher.Builder().maxStitchedByteSize(maxStitchedByteSize).build();
            List<Chunk> chunks = ChunkChopper.ofByteSize(CHUNK_BYTE_SIZE).chop(BYTES);

            assertThrows(IllegalArgumentException.class, () -> {
                for (Chunk chunk : chunks) {
                    tot.stitch(chunk);
                }
            });
        }
    }
}
