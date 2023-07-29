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

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.Value;

import java.io.Serializable;
import java.util.UUID;

/**
 * A larger blob of data can be chopped up into smaller "chunks" to form a "group". When needed, often on a different
 * network node than the one where the data was chopped, the group of chunks can be collectively stitched back together
 * to restore the original data.
 *
 * @author Qingtian Wang
 */
@Value
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Builder
public class Chunk implements Serializable {

    private static final long serialVersionUID = -1879320933982945956L;
    /**
     * The group ID of the original data blob. All chunks in the same group share the same group ID.
     */
    @EqualsAndHashCode.Include UUID groupId;

    /**
     * Ordered index at which this current chunk is positioned inside the group. Chunks are chopped off from the
     * original data bytes in sequential order, indexed as such, and assigned with the same group ID as all other chunks
     * in the group that represents the original data bytes.
     */
    @EqualsAndHashCode.Include int index;

    /**
     * Total number of chunks the original data blob is chopped to form the group.
     */
    int groupSize;

    /**
     * Data bytes chopped for this current chunk to hold.
     */
    @ToString.Exclude byte[] bytes;
}
