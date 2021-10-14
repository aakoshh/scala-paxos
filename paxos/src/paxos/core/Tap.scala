package paxos.core

/** Used for error logging. */
trait Tap[F[_]] {
  def apply(msg: String): F[Unit]
}

object Tap {
  def apply[F[_]](msg: String)(implicit ev: Tap[F]) = ev(msg)
}
