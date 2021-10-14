package paxos.core

import org.scalacheck._, Arbitrary.arbitrary

trait ArbitraryInstances {
  implicit def arbBallotOrdinal[P: Arbitrary]: Arbitrary[BallotOrdinal[P]] = Arbitrary {
    for {
      pid   <- arbitrary[P]
      round <- genRound
    } yield BallotOrdinal(pid, round)
  }

  val genRound = Gen.posNum[Int].map(_ - 1) // non-negative
}
