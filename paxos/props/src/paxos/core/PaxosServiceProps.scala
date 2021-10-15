package paxos.core

import cats.implicits._
import cats.effect.Resource
import cats.effect.concurrent.Ref
import monix.eval.Task
import org.scalacheck._

object PaxosServiceProps extends Properties("PaxosService") with ArbitraryInstances {
  import TestPaxos.Pid
  import TestNode.{PaxosService, PaxosMessage}

  case class Env(
      services: Map[Pid, PaxosService[Task]],
      logRef: Ref[Task, Vector[String]]
  )

  def makeNetwork(size: Int): Resource[Task, Env] =
    for {
      peerRegistryRef <- Resource.eval(Ref[Task].of(Map.empty[Pid, Channel[Task, PaxosMessage]]))
      logRef          <- Resource.eval(Ref[Task].of(Vector.empty[String]))

      services <- (0 to size).toList.map(Pid(_)).traverse { pid =>
        // Annotate the logs with the PID
        implicit val tap = new Tap[Task] {
          override def apply(msg: String): Task[Unit] =
            logRef.update(_ :+ s"$pid: $msg")
        }
        PaxosService[Task](pid, peerRegistryRef).map(pid -> _)
      }

    } yield Env(services.toMap, logRef)
}
