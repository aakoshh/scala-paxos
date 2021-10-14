package paxos.core

import org.scalacheck._, Arbitrary.arbitrary
import org.scalacheck.Prop.{forAll, all}
import scala.math.Ordering.Implicits._
import scala.math.{min, max}

object BallotOrdinalProps extends Properties("BallotOrdinal") with ArbitraryInstances {
  import TestPaxos.Pid

  property("∀ (ord ∈ BallotOrdinal) : ballot-ordinal-zero ≤ ord") = forAll {
    (ord: BallotOrdinal[Pid]) =>
      Zero[BallotOrdinal[Pid]].zero <= ord
  }

  property("< with different rounds") = forAll(for {
    p1 <- arbitrary[Pid]
    p2 <- arbitrary[Pid]
    r1 <- genRound
    r2 <- genRound.suchThat(_ != r1)
    balH = BallotOrdinal(p1, max(r1, r2))
    balL = BallotOrdinal(p2, min(r1, r2))
  } yield (balH, balL)) { case (balH, balL) =>
    !(balH < balL) && (balL < balH)
  }

  property("< with equal rounds") = forAll(for {
    p1 <- arbitrary[Pid]
    p2 <- arbitrary[Pid].suchThat(_ != p1)
    r  <- genRound
    balH = BallotOrdinal(Pid(max(p1, p2)), r)
    balL = BallotOrdinal(Pid(min(p1, p2)), r)
  } yield (balH, balL)) { case (balH, balL) =>
    !(balH < balL) && (balL < balH)
  }
}
