package paxos.core

object PaxosMessage {

  sealed trait Detail[+P <: Paxos]

  /** 1a message. */
  case object Prepare extends Detail[Nothing]

  /** 1b message. */
  case class Promise[P <: Paxos](vote: Option[Vote[P]]) extends Detail[P]

  /** 2a message. */
  case class Propose[P <: Paxos](value: P#Value) extends Detail[P]

  /** 2b message. */
  case class Accept[P <: Paxos](value: P#Value) extends Detail[P]
}

case class PaxosMessage[P <: Paxos](
    src: P#Pid,
    instanceId: InstanceId,
    members: Set[P#Pid],
    ballotOrdinal: BallotOrdinal[P#Pid],
    detail: PaxosMessage.Detail[P]
)
