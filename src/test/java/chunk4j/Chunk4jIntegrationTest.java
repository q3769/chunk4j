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

import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Qingtian Wang
 */
class Chunk4jIntegrationTest {

    private static final String DATA_TEXT1 =
            "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat. Duis aute irure dolor in reprehenderit in voluptate velit esse cillum dolore eu fugiat nulla pariatur. Excepteur sint occaecat cupidatat non proident, sunt in culpa qui officia deserunt mollit anim id est laborum.";
    private static final String DATA_TEXT2 = DATA_TEXT1 + DATA_TEXT1;

    @Test
    void chunksInRandomOrder() {
        ChunkChopper chopper = ChunkChopper.ofChunkByteSize(4);
        ChunkStitcher stitcher = new ChunkStitcher.Builder().build();

        List<Chunk> choppedMingledAndScrambled = new ArrayList<>();
        choppedMingledAndScrambled.addAll(chopper.chop(DATA_TEXT1.getBytes()));
        choppedMingledAndScrambled.addAll(chopper.chop(DATA_TEXT2.getBytes()));
        Collections.shuffle(choppedMingledAndScrambled);
        Set<byte[]> stitched = new HashSet<>();
        choppedMingledAndScrambled.forEach(chunk -> stitcher.stitch(chunk).ifPresent(stitched::add));
        Set<String> restored = stitched.stream().map(String::new).collect(Collectors.toSet());

        assertTrue(restored.contains(DATA_TEXT1));
        assertTrue(restored.contains(DATA_TEXT2));
    }
}
