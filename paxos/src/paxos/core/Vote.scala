package paxos.core

case class Vote[P <: Paxos](
    value: P#Value,
    ord: BallotOrdinal[P#Pid]
)
