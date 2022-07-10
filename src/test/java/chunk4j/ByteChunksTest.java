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

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @author Qingtian Wang
 */
class ByteChunksTest {

    private static final Level TEST_RUN_LOG_LEVEL = Level.FINER;
    private static final String DATA_TEXT1 =
            "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat. Duis aute irure dolor in reprehenderit in voluptate velit esse cillum dolore eu fugiat nulla pariatur. Excepteur sint occaecat cupidatat non proident, sunt in culpa qui officia deserunt mollit anim id est laborum.";
    private static final String DATA_TEXT2 = DATA_TEXT1 + DATA_TEXT1;

    @BeforeAll public static void setLoggingLevel() {
        Logger root = Logger.getLogger("");
        // .level= ALL
        root.setLevel(TEST_RUN_LOG_LEVEL);
        for (Handler handler : root.getHandlers()) {
            if (handler instanceof ConsoleHandler) {
                // java.util.logging.ConsoleHandler.level = ALL
                handler.setLevel(TEST_RUN_LOG_LEVEL);
            }
        }
    }

    @Test void testByteChunks() {
        final int chunkByteCapacity = 4;
        final ChunkChopper chopper = ChunkChopper.ofChunkByteSize(chunkByteCapacity);
        final ChunkStitcher stitcher = new ChunkStitcher.Builder().build();

        final List<Chunk> choppedMingledAndScrambledData = new ArrayList<>();
        choppedMingledAndScrambledData.addAll(chopper.chop(DATA_TEXT1.getBytes()));
        choppedMingledAndScrambledData.addAll(chopper.chop(DATA_TEXT2.getBytes()));
        Collections.shuffle(choppedMingledAndScrambledData);
        final List<byte[]> stitched = new ArrayList<>();
        choppedMingledAndScrambledData.forEach(chunk -> stitcher.stitch(chunk).ifPresent(stitched::add));

        final int originalDataUnits = 2;
        assertEquals(originalDataUnits, stitched.size());
        final String dataStitched1 = new String(stitched.get(0));
        final String dataStitched2 = new String(stitched.get(1));
        if (!DATA_TEXT1.equals(dataStitched1))
            assertEquals(DATA_TEXT2, dataStitched1);
        else
            assertEquals(DATA_TEXT2, dataStitched2);

    }
}
