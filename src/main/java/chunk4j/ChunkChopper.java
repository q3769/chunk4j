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

package chunk4j;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * @author Qingtian Wang
 */
public final class ChunkChopper implements Chopper {

    public static ChunkChopper ofChunkByteSize(int maxChunkByteSize) {
        return new ChunkChopper(maxChunkByteSize);
    }

    private final int chunkCapacity;

    private ChunkChopper(int chunkByteCapacity) {
        if (chunkByteCapacity <= 0) {
            throw new IllegalArgumentException("Max size of the byte array in a chunk has to be a positive int: "
                    + chunkByteCapacity);
        }
        this.chunkCapacity = chunkByteCapacity;
    }

    @Override
    public List<Chunk> chop(byte[] bytes) {
        final UUID groupId = UUID.randomUUID();
        final int groupSize = numberOfChunks(bytes);
        final List<Chunk> chunks = new ArrayList<>();
        int start = 0;
        int chunkIndex = 0;
        while (start < bytes.length) {
            int end = Math.min(bytes.length, start + this.chunkCapacity);
            final byte[] chunkBytes = Arrays.copyOfRange(bytes, start, end);
            chunks.add(Chunk.builder()
                    .byteCapacity(chunkCapacity)
                    .groupId(groupId)
                    .groupSize(groupSize)
                    .index(chunkIndex++)
                    .bytes(chunkBytes)
                    .build());
            start += this.chunkCapacity;
        }
        assert groupSize == chunks.size();
        return chunks;
    }

    private int numberOfChunks(byte[] bytes) {
        int chunkCount = bytes.length / this.chunkCapacity;
        return bytes.length % this.chunkCapacity == 0 ? chunkCount : chunkCount + 1;
    }
}
