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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * @author QingtianWang
 */
public class ByteChunksIT {

    private static final String DATA_TEXT1 =
            "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat. Duis aute irure dolor in reprehenderit in voluptate velit esse cillum dolore eu fugiat nulla pariatur. Excepteur sint occaecat cupidatat non proident, sunt in culpa qui officia deserunt mollit anim id est laborum.";
    private static final String DATA_TEXT2 = DATA_TEXT1 + DATA_TEXT1;

    @Test
    public void testByteChunks() {
        final int chunkByteCapacity = 2;
        final String chunkGroup1 = "data1";
        final String chunkGroup2 = "data2";
        final DefaultBytesChopper chopper1 = DefaultBytesChopper.builder()
                .chunkByteCapacity(chunkByteCapacity)
                .chunkGroupName(chunkGroup1)
                .build();
        final DefaultBytesChopper chopper2 = DefaultBytesChopper.builder()
                .chunkByteCapacity(chunkByteCapacity)
                .chunkGroupName(chunkGroup2)
                .build();
        final DefaultBytesStitcher stitcher = new DefaultBytesStitcher.Builder().build();

        List<Chunk> chopped1 = chopper1.chop(DATA_TEXT1.getBytes());
        List<Chunk> chopped2 = chopper2.chop(DATA_TEXT2.getBytes());
        final List<Chunk> combinedAndShuffled = Stream.of(chopped1, chopped2)
                .flatMap(x -> x.stream())
                .collect(Collectors.toList());
        Collections.shuffle(combinedAndShuffled);
        List<byte[]> stitched = new ArrayList<>();
        for (Chunk chunk : combinedAndShuffled) {
            final Optional<byte[]> stitchBytes = stitcher.stitch(chunk);
            if (stitchBytes.isEmpty())
                continue;
            stitched.add(stitchBytes.get());
        }

        assertEquals(2, stitched.size());
        String dataStitched1 = new String(stitched.get(0));
        String dataStitched2 = new String(stitched.get(1));
        assertFalse(dataStitched1.equals(dataStitched2));
        if (!DATA_TEXT1.equals(dataStitched1))
            assertEquals(DATA_TEXT2, dataStitched1);
        else
            assertEquals(DATA_TEXT2, dataStitched2);

    }
}