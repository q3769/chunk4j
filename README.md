[![Maven Central](https://img.shields.io/maven-central/v/io.github.q3769/chunk4j.svg?label=Maven%20Central)](https://search.maven.org/search?q=g:%22io.github.q3769%22%20AND%20a:%22chunk4j%22)

# chunk4j

A Java API to chop up larger data blobs into smaller "chunks" of a pre-defined size, and stitch the chunks back together
to restore the original data when needed.

## User story

As a user of the chunk4j API, I want to chop a data blob (bytes) into smaller pieces of a pre-defined size and, when
needed, restore the original data by stitching the pieces back together.

Notes:

- The separate processes of "chop" and "stitch" often need to happen on different network compute nodes; and the
  chunks are transported between the nodes in a possibly random order.
- The chunk4j API comes in handy when, at run-time, you may have to ship larger sized data entries than what is allowed
  by the underlying transport in a distributed system. E.g. at the time of writing, the default message size limit is
  256KB with [Amazon Simple Queue Service (SQS)](https://aws.amazon.com/sqs/) , and 1MB
  with [Apache Kafka](https://kafka.apache.org/); the default cache entry size limit is 1MB
  for [Memcached](https://memcached.org/), and 512MB for [Redis](https://redis.io/). Although the default limits can be
  customized, often times, the default is there for a sensible reason. Meanwhile, it may be difficult to predict or
  ensure that the size of a data entry being transported at run-time will, by its business nature, never go beyond the
  default or customized limit.

## Prerequisite

* Java 8+ for versions earlier than 20250321.0.0 (exclusive)
* Java 21+ for versions later than 20250321.0.0 (inclusive)

## Get it...

[![Maven Central](https://img.shields.io/maven-central/v/io.github.q3769/chunk4j.svg?label=Maven%20Central)](https://search.maven.org/search?q=g:%22io.github.q3769%22%20AND%20a:%22chunk4j%22)

Install as a compile-scope dependency in Maven or other build tools alike.

## Use it...

- The implementation of chunk4j API is thread-safe.

### The Chopper

#### API:

```java

@FunctionalInterface
public interface Chopper {

  /**
   * Chops a byte array into a list of Chunk objects. Each Chunk object represents a portion of the original data
   * blob. The size of each portion is determined by a pre-configured maximum size (the Chunk's capacity). If the size
   * of the original data blob is smaller or equal to the Chunk's capacity, the returned list will contain only one
   * Chunk.
   *
   * @param bytes the original data blob to be chopped into chunks
   * @return the group of chunks which the original data blob is chopped into.
   */
  List<Chunk> chop(byte[] bytes);
}
```

A larger blob of data can be chopped up into smaller "chunks" to form a "group". When needed, often on a different
network node, the group of chunks can be collectively stitched back together to restore the original data.

#### Usage example:

```java
import chunk4j.Chunk;

public class MessageProducer {

  private final Chopper chopper = ChunkChopper.ofByteSize(1024); // each chopped off chunk holds up to 1024 bytes

  @Autowired
  private MessagingTransport transport;

  /**
   * Sender method of business data
   */
  public void sendBusinessDomainData(String domainDataText) {
    chopper.chop(domainDataText.getBytes()).forEach((chunk) -> transport.send(chunkToMessage(chunk)));
  }

  /**
   * pack/serialize/marshal the chunk POJO into a transport-specific message
   */
  private Message chunkToMessage(Chunk chunk) {
    //...
  }
}
```

On the `Chopper` side, you only have to say how big you want the chunks chopped up to be. The chopper will internally
divide up the original data bytes based on the chunk size you specified, and assign a unique group ID to all the chunks
in the same group representing the original data unit.

### The Chunk

#### API:

```java
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
 */
@Builder
public record Chunk(@NonNull UUID groupId, int orderIndex, int groupSize, byte @NonNull [] bytes)
    implements Serializable {

  @Serial
  private static final long serialVersionUID = -1879320933982945956L;
}
```

#### Usage example:

`Chunk` is a simple POJO data holder, carrying a portion of the original data bytes from the `Chopper` to
the `Stitcher`. It is marked as a JDK `java.io.Serializable`. To transport a Chunk over the network, the API client is
expected to package the serialized byte array of the entire Chunk instance into a transport-specific (JSON, Kafka,
JMS, ...) message on the Chopper's end (as in `MessageProducer#chunkToMessage` above), and unpack the bytes back to a
Chunk instance on the Stitcher's end (as in `MessageConsumer#messageToChunk` below). chunk4j will handle the rest of the
data assembly details.

### The Stitcher

#### API:

```java

@FunctionalInterface
public interface Stitcher {

  /**
   * Adds a chunk to its corresponding chunk group. If the chunk is the last one expected by the group, the original
   * data bytes are restored and returned. Otherwise, the chunk group is kept around, waiting for the missing chunk(s)
   * to arrive.
   *
   * @param chunk The chunk to be added to its corresponding chunk group.
   * @return An Optional containing the original data bytes if the chunk is the last one expected by the group, or an
   *     empty Optional otherwise.
   */
  Optional<byte[]> stitch(Chunk chunk);
}

```

On the stitcher side, a group must gather all the previously chopped chunks before the original data blob represented by
this group can be stitched back together and restored.

#### Usage example:

```java
public class MessageConsumer {

  private final Stitcher stitcher = new ChunkStitcher.Builder().build();

  @Autowried
  private DomainDataProcessor domainDataProcessor;

  /**
   * Suppose the run-time invocation of this method is managed by messaging provider/transport
   */
  public void onReceiving(Message message) {
    stitcher.stitch(messageToChunk(message))
        .ifPresent(originalDomainDataBytes -> domainDataProcessor.process(new String(originalDomainDataBytes)));
  }

  /**
   * unpack/deserialize/unmarshal the chunk POJO from the transport-specific message
   */
  private Chunk messageToChunk(Message message) {
    //...
  }
}
```

It is imperative that all received chunks be stitched by the same Stitcher instance. The instance's `stitch` method
should be repeatedly called on every chunk. With each call, if the input chunk is the last expected piece chopped from
an original data unit, then the Stitcher returns a non-empty `Optional` containing the completely restored bytes of the
original data unit. Otherwise, if the input chunk is not the last one expected of its original data unit, then the
Stitcher keeps the chunk aside and returns an empty `Optional`, indicating no original data unit can yet be restored.

The `stitch` method will only return each restored data unit once. The API client should process or retain each returned
data unit as the Stitcher will not keep around any already-returned data unit.

The same Stitcher instance keeps/caches all the "pending" chunks received via the `stitch` method in different groups;
each group represents one original data unit. When an incoming chunk renders its own corresponding group "complete" -
that is, with that chunk, the group has gathered all the chunks of the original data unit, then

- The entire group of chunks is stitched to restore the original data bytes;
- The complete group of chunks is evicted from the Stitcher's cache;
- The restored bytes from the evicted group are returned in an `Optional` that is non-empty, indicating the data
  contained inside is a complete restore of the original.

By default, a stitcher caches unlimited groups of pending chunks, and a pending group of chunks will never be discarded
no matter how much time has passed while awaiting all the chunks of the original data unit to arrive:

```jshelllanguage
new ChunkStitcher.Builder().build()
```

Both of those aspects, though, can be customized.

The following stitcher will discard a group of chunks if 5 seconds of
time have passed since the stitcher was asked to stitch the very first chunk of the group, but hasn't received all the
chunks needed to restore the whole group back to the original data unit:

```jshelllanguage
new ChunkStitcher.Builder().maxStitchTime(Duration.ofSeonds(5)).build()
```

The following stitcher will discard some group(s) of chunks when there are more than 100 groups of original data pending
restoration:

```jshelllanguage
new ChunkStitcher.Builder().maxStitchingGroups(100).build()
```

The following stitcher is customized by a combination of both aspects:

```jshelllanguage
new ChunkStitcher.Builder().maxStitchTime(Duration.ofSeconds(5)).maxStitchingGroups(100).build()
```

### Hints on using chunk4j API in messaging

#### Chunk size/capacity

chunk4j works on the application layer of the network (Layer 7). There is a small fixed-size overhead in addition to
a chunk's byte size to serialize the entire Chunk object. Take all possible overheads into account when designing
to keep the **overall** message size under the transport limit.

#### Message acknowledgment/commit

When working with a messaging provider, you want to (at least semantically) acknowledge/commit all the messages of an
entire group of chunks in an all-or-nothing fashion. Otherwise, in case of a consumer node crash, it is possible that
the group loses chunks. The loss of any chunk in a group semantically equates the loss of the entire group, thus the
whole original data unit.

Usually, data integrity is not an issue with any messaging provider that supports the at-least-once delivery guarantee
to the message consumer. The Stitcher of chunk4j is tolerant and will seamlessly discard repeatedly delivered chunks. As
long as there is no loss of chunks per the messaging provider guarantee, the overall data integrity is assured by the
chunk4j consumer.

However, because the data stored by the chunk4j Stitcher at runtime is in memory-only and not persistent, the Stitcher
will lose all the incomplete groups of chunks if the consumer node crashes. If that is a situation having to be avoided
or recovered from, the options include:

* **The Preventative Approach**: Use a third-party API or implement a custom mechanism that works with your specific
  messaging provider to achieve the all-or-nothing acknowledgment/commit semantics for the group. For example, in case
  of the Apache Kafka messaging provider, this is trivial to implement with the manual sync commit mode (and optionally
  the support of transactional producer/consumer API and/or Spring Kafka). In case of TIBCO EMS, the support of
  individual explicit message acknowledgment can make this even more seamless.
* **The Curative Approach**: Make the loss of original data units detectable and recoverable by the application logic.
  For example, implement a mechanism to detect the loss of an original data unit (e.g. via timeout checks), and re-send
  the data unit (missing chunks) from the producer.