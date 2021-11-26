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
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * @author Qingtian Wang
 */
public class DefaultChopper implements Chopper {

    public static DefaultChopper ofChunkByteCapacity(int chunkByteCapacity) {
        return new DefaultChopper(chunkByteCapacity);
    }

    private final int chunkByteCapacity;

    private DefaultChopper(int chunkByteCapacity) {
        this.chunkByteCapacity = chunkByteCapacity;
    }

    @Override
    public List<Chunk> chop(byte[] bytes) {
        final int groupSize = getChunkCount(bytes);
        final UUID groupName = UUID.randomUUID();
        List<Chunk> chunks = new ArrayList<>(groupSize);
        int start = 0;
        int chunkPosition = 0;
        while (start < bytes.length) {
            int end = Math.min(bytes.length, start + chunkByteCapacity);
            chunks.add(new Chunk.ChunkBuilder().byteCapacity(chunkByteCapacity)
                    .groupId(groupName)
                    .groupSize(groupSize)
                    .chunkPosition(chunkPosition++)
                    .bytes(Arrays.copyOfRange(bytes, start, end))
                    .build());
            start += chunkByteCapacity;
        }
        assert groupSize == chunks.size();
        return chunks;
    }

    private int getChunkCount(byte[] bytes) {
        int chunkCount = bytes.length / chunkByteCapacity;
        return bytes.length % chunkByteCapacity == 0 ? chunkCount : chunkCount + 1;
    }

}
