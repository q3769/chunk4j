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

import java.io.Serializable;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;

/**
 * A larger blob of data can be chopped up into smaller "chunks" that form a "group". The group of
 * chunks can then be collectively stitched back together to restore the original data.
 * 
 * @author Qingtian Wang
 */
@Value
@Builder
public class Chunk implements Serializable {

    /**
     * Maximum data byte size the chunk and hold.
     */
    private final int byteCapacity;

    /**
     * Effectively the name of the original data.
     */
    private final UUID groupId;

    /**
     * Total number of chunks the original data blob is chopped into.
     */
    private final int groupSize;

    /**
     * Ordered index this chunk is positioned inside the group.
     */
    private final int chunkPosition;

    /**
     * Data bytes chopped into this chunk. Every chunk in the group should hold bytes to its full capacity in size
     * except maybe the last one in the group.
     */
    private final byte[] bytes;

}
