[![](https://img.shields.io/static/v1?label=github&message=repo&color=blue)](https://github.com/q3769/chunk4j)

A Java API to chop up larger data blobs into smaller "chunks" of a pre-defined size, and stitch the chunks back together
to restore the original data when needed.

# User story

As a user of the chunk4j API, I want to chop a data blob (bytes) into smaller pieces of a pre-defined size and, when
needed, restore the original data by stitching the pieces back together.

Notes:

- The separate processes of "chop" and "stitch" often need to happen on different network compute nodes; and the
  chunks are transported between the nodes in a possibly random order.
- The chunk4j API comes in handy when, at run-time, you may have to ship larger sized data entries than what is
  allowed by the underlying transport in a distributed system. E.g. at the time of writing, the default message size
  limit is 256KB with [Amazon Simple Queue Service (SQS)](https://aws.amazon.com/sqs/) , and 1MB
  with [Apache Kafka](https://kafka.apache.org/); the default cache entry size limit is 1MB
  for [Memcached](https://memcached.org/), and 512MB for [Redis](https://redis.io/). Although the default limits can be
  customized, often times, the default is there for a sensible reason. Meanwhile, it may be difficult to predict that
  the data entries being transported at run-time will, by their intrinsic nature, never go beyond the default or
  customized limit.

# Prerequisite

Java 8 or better

# Get it...

[![Maven Central](https://img.shields.io/maven-central/v/io.github.q3769/chunk4j.svg?label=Maven%20Central)](https://search.maven.org/search?q=g:%22io.github.q3769%22%20AND%20a:%22chunk4j%22)

# Use it...

- The implementation of chunk4j API is thread-safe.

## The Chopper

### API:

```java
public interface Chopper {

	/**
	 * @param bytes the original data blob to be chopped into chunks
	 * @return the group of chunks which the original data blob is chopped into. Each chunk carries a portion of the
	 *         original bytes; and the size of that portion has a pre-configured maximum (a.k.a. the {@code Chunk}'s
	 *         capacity). Thus, if the size of the original bytes is smaller or equal to the chunk's capacity, then the
	 *         returned chunk group will have only one chunk element.
	 */
	List<Chunk> chop(byte[] bytes);
}
```

A larger blob of data can be chopped up into smaller "chunks" to form a "group". When needed, often on a different
network node, the group of chunks can be collectively stitched back together to restore the original data.

### Usage example:

```java
public class MessageProducer {

	private Chopper chopper = ChunkChopper.ofByteSize(1024); // each chopped off chunk holds up to 1024 bytes

	@Autowired private MessagingTransport transport;

	/**
	 * Sender method of business data
	 */
	public void sendBusinessDomainData(String domainDataText) {
		chopper.chop(domainDataText.getBytes()).forEach((chunk) -> transport.send(toMessage(chunk)));
	}

	/**
	 * pack/serialize/marshal the chunk POJO into a transport-specific message
	 */
	private Message toMessage(Chunk chunk) {
		//...
	}
}
```

On the `Chopper` side, you only have to say how big you want the chunks chopped up to be. The chopper will internally
divide up the original data bytes based on the chunk size you specified, and assign a unique group ID to all the chunks
in the same group representing the original data unit.

## The Chunk

### API:

```java
public class Chunk implements Serializable {

	private static final long serialVersionUID = 42L;

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
	byte[] bytes;
}
```

### Usage example:

Chunk4J aims to handle most details of the `Chunk` behind the scenes of the `Chopper` and `Stitcher` API. For the API
client, it suffices to know that `Chunk` is a simple POJO data holder; serializable, it carries the data bytes
travelling from the `Chopper` to the `Stitcher`. To transport Chunks over the network, the API client simply needs to
pack the Chunk into a transport-specific message on the Chopper's end, and unpack the message back to a Chunk on the
Stitcher's end, using the POJO marshal-unmarshal technique applicable to the transport.

## The Stitcher

### API:

```java
public interface Stitcher {

    /**
     * @param chunk to be added to its corresponding chunk group. If this chunk renders its group "complete", i.e. all
     *              the chunks of the original data blob are gathered, then the original data blob will be stitched
     *              together and returned. Otherwise, if the chunk group still hasn't gathered all the chunks needed,
     *              even with the addition of this chunk, then the whole group will be kept around, waiting for the
     *              missing chunk(s) to arrive.
     * @return Optional nonempty and contains the original data blob restored by stitching if the input chunk is the
     *         last missing piece of the entire chunk group representing the original data; empty otherwise.
     */
    Optional<byte[]> stitch(Chunk chunk);
}
```

On the stitcher side, a group must gather all the previously chopped chunks before the original data blob represented by
this group can be stitched back together and restored.

### Usage example:

```java
public class MessageConsumer {

    private final Stitcher stitcher = new ChunkStitcher.Builder().build();

    @Autowried private DomainDataProcessor domainDataProcessor;

    /**
     * Suppose the run-time invocation of this method is managed by messaging provider/transport
     */
    public void onReceiving(Message message) {
        stitcher.stitch(toChunk(message))
                .ifPresent(originalDomainDataBytes -> domainDataProcessor.process(new String(originalDomainDataBytes)));
    }

    /**
     * unpack/deserialize/unmarshal the chunk POJO from the transport-specific message
     */
    private Chunk toChunk(Message message) {
        //...
    }
}
```

It is imperative that all received chunks be stitched by the same Stitcher instance. The instance's `stitch` method
should be repeatedly called on every chunk. With each call, if the input chunk is the last expected piece chopped from
an original data unit, then the Stitcher returns a nonempty `Optional` containing the completely restored bytes of the
original data unit. Otherwise, if the input chunk is not the last one expected of its original data unit, then the
Stitcher keeps the chunk aside and returns an empty `Optional`, indicating no original data unit can yet be restored.

The `stitch` method will only return each restored data unit once. The API client should process or retain each returned
data unit as the Stitcher will not keep around any already-returned data unit.

The same Stitcher instance keeps/caches all the "pending" chunks received via the `stitch` method in different groups;
each group represents one original data unit. When an incoming chunk renders its own corresponding group "complete" -
that is, with that chunk, the group has gathered all the chunks of the original data unit, then

- The entire group of chunks is stitched to restore the original data bytes;
- The complete group of chunks is evicted from the Stitcher's cache;
- The restored bytes from the evicted group are returned in an `Optional` - nonempty, indicating the data contained
  inside is a complete restore of the original.

By default, a stitcher caches unlimited groups of pending chunks, and a pending group of chunks will never be discarded
no matter how much time has passed while awaiting all the chunks of the original data unit to arrive:

```jshelllanguage
new ChunkStitcher.Builder().build()
```

Both of those aspects, though, can be customized. The following stitcher will discard a group of chunks if 5 seconds of
time have passed since the stitcher was asked to stitch the very first chunk of the group, but hasn't received all the
chunks needed to restore the whole group back to the original data unit:

```jshelllanguage
new ChunkStitcher.Builder().maxStitchTime(Duration.ofSeonds(5)).build()
```

This stitcher will discard some group(s) of chunks when there are more than 100 groups of original data pending
restoration:

```jshelllanguage
new ChunkStitcher.Builder().maxStitchingGroups(100).build()
```

This stitcher is customized by a combination of both aspects:

```jshelllanguage
new ChunkStitcher.Builder().maxStitchTime(Duration.ofSeconds(5)).maxStitchingGroups(100).build()
```

## Hints on using chunk4j API in messaging

### Chunk size/capacity

Chunk4J works on the application layer of the network (Layer 7). There is a small fixed-size overhead in addition to
a chunk's byte size to serialize the entire Chunk object. Take all possible overheads into account when designing
to keep the **overall** message size under the transport limit.

### Message acknowledgment/commit

When working with a messaging provider, you want to acknowledge/commit all the messages of an entire group of chunks in
an all-or-nothing fashion, e.g. by using the individual and explicit commit mechanism. The all-or-nothing group commits
help ensure, in the case of producer/consumer node crash, no original data units get lost after node recovery. It may be
worthwhile to obtain/develop such mechanism when it is not directly available from the particular messaging provider.

If the all-or-nothing group commit is unavailable, then in case of a node crash, it is possible that the group loses
chunks. The loss of any chunks in a group semantically equates the loss of entire group, thus the original data unit. To
assess the potential "damage", you may need to ask in your system:

- At run-time, how often does an original data unit truly need more than one chunk to hold?
- How often does a node crash or go in and out of the system?
- What are the odds for those larger-than-one-chunk data units to be in transit at the same time of node crashes?
