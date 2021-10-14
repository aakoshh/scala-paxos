package paxos.core

import scala.math.Ordering, Ordering.Implicits._

case class BallotOrdinal[P](
    pid: P,
    round: Int
) {
  def incr(pid: P): BallotOrdinal[P] =
    BallotOrdinal(pid, round = round + 1)
}

object BallotOrdinal {
  implicit def zero[P: Zero]: Zero[BallotOrdinal[P]] =
    Zero.instance {
      BallotOrdinal(pid = Zero[P].zero, round = 0)
    }

  implicit def ordering[P: Ordering]: Ordering[BallotOrdinal[P]] =
    Ordering.fromLessThan { (lhs, rhs) =>
      lhs.round < rhs.round || lhs.round == rhs.round && lhs.pid < rhs.pid
    }
}
