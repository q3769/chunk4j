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
- The chunk4j API comes in handy when, occasionally at run-time, you have to handle larger sized business domain data
  entries than what is allowed/configured by the underlying transport. As background information e.g. at the time of
  writing, the default message size limit is 256KB with [Amazon Simple Queue Service (SQS)](https://aws.amazon.com/sqs/)
  , and 1MB with [Apache Kafka](https://kafka.apache.org/); the default cache entry size limit is 1MB
  for [Memcached](https://memcached.org/), and 512MB for [Redis](https://redis.io/). Those default transport limits can
  be customized but, often times, the default is there for a sensible reason.

## Prerequisite

Java 8 or better

## Get it...

In Maven

```
<dependency>
    <groupId>io.github.q3769</groupId>
    <artifactId>chunk4j</artifactId>
    <version>20220116.0.5</version>
</dependency>
```

In Gradle

```
implementation 'io.github.q3769:chunk4j:20220116.0.5'
```

## Use it...

As a generic POJO API, chunk4j can be directly used in any Java client codebase. For better encapsulation, though,
consider using chunk4j as a "lower level API". I.e. set up some simple wrapper/adapter mechanism such that the
end-client codebase can be completely agnostic of the transport size limitation or the chunk4j API. Working with the
higher-level wrapper interface, the end-client code only needs to be concerned with the original client-side business
domain data (bytes or domain objects serializable to bytes).

### The Chopper

#### API:

```
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

#### Usage example:

```
public class MessageProducer {

    priviate Chopper chopper = ChunkChopper.ofChunkByteSize(1024); // each chopped off chunk holds up to 1024 bytes
    prviate MessagingTransport transport = ...;
    
    ...

    /**
     * Sender method of business data
     */
    public void sendEndClientBusinessDomainData(String domainDataText) {
        List<Chunk> chunks = this.chopper.chop(domainDataText.getBytes());
        this.transport.sendAll(toMessages(chunks));
    }

    private List<Message> toMessages(List<Chunk> chunks) {
        // pack the input group of chunks 1:1 into transport messages, 
        // each message carries a single chunk of data.
        ...
    }
    ...
}
```

On the `Chopper` side, you only have to say how big you want the chunks chopped up to be. The chopper will internally
divide up the original data bytes based on the chunk size you specified, and assign a unique group ID to all the chunks
in the same group representing the original data unit.

### The Chunk

#### API:

```
public class Chunk implements Serializable {

    private static final long serialVersionUID = 0L;

    /**
     * Maximum bytes of data a chunk can hold.
     */
    int byteCapacity;

    /**
     * The group ID of the original data blob. All chunks in the same group share the same group ID.
     */
    @EqualsAndHashCode.Include
    UUID groupId;

    /**
     * Ordered index at which this current chunk is positioned inside the group. Chunks are chopped off from the
     * original data bytes in sequential order, indexed as such, and assigned with the same group ID as all other chunks
     * in the group that represents the original data bytes.
     */
    @EqualsAndHashCode.Include
    int index;

    /**
     * Total number of chunks the original data blob is chopped to form the group.
     */
    int groupSize;

    /**
     * Data bytes chopped for this current chunk to hold. Every chunk in the group should hold bytes of size equal to
     * the chunk's full capacity except maybe the last one in the group.
     */
    byte[] bytes;
}
```

#### Usage example:

Normally, you don't need to interact with the `Chunk`'s API methods; those details are already handled behind the scenes
of the `Chopper` and `Stitcher` API. It suffices to know that `Chunk` is a simple POJO data holder; serializable, it
carries the data bytes travelling from the `Chopper` to the `Stitcher`.

### The Stitcher

#### API:

```
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
```

On the stitcher side, a group has to gather all the previously chopped chunks before the original data blob represented
by this group can be stitched together and restored.

#### Usage example:

```
public class MessageConsumer {

    private Stitcher stitcher = new ChunkStitcher.Builder().build();    
    
    @Autowried
    private EndClientBusinessDomainDataProcessor endClientBusinessDomainDataProcessor;
        
        ...
        
    /**
     * Suppose the run-time invocation of this method is managed by messaging provider/transport
     */
    public void onReceiving(Message message) {
        final Optional<byte[]> stitchedBytes = this.stitcher.stitch(getChunk(message));
        stitchedBytes.ifPresent(originalTextDomainDataBytes -> 
                this.endClientBusinessDomainDataProcessor.process(new String(originalTextDomainDataBytes));
    } 
    
    private Chunk getChunk(Message message) {
        // parse out the chunk carried by the message
    }
    ...
}
```

The `stitch` method should be repeatedly called on all the chunks ever received. On each call and addition of the
received chunk, if a meaningful group can form to complete and restore the original data blob in bytes, such bytes are
returned inside an `Optional`; otherwise if the group is still "incomplete" even with the addition of this current
chunk, then the `stitch` method returns an empty `Optional`. I.e. You keep calling the `stitch` method with each and
every chunk you received; you'll know you get a fully restored original data blob when the method returns a
non-empty `Optional` that contains the restored data bytes.

Note that the stitcher caches all "pending" chunks it has received via the `stitch` method in different groups, each
group representing one original data unit. When an incoming chunk renders its own corresponding group "complete" - i.e.
the group has gathered all the chunks needed to restore the whole group of chunks back to the original data unit - then
such group of chunks are stitched back together for original data restoration. As soon as the original data unit is
restored and returned by the `stitch` method, the entire chunk group is evicted from the cache.

By default, a stitcher caches unbounded groups of pending chunks, and a pending group of chunks will never be discarded
no matter how much time has passed without being able to restore the group back to the original data unit:

```
new ChunkStitcher.Builder().build()
```

Both of those aspects, though, can be customized. The following stitcher will discard a group of chunks if 2 seconds of
time have passed since the stitcher was asked to stitch the very first chunk of the group, but hasn't received all the
chunks needed to restore the whole group back to the original data unit:

```
new ChunkStitcher.Builder().maxStitchTimeMillis(2000).build()
```

This stitcher will discard some group(s) of chunks when there are more than 100 groups of original data pending
restoration:

```
new ChunkStitcher.Builder().maxGroups(100).build()
```

This stitcher is customized by a combination of both aspects:

```
new ChunkStitcher.Builder().maxStitchTimeMillis(2000).maxGroups(100).build()
```

### Hints on using chunk4j API in messaging

#### Chunk size/capacity

When deciding the chunk's capacity, it may be beneficial to use the chop-n-stitch mechanism as a safety measure rather
than the regular mode of delivery at run-time: "One ounce of prevention" may still be "worth a pound of cure". On the
one hand, the chunk's capacity will need to be under the transport message size limit. On the other hand, try setting up
the application and chunk capacity such that most domain data can be fit and transported in one single chunk/message,
and the chop-n-stitch mechanism only needs to kick in when, occasionally, the domain data entry does go beyond the
chunk's capacity.

*Note: chunk4j works on the application layer of the network (Layer 7). There is a fixed-size overhead between a chunk's
byte size capacity (`Chunk.getByteCapacity()`) and the overall size of the entire chunk that is transported by a
message. Take that and all other overheads into account when designing to keep the **overall** message size under the
transport limit.*

#### Message acknowledgment/commit

When working with a messaging provider, you want to acknowledge/commit all the messages of an entire group of chunks in
an all-or-nothing fashion, e.g. by using the individual and explicit commit mechanism. The all-or-nothing group commits
help ensure, in the case of producer/consumer node crash, no original data units get lost after node recovery.

If, however, the particular messaging provider lacks such mechanism that enables the all-or-nothing commit, then in case
of node crash, it is possible that the original data unit whose group of chunks gets "cut off in the middle" at the time
of crash will be lost in transportation. All the chunks of a group form a "session" that represents the original data
unit. Loss of such sessions is similar to the case where active web sessions get lost when a stateful web application
node hosting those sessions fails. To assess the potential "damage", you may need to ask in your system:

- At run-time, how often does an original data unit truly need more than one chunk to hold?
- How often does a node crash or go in and out of the system?
- What are the odds for those larger-than-one-chunk data units to be in transit at the same time of node crashes?
