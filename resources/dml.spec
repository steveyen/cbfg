This dml.spec file covers logical items and operations, including
consistency/availability (CAP) concerns.

itemOperation
- headers
- op
- item

ops          | cluster | bucket | partition
- getCached  |         | item   | item
- get        |         | item   | item, tx
- set        |         | item   | item, tx, writeConcern
- del        |         | item   | item, tx, writeConcern
- merge      |         | item   | items, tx, writeConcern
- scan       |         | items  | items, tx
- changes    |         | y      |
- def        |         | y      | tx, writeConcern
- eval       |         | y      | tx, writeConcern
- mget       |         |        | itemHierarchy, tx
- mset       |         |        | itemHierarchy, tx, writeConcern
- mdel       |         |        | itemHierarchy, tx, writeConcern, cascadingDelete
- ddl        | y       | y      | tx, writeConcern
- conductor  | y       | y      |
- pillInject |         |        | tx, writeConcern
- logInject  | y       | y      | y
- fence      |         |        | y
- compactNow |         |        | y
- exec(task) |         |        | y
- pause(task)
- resume(task)
- splitPartition
- mergePartition
- set/getPartitionConfig (including range startInclusive/limitExclusive)
- set/getPartitionState
- set/getFilter
- set/getErrorExtra(CCCP)
- passOnwardsToReplicaX
- features
- stats
- purgeTombstones
- partitionMetaData
  - takeOverLog
- partitionMailBox
- partitionEphemeralBlackBoard
  - locks

requests to not affect cache if possible
- e.g., replication streams
- backfill on master
- changes injest on replica
- maybe put this in request header?

writeConcern
- N < R + W
- synchronous vs asynchronous XDCR

  errorResponseBody.errorCode
  - not my partition
    - extraBody includes cccpOpaque & engineVelocity
  - not my range
    - extraBody includes cccpOpaque & engineVelocity
  - temp OOM
    - extraBody includes engineVelocity
  - not caught up (e.g., index scan)
    - extraBody includes engineVelocity
  - not cached (getCached)
    - extraBody includes engineVelocity

  engineVelocity
  - queue in/out speeds and lengths
  -- basically, enough info so that client can calculate when it should retry
  --- and with some random client-side politeness jitter

  cccpOpaque probably defined in cdl.spec
  - this is the "fast forward map" of where the client should look

subItems
- modifying subItem does not change parent
-- unless a mset (changing parent and child in one ACID set)
-- beware that replication keeps the atomicity
- cascading deletes
- used for
-- transactions
-- attachments
-- revisionTrees
-- subItems
-- flexMetaData
- upward ver propagation

RYOW - read your own writes

item
- partitionId
- key
- revId
- cas?
- flags
- expiration
- valueDataType
- valueCompression
-- in-memory data compression
- value
- childItems (same partitionId as parent)
-- appMetaData (will this work?)
-- flexMetaData
-- txMetaData
-- revisionTree (will this work?)
-- attachments
-- items

