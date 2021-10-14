package paxos.core

case class ClientRequest[P <: Paxos](
    instanceId: InstanceId,
    members: Set[P#Pid],
    value: P#Value
)
