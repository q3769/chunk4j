/*
 * MIT License
 *
 * Copyright (c) 2022. Qingtian Wang
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

import java.util.Optional;

/**
 * @author Qingtian Wang
 */
public interface Stitcher {

    /**
     * @param chunk to be added to its corresponding chunk group. If this chunk renders its group "complete", i.e. all
     *              the chunks of the original data blob are gathered, then the original data blob will be stitched
     *              together and returned. Otherwise, if the chunk group still hasn't gathered all the chunks needed,
     *              even with the addition of this chunk, then the whole group will be kept around, waiting for the
     *              missing chunk(s) to arrive.
     * @return Optional non-empty and contains the original data blob restored by stitching if the input chunk is the
     *         last missing piece of the entire chunk group representing the original data; empty otherwise.
     */
    Optional<byte[]> stitch(Chunk chunk);
}
