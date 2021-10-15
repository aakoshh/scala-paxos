package paxos.core

/** Bring together all modules into one. The concrete application should create an object instance
  * of the node, which is going to be our "type bag" from which we can instantiate classes that all
  * share a single `p` value.
  *
  * In theory `P <: Paxos & Singleton` should work as well.
  */
abstract class Node[F[_], P <: Paxos]
    extends PaxosModule[P]
    with PaxosModels[P]
    with PaxosInstanceModule[P]
    with PaxosServiceModule[P] {}
