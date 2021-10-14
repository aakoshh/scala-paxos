package paxos.core

trait Paxos {

  /** Process ID. Could be an int, or a network address. */
  type Pid

  /** Arbitrary values to reach consensus on. */
  type Value
}
