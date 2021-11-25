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

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public class DefaultBytesStitcher implements BytesStitcher {

    private static byte[] stitchAll(List<Chunk> group) {
        byte[] groupBytes = new byte[getGroupBytesSize(group)];
        int groupBytesPosition = 0;
        for (Chunk chunk : group) {
            final int chunkBytesLength = chunk.getBytes().length;
            System.arraycopy(chunk.getBytes(), 0, groupBytes, groupBytesPosition, chunkBytesLength);
            groupBytesPosition += chunkBytesLength;
        }
        return groupBytes;
    }

    private static int getGroupBytesSize(List<Chunk> group) {
        return group.stream()
                .mapToInt(chunk -> chunk.getBytes().length)
                .sum();
    }

    private final Cache<String, List<Chunk>> chunkGroups;

    public DefaultBytesStitcher(long expireIfUnstitchableMillis) {
        this.chunkGroups = Caffeine.newBuilder()
                .expireAfterWrite(expireIfUnstitchableMillis, TimeUnit.MILLISECONDS)
                .build();
    }

    @Override
    public Optional<byte[]> stitch(Chunk chunk) {
        final String groupName = chunk.getGroupName();
        List<Chunk> group = chunkGroups.getIfPresent(groupName);
        if (group == null) {
            group = new ArrayList<>(List.of(chunk));
            chunkGroups.put(groupName, group);
        } else {
            group.add(chunk);
        }
        if (group.size() != chunk.getGroupSize()) {
            return Optional.empty();
        }
        chunkGroups.invalidate(groupName);
        Collections.sort(group, (c1, c2) -> {
            return c1.getGroupIndex() - c2.getGroupIndex();
        });
        return Optional.of(stitchAll(group));
    }

}
