package paxos.core

trait Paxos {

  /** Process ID. Could be an int, or a network address. */
  type Pid

  /** Arbitrary values to reach consensus on. */
  type Value
}

// Scala 3 doesn't support type projections, an instance variable has to be provided,
// so I'm going to wrap everything in modules and mix them together into one object
// that has all case classes that would have been generic in `P <: Paxos` in Scala 2.
// https://contributors.scala-lang.org/t/proposal-to-remove-general-type-projection-from-the-language/2812/42
trait PaxosModule[P <: Paxos & Singleton] {

  // If `p` is a Singleton type then we should never need to actually access this instance
  // via `p`, it's here only to satisfy the compiler.
  protected val p: P = null.asInstanceOf[P]
}
