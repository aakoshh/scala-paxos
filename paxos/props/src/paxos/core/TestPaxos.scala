package paxos.core

import org.scalacheck._

object TestPaxos extends Paxos {

  object Pid extends Tagger[Int] {
    implicit val zero: Zero[Pid] =
      Zero.instance(Pid(0))

    implicit val arb: Arbitrary[Pid] =
      Arbitrary(Gen.posNum[Int].map(Pid(_)))
  }
  type Pid = Pid.Tagged

  type Value = String
}
