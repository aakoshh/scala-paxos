package paxos.core

sealed trait Effect[P <: Paxos]

object Effect {

  case class Broadcast[P <: Paxos](
      msg: PaxosMessage[P]
  ) extends Effect[P]

  case class Unicast[P <: Paxos](
      to: P#Pid,
      msg: PaxosMessage[P]
  ) extends Effect[P]
}
