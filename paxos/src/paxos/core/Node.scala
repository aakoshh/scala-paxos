package paxos.core

/** Bring together all modules into one. The concrete application should create an object instance
  * of the node, which is going to be our "type bag" from which we can instantiate classes that all
  * share a single `p` value.
  */
class Node[F[_], P <: Paxos & Singleton]
    extends PaxosModels[P]
    with PaxosInstanceModule[P]
    with PaxosServiceModule[P] {}
