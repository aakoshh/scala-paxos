package paxos.core

trait PaxosModels[P <: Paxos & Singleton] extends PaxosModule[P] {
  case class ClientRequest(
      instanceId: InstanceId,
      members: Set[p.Pid],
      value: p.Value
  )

  case class Vote(
      value: p.Value,
      ord: BallotOrdinal[p.Pid]
  )

  object PaxosMessage {

    sealed trait Detail

    /** 1a message. */
    case object Prepare extends Detail

    /** 1b message. */
    case class Promise(vote: Option[Vote]) extends Detail

    /** 2a message. */
    case class Propose(value: p.Value) extends Detail

    /** 2b message. */
    case class Accept(value: p.Value) extends Detail
  }

  case class PaxosMessage(
      src: p.Pid,
      instanceId: InstanceId,
      members: Set[p.Pid],
      ballotOrdinal: BallotOrdinal[p.Pid],
      detail: PaxosMessage.Detail
  )
}
