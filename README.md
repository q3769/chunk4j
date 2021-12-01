# chunk4j

A Java API to chop up larger data blobs into smaller "chunks" of a pre-defined size, and stitch the chunks back together to restore the original data when needed.

## User story

As a user of the chunk4j API, I want to be able to chop data blobs (bytes) into pieces of a pre-defined size and, when needed, restore the original data by stitching the pieces back together.

Note that the separate processes of "chop" and "stitch" often happen on different network compute nodes, and the chunks are transported between the nodes in a possibly random order. While being a generic Java API, chunk4j comes in handy when you have to send, over the network, messages whose sizes may be exceeding what is allowed by the messaging transport.

## Prerequisite
Java 8 or better

## Get it...

In Maven

```
<dependency>
    <groupId>io.github.q3769.qlib</groupId>
    <artifactId>chunk4j</artifactId>
    <version>20211201.0.0</version>
</dependency>
```

In Gradle

```
implementation 'io.github.q3769.qlib:chunk4j:20211201.0.0'
```

## Use it...

### The Chunk

A larger blob of data can be chopped up into smaller "chunks" to form a "group". When needed, often on a different network node, the group of chunks can be collectively stitched back together to restore the original data. A group has to gather all the originally chopped chunks in order to be stitched and restored back to the original data blob.

As the API user, though, you don't need to be concerned about the intricacies of the `Chunk` - it suffices to know that `Chunk` is a `Serializable` POJO with an upper byte size capacity. Instead, you can directly work with `Chopper` and `Stitcher`, and only be concerned with your own original data bytes.

```
@Value
@Builder
public class Chunk implements Serializable {

    ...

    /**
     * Maximum bytes of data a chunk can hold.
     */
    int byteCapacity;

    /**
     * The group ID of the original data blob. All chunks in the same group share the same group ID.
     */
    UUID groupId;

    /**
     * Total number of chunks the original data blob is chopped to form the group.
     */
    int groupSize;

    /**
     * Ordered index at which this current chunk is positioned inside the group. Chunks are chopped off from the
     * original data bytes in sequential order, indexed as such, and assigned with the same group ID as all other chunks
     * in the group that represents the same original data bytes.
     */
    int index;

    /**
     * Data bytes chopped for this current chunk to hold. Every chunk in the group should hold bytes of size equal to
     * the chunk's full capacity except maybe the last one in the group.
     */
    byte[] bytes;
    
    ...
}
```

### The Chopper


```
public interface Chopper {
    List<Chunk> chop(byte[] bytes);
}
```

On the chopper side, a data blob (bytes) is chopped into a group of chunks. You only have to say how big you want the chunks chopped up to be. Internally, the chopper will divide up the original data bytes based on the chunk size you specified, and assign a unique group ID to all the chunks in the same group representing the original data unit.

```
public class MessageProducer {

    priviate Chopper chopper = ChunksChopper.ofChunkByteSize(1024); // each chopped off chunk holds up to 1024 bytes
    prviate MessagingTransport transport = ...;
    
    ...

    /**
     * Sender method of business data
     */
    public void sendBusinessData(String dataText) {
        List<Chunk> chunks = this.chopper.chop(dataText.getBytes());
        this.transport.sendAll(chunks);
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

On the stitcher side, the `stitch` method is called repeatedly on all chunks. On each call, if a meaningful group of chunks can form to restore a complete original data blob (bytes), such bytes are returned inside an `Optional`; otherwise, the `stitch` method caches the `chunk` and returns an empty `Optional`. i.e. You keep calling the `stitch` method with each and every chunk you receive; you'll know you get a restored original data unit when the method returns a non-empty optional that contains the restored bytes.  

```
public class MessageConsumer {

    private Stitcher stitcher = new ChunksStitcher.Builder().build();
    
    ...

    /**
     * Suppose the run-time invocation of this method is managed by messaging provider/transport
     */
    public void onReceiving(Chunk chunk) {
        final Optional<byte[]> stitchedBytes = this.stitcher.stitch(chunk);
        stitchedBytes.ifPresent(originalDataBytes -> this.consumeBusinessData(new String(originalDataBytes));
    }
    
    /**
     * Consumer method of business data
     */
    private void consumeBusinessData(String dataText) {
        ...
    }
    
    ...
}
```

The stitcher caches all "pending" chunks it has received via the `stitch` method in different groups, each group representing one original data unit. When an incoming `chunk` renders its group "complete" - i.e. the group has gathered all the chunks needed to restore the whole group of chunks back to the original data unit - then such group of chunks are stitched back together for original data restoration. As soon as the original data unit is restored and returned by the `stitch` method, all chunks in the restored group are evicted from the cache.

By default, a stitcher caches unbounded groups of pending chunks, and a pending group of chunks will never be discarded no matter how much time has passed without being able to restore the group back to the original data unit:

```
new ChunksStitcher.Builder().build()
```

Both aspects of the default, though, can be customized. The following stitcher will discard a group of chunks if 2 seconds have passed since the stitcher was asked to stitch the very first chunk of the group, but hasn't received all the chunks needed to retore the whole group back to the original data unit:

```
new ChunksStitcher.Builder().maxStitchTimeMillis(2000).build()
```

This stitcher will discard some group(s) of chunks when there are more than 100 chunk groups pending restoration:

```
new ChunksStitcher.Builder().maxGroups(100).build()
```

This stitcher is customized by a combination of both aspects:

```
new ChunksStitcher.Builder().maxStitchTimeMillis(2000).maxGroups(100).build()
```

### Hints on using chunk4j API in messaging

These are independent of the chunk4j API itself but...

#### Chunk size/capacity

Chunk4j works completely on the application level of the network (Layer 7). In messaging, usually you'd transport one single chunk per each message. In case you have a hard limit of message size on the transport level, you want to make sure to set the chunk's byte capacity such that the size stays within the transport limit after the entire chunk/message is serialized. Know that there is a (small) fixed size overhead between the value of `Chunk.getByteCapacity()` and the final size of the serialized chunk.

#### Message acknowlegment/commit

When working with a messaging provider, you want to acknowlege/commit all the messages of an entire group of chunks in an all-or-nothing fashion, e.g. by using the individual and explicit commit mechanism. The all-or-nothing group commits help ensure, in the case of producer/consumer node crash, no original data units get lost after node recovery. 

If, however, the particular messaging provider lacks such mechanism that enables the all-or-nothing commit, then in case of node crash, it is possible that the original data unit whose group of chunks gets "cut off in the middle" at the time of crash will be lost in transportation. All the chunks of a group form a "session" that represents the original data unit. Loss of such sessions is similar to the case where active web sessions get lost when a stateful web application node hosting those sessions fails. To assess the potential "damage", you may need to ask in your system:

- At run-time, how often does an original data unit truly need more than one chunk to hold?
- How often does a node crash or go in and out of the system?
- What are the odds for those larger-than-one-chunk data units to be in transit right at the time the node crashes out?
