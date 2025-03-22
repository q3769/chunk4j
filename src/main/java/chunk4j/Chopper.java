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

import java.util.List;

/**
 * The Chopper interface defines the contract for chopping a data blob into chunks. Implementations
 * of this interface should provide a method to chop a byte array into a list of Chunk objects. Each
 * Chunk object represents a portion of the original data blob. The size of each portion is
 * determined by a pre-configured maximum size (the Chunk's capacity). If the size of the original
 * data blob is smaller or equal to the Chunk's capacity, the returned list will contain only one
 * Chunk.
 *
 * @author Qingtian Wang
 */
@FunctionalInterface
public interface Chopper {

  /**
   * Chops a byte array into a list of Chunk objects. Each Chunk object represents a portion of the
   * original data blob. The size of each portion is determined by a pre-configured maximum size
   * (the Chunk's capacity). If the size of the original data blob is smaller or equal to the
   * Chunk's capacity, the returned list will contain only one Chunk.
   *
   * @param bytes the original data blob to be chopped into chunks
   * @return the group of chunks which the original data blob is chopped into.
   */
  List<Chunk> chop(byte[] bytes);
}
