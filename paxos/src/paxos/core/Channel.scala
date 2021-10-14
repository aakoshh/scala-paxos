package paxos.core

import cats.implicits._
import cats.effect.{ContextShift, Concurrent}
import monix.catnap.ConcurrentQueue

trait Channel[F[_], M] extends Channel.Reader[F, M] with Channel.Writer[F, M]

object Channel {
  trait Writer[F[_], M] {
    def send(msg: M): F[Unit]
  }
  trait Reader[F[_], M] {
    def next: F[M]
  }
  def apply[F[_]: Concurrent: ContextShift, M]: F[Channel[F, M]] = {
    ConcurrentQueue.unbounded[F, M](None).map { queue =>
      new Channel[F, M] {
        override def send(msg: M): F[Unit] = queue.offer(msg)
        override def next: F[M]            = queue.poll
      }
    }
  }
}
