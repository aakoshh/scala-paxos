package paxos.core

import cats._
import cats.implicits._
import cats.effect.{Sync, Concurrent, ContextShift, Resource}
import cats.effect.concurrent.{Ref, Deferred}
import monix.catnap.ConcurrentQueue
import scala.math.Ordering.Implicits._

class PaxosService[F[_]: Concurrent: Tap, P <: Paxos](
    myPid: P#Pid,
    writer: P#Pid => F[Option[Channel.Writer[F, PaxosMessage[P]]]],
    reader: Channel.Reader[F, PaxosMessage[P]],
    requestQueue: ConcurrentQueue[F, ClientRequest[P]],
    instancesRef: Ref[F, Map[InstanceId, PaxosInstance[P]]],
    resultsRef: Ref[F, Map[InstanceId, Deferred[F, P#Value]]]
)(implicit ev1: Ordering[P#Pid], ev2: Zero[P#Pid]) {

  def clientRequest(
      req: ClientRequest[P]
  ): F[P#Value] =
    for {
      _      <- requestQueue.offer(req)
      result <- getResult(req.instanceId)
      value  <- result.get
    } yield value

  private def handleEvent: F[Unit] =
    Concurrent[F]
      .race(
        requestQueue.poll,
        reader.next
      ) >>= {
      case Left(req)  => handleRequest(req)
      case Right(msg) => handleMessage(msg)
    }

  private def handleRequest(
      req: ClientRequest[P]
  ): F[Unit] =
    getInstance(req.instanceId, req.members) >>= { inst =>
      inst.doPrepare(req.value) match {
        case (next, effects, ()) =>
          updateAndExecute(next, effects)
      }
    }

  private def handleMessage(
      msg: PaxosMessage[P]
  ): F[Unit] =
    getInstance(msg.instanceId, msg.members) >>= { inst =>
      inst.handleMessage(msg) match {
        case Left(err) =>
          Tap[F](s"error handling message $msg: $err")

        case Right((next, effects, maybeDecidedValue)) =>
          val maybeComplete =
            maybeDecidedValue.fold(().pure[F]) { value =>
              getResult(next.id) >>= (_.complete(value))
            }

          maybeComplete >> updateAndExecute(next, effects)
      }
    }

  private def updateAndExecute(
      next: PaxosInstance[P],
      effects: Seq[Effect[P]]
  ): F[Unit] =
    instancesRef.update {
      _.updated(next.id, next)
    } >>
      effects.toList.traverse { effect =>
        Concurrent[F].start(handleEffect(effect))
      }.void

  private def handleEffect(
      effect: Effect[P]
  ): F[Unit] = effect match {
    case Effect.Broadcast(msg) =>
      msg.members.toList.traverse { pid =>
        writer(pid) flatMap {
          case Some(chan) => chan.send(msg)
          case None       => Tap[F](s"unable to locate channel for dest $pid")
        }
      }.void

    case Effect.Unicast(pid, msg) =>
      writer(pid) flatMap {
        case Some(chan) => chan.send(msg)
        case None       => Tap[F](s"unable to locate channel for dest $pid")
      }
  }

  private def getResult(id: InstanceId) =
    for {
      d <- Deferred[F, P#Value]
      r <- resultsRef.modify { results =>
        if (results.contains(id))
          (results, results(id))
        else
          (results.updated(id, d), d)
      }
    } yield r

  private def getInstance(id: InstanceId, members: Set[P#Pid]) =
    instancesRef.get.map { instances =>
      instances.getOrElse(id, PaxosInstance[P](id, myPid, members))
    }
}

object PaxosService {

  def apply[F[_]: Concurrent: ContextShift: Tap, P <: Paxos](
      myPid: P#Pid,
      peerRegistryRef: Ref[F, Map[P#Pid, Channel[F, PaxosMessage[P]]]]
  )(implicit ev1: Ordering[P#Pid], ev2: Zero[P#Pid]): Resource[F, PaxosService[F, P]] = {
    // 1. create channel
    // 2. add channel to the registry
    // 3. create service
    // 4. start handling message in the background
    // 5. on release, remove the channel
    val res = Resource.make(
      for {
        channel <- Channel[F, PaxosMessage[P]]

        _ <- peerRegistryRef.update { peerRegistry =>
          assert(!peerRegistry.contains(myPid))
          peerRegistry.updated(myPid, channel)
        }

        instancesRef <- Ref[F].of(Map.empty[InstanceId, PaxosInstance[P]])
        resultsRef   <- Ref[F].of(Map.empty[InstanceId, Deferred[F, P#Value]])

        requestQueue <- ConcurrentQueue.unbounded[F, ClientRequest[P]](None)

        service = new PaxosService[F, P](
          myPid,
          writer = pid => peerRegistryRef.get.map(_.get(pid)),
          reader = channel,
          requestQueue,
          instancesRef,
          resultsRef
        )
      } yield (service, channel)
    ) { _ =>
      peerRegistryRef.update(_ - myPid)
    }

    for {
      (service, channel) <- res
      _ <- Concurrent[F].background {
        service.handleEvent.foreverM.void
      }
    } yield service
  }
}
