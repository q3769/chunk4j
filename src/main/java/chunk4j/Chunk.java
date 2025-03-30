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

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

/**
 * The Chunk record represents a chunk of data that is part of a larger data blob. The data blob can
 * be chopped up into smaller chunks to form a group. When needed, often on a different network node
 * than the one where the data was chopped, the group of chunks can be collectively stitched back
 * together to restore the original data unit.
 *
 * @param groupId The unique identifier for the entire group of chunks representing the original
 *     data unit.
 * @param orderIndex The order index of this chunk within the chunk group.
 * @param groupSize The total number of chunks in the group.
 * @param bytes The byte array representing the data of this chunk.
 * @author Qingtian Wang
 */
@Builder
public record Chunk(@NonNull UUID groupId, int orderIndex, int groupSize, byte @NonNull [] bytes)
    implements Serializable {

  @Serial
  private static final long serialVersionUID = -1879320933982945956L;

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o instanceof Chunk chunk) {
      return orderIndex == chunk.orderIndex && Objects.equals(groupId, chunk.groupId);
    }
    return false;
  }

  @Override
  public int hashCode() {
    return Objects.hash(groupId, orderIndex);
  }
}
