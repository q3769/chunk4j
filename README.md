# Chunks

A Java API to chop up larger data blobs into smaller "chunks" with pre-defined size, and stitch the chunks back together to restore the original data when needed.

## User story

As a user of the Chunks API, I want to be able to chop data blobs (bytes) into pieces of pre-defined size and, when needed, restore the original data by stitching the pieces back together.

Note that the processes of chopping and stitching often happen on different network compute nodes.

## Prerequisite
Java 8 or better

## Use it...

### The Chunk

A larger blob of data can be chopped up into smaller "chunks" to form a "group". When needed, often on a different network node, the group of chunks can be collectively stitched back together to restore the original data. A group has to gather all the originally chopped chunks in order to be stitched and restored back to the original data blob.   

```
@Value
@Builder
public class Chunk implements Serializable {

    /**
     * Maximum data byte size a chunk can hold.
     */
    private final int byteCapacity;

    /**
     * The group ID of the original data blob. All chunks in the same group share the same group ID.
     */
    private final UUID groupId;

    /**
     * Total number of chunks the original data blob is chopped to form the group.
     */
    private final int groupSize;

    /**
     * Ordered index at which this current chunk is positioned inside the chunk group.
     */
    private final int chunkPosition;

    /**
     * Data bytes chopped for this current chunk to hold. Every chunk in the group should hold bytes of size equal to
     * the chunk's full capacity except maybe the last one in the group.
     */
    private final byte[] bytes;

}
```

### The Chopper


```
public interface Chopper {
    List<Chunk> chop(byte[] bytes);
}
```

On the chopper side, a data blob (bytes) is chopped into a group of chunks. Internally, the chopper assigns the same group ID to all the chunks of the same group representing the original data bytes.

```
public class MySender {

	priviate Chopper chopper = DefaultChopper.ofChunkByteCapacity(1024); // holds up to 1024 bytes
	
	...

	public void send(String dataText) {
		List<Chunk> chunks = chopper.chop(aLargeDataText.getBytes());
		transport.sendAll(chunks);
	}

	...
}
```

### The Stitcher

```
public interface Stitcher {
    /**
     * @param chunk
     * @return Optional of the original data blob restored by stitching. Contains the restored bytes if the input chunk
     *         is the last missing piece of the entire group of chunks representing the orginal data; empty otherwise.
     */
    Optional<byte[]> stitch(Chunk chunk);
}

```

On the stitcher side, the `stitch` method is called repeatedly on all chunks. Whenever a meaningful group of chunks can form to restore a complete original data blob (bytes), such bytes are returned. 

```
public class MyReceiver {

	private Stitcher stitcher = new DefaultStitcher.Builder().build();
	
	...


	public void onReceiving(Chunk chunk) {
		final Optional<byte[]> stitchedBytes = stitcher.stitch(chunk);
		if (stitchedBytes.isEmpty())
			return;
		else 
			processOriginalData(new String(stitchedBytes.get()));
	}
	
	private void processOriginalData(String dataText) {
		...
	}
	
	...

```

The stitcher caches pending chunks in different groups until a full meaningful group is formed to stitch and restore the original data blob. As soon as the original data blob is restored from a group and returned by the `stitch` method, all chunks in that particular group is cleared and evicted from cache.

By default, a stitcher keep unbounded groups of pending chunks, and a pending group of chunks will never be discarded no matter how much time has passed without being able to form a restorable group of chunks. Both aspects of the default, though, can be customized.

This stitcher will discard a group of chunks if 2 seconds has passed since it received the first chunk of the group but hasn't received all the chunks needed to stitch the whole group of chunks back to the original data:

```
Stitcher stitcher = new DefaultStitcher.Builder().maxStitchTimeMillis(2000).build();
```

This stitcher will discard some group(s) of chunks when there are more than 100 chunk groups pending to restore back to original data:

```
Stitcher stitcher = new DefaultStitcher.Builder().maxGroups(100).build();
```

This stitcher is customized by a combination of both aspects:

```
Stitcher stitcher = new DefaultStitcher.Builder().maxStitchTimeMillis(2000).maxGroups(100).build();
```
