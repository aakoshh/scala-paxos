package paxos.core

object `package` {
  object InstanceId extends Tagger[Long]
  type InstanceId = InstanceId.Tagged
}
