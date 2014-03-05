This edl.spec file ("engine definition language") covers engine
implementation including internal queues, stats, error conditions.

- statsAggregator
- partitionMapValidator
-- detects unbalancedness and not enough replicas
- partitionMapPlanner
- partitionMapScheduler
-- max scanners per node
-- max movers per pair of nodes
- partitionMapMover
- n2nr
-- ongoing streams
-- takeover streams
- xdcr
- dmlEngine
-- n1ql
-- hashJoiner
-- mapper
- backIndex
- indexMaintainer
- index
- view
- fullText
- backup
- directFileCopier
- autoFailOverer
- doctor
-- heartBeat
-- nodeHealth
- healthChecker
- integrations
-- hadoop
-- elasticSearch

each service...
- short label
- needs storage?
-- ephemeral storage
-- permanent storage
- needs partitionMap
-- reads partitionMap
-- writes partitionMap
- limits  (per cluster, per node,  per bucket, per partition)...
-- memory
-- storage
-- processors

engineProcess
  conn*
  ePool*
    eBucket*
      modelBucket
      acl*
      eColl*
        for: primary, secondaryIndex, system
        ePartition*
          item*
          change*
          takeOverLog
          partitionId
          rangeStartInclusive (bottom)
          rangeLimitExclusive (top)
          state: master, replica, pending, dead
          readiness: warming, cooling, running, stopped
          mailBox
          ephemeral
            subscribers (with filters (keyOnly, matchKeyExpr, matchValueExpr))
            partition assignment to processor (mem quota)
            partition assignment to storage (storage quota)
  storage
    scanner*
    snapshot*
      snapshotScanner*
  volume*
    type: ephemeral, permanent
    kind: ssd, hdd
    fsVolume* (directory)
  task*
    storageScanner
       (current num and max num of these per node, per volume;
        state: working/running vs stopped)
      compactor / expirer / tombstonePurger
      backFiller
    memScanner
      evictor, expirer
    inPlaceBackUpper / directFileCopier (after compaction)
  stream*
    replicaStream, changeStream, persister
  memAllocator
  stats
  - startTime
  - currTime
  - upTime is calculated by tool
  - aggregates calculated elsewhere?
  - speed in/out and current queue lengths tracked by node

conn
  channel*

eMemoryItem ephemeral
- dirty
-- not persisted (persistenceConcern)
-- not replicated (replicationConcern)
- cached
- deleted
- hotness (LRU, NRU)

ePersistItem

script building blocks / lua
- lua contexts
- SHAs of registered, available functions
- node-local processors vs further away cluster-local processors

storage error cases
- out of space
- checksum problem
- storage inaccessible

thread
- conn affinity
- partition affinity
- partition slice affinity (concurrent hashmap)
- NUMA affinity?

indexDB key layout...
- hash  partitionId | key . subKey (m|v)
- range partitionId | key . subKey (m|v)
- index usage - emitKey . docId . partitionId
- changeStream - partitionId | seqNum | key . subKey

pill injection
- respond only when received
- respond only when authed/acl-checked
- respond only when queued
- respond only when persisted
- respond only when replicated
- respond only when indexed
- but, do not do real update?
- or, do real update in scratch area

snapshot
- queue dedupe until there is a snapshot
-- persist dedupe
-- replica dedupe
-- read dedupe
--- snapshot reads

transactions
- locking (lease) / lock table?
-- lock per ePartition
-- lock per eCollection
-- lock per eBucket

tombstone purge
- tombstone purging based on time
- allow UPR clients to "lease" their high-watermark of
  tombstone-purgable seq-num.
- if tombstone purger got ahead of UPR client that went
  away for a long time, that UPR client needs to start
  from seq num 0 to avoid missing deletions
- highwatermark registrations need to be replicated.
- tombstone purger on replica should not move
  faster than tombstone purger on master.

eMemoryPartition
ePersistedPartition

eMemoryCollection
ePersistedCollection

scenarios
- os/memory swap
- node slowness
- storage slowness
- restart a datacenter
- restart a cluster
- restart a node
- restart a process
- restart a network hop

writeBatch
- max num items
- max num bytes
- max time

dedupe
- max dirty age
- max dedup count

on incomingReq as r
   P = myPartitions.get(r.partitionId)

lojoin [incomingReqs, join [myPartitions, allowsOpsByPartitionState]
                        on partitionState]
    on [partitionId, reqNum]
 split on partitionUUID is not null
       into myPartitionRequests else notMyPartitionRequests

onMessageReceived{Msg}
  Req    = must isRequest(Msg)
  OpCode = must knownImplementedOpCode(Req)
  User   = must knownUser(Req)

  if OpCode is CHANNEL_LEVEL
     return handleChannelLevel(Req, User)
  Channel = must knownAllowedChannel(Req, User)

  if OpCode is BUCKET_LEVEL
    return handleBucketLevel(Req, User)
  Bucket = must knownAllowedBucket(Req, User)

  if OpCode is COLLECTION_LEVEL
    return handleCollectionLevel(Req, User)
  Collection = must knownAllowedCollection(Req, User)

  if OpCode is PARTITION_LEVEL
    return handlePartitionLevel(Req, User)
  Partition = must knownAllowedPartition(Req, User)

  return handlePartitionReq(Req, User, Bucket, Collection, Partition)

handling higher level admin changes
- easy answer: lock (global?)
- setup new datastructures / immutable
- CAS-like swap

ops
- getCached
- get
- set
- del
- merge
- scan
- changes
- def
- eval
- mget
- mset
- mdel
- ddl
- conductor
- pillInject
- logInject
- compactNow
- exec(task)
- pause(task)
- resume(task)
- splitPartition
- mergePartition
- set/getPartitionConfig (including range)
- set/getPartitionState
- set/getFilter
- set/getErrorExtra(CCCP)
- passOnwardsToReplicaX

partitionStorageAPI
       level(cacheOnly?)
- adminCmds (config, create, delete, validate, list,
             compact, purge, stats, pause/resume,
             get/set ephemeral memo)
- get
- getMulti
- set
- del
- scan
- merge
- writeBatch
- registerForChanges

filtered replication streams

how is backFill handled?
- with slow acks due to slow consumer?
- request for changesStream or scan comes in.
- request registered on partitionStorage
  - callback places data on reply channel
  - reply channel consumed by sender
  - sender send()s N messages before injecting / sending
    a READY_FOR_MORE response
  - client eventually sends a READY quiet request or any other
    request on the original channel
    which allows sender to keep going on that channel
  - if reply channel is full, then scan() pauses until
    reply channel has more space
  - if reply channel is too full for too long (timeout)
    then scan is stopped and reply channel gets an error msgs
