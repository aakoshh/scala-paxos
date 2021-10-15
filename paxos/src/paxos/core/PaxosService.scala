package paxos.core

import cats._
import cats.implicits._
import cats.effect.{Sync, Concurrent, ContextShift, Resource}
import cats.effect.concurrent.{Ref, Deferred}
import monix.catnap.ConcurrentQueue
import scala.math.Ordering.Implicits._

trait PaxosServiceModule[P <: Paxos] {
  self: PaxosModule[P] with PaxosModels[P] with PaxosInstanceModule[P] =>

  class PaxosService[F[_]: Concurrent: Tap](
      myPid: p.Pid,
      writer: p.Pid => F[Option[Channel.Writer[F, PaxosMessage]]],
      reader: Channel.Reader[F, PaxosMessage],
      requestQueue: ConcurrentQueue[F, ClientRequest],
      instancesRef: Ref[F, Map[InstanceId, PaxosInstance]],
      resultsRef: Ref[F, Map[InstanceId, Deferred[F, p.Value]]]
  )(implicit ev1: Ordering[p.Pid], ev2: Zero[p.Pid])
      extends PaxosInstance.Ops {

    override implicit val pidOrd = ev1

    def clientRequest(
        req: ClientRequest
    ): F[p.Value] =
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
        req: ClientRequest
    ): F[Unit] =
      getInstance(req.instanceId, req.members) >>= { inst =>
        doPrepare(req.value).run(inst) match {
          case Left(err) =>
            Tap[F](s"error handling request $req: $err")

          case Right((next, effects, ())) =>
            updateAndExecute(next, effects)
        }
      }

    private def handleMessage(
        msg: PaxosMessage
    ): F[Unit] =
      getInstance(msg.instanceId, msg.members) >>= { inst =>
        doHandleMessage(msg).run(inst) match {
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
        next: PaxosInstance,
        effects: Seq[Effect]
    ): F[Unit] =
      instancesRef.update {
        _.updated(next.id, next)
      } >>
        effects.toList.traverse { effect =>
          Concurrent[F].start(handleEffect(effect))
        }.void

    private def handleEffect(
        effect: Effect
    ): F[Unit] = effect match {
      case Effect.Broadcast(msg) =>
        msg.members.toList.traverse(send(_, msg)).void

      case Effect.Unicast(pid, msg) =>
        send(pid, msg)
    }

    private def send(pid: p.Pid, msg: PaxosMessage) =
      writer(pid) flatMap {
        case Some(chan) => chan.send(msg)
        case None       => Tap[F](s"unable to locate channel for dest $pid")
      }

    private def getResult(id: InstanceId) =
      for {
        d <- Deferred[F, p.Value]
        r <- resultsRef.modify { results =>
          if (results.contains(id))
            (results, results(id))
          else
            (results.updated(id, d), d)
        }
      } yield r

    private def getInstance(id: InstanceId, members: Set[p.Pid]) =
      instancesRef.get.map { instances =>
        instances.getOrElse(id, PaxosInstance(id, myPid, members))
      }
  }

  object PaxosService {

    def apply[F[_]: Concurrent: ContextShift: Tap](
        myPid: p.Pid,
        peerRegistryRef: Ref[F, Map[p.Pid, Channel[F, PaxosMessage]]]
    )(implicit ev1: Ordering[p.Pid], ev2: Zero[p.Pid]): Resource[F, PaxosService[F]] = {
      // 1. create channel
      // 2. add channel to the registry
      // 3. create service
      // 4. start handling message in the background
      // 5. on release, remove the channel
      val res = Resource.make(
        for {
          channel <- Channel[F, PaxosMessage]

          _ <- peerRegistryRef.update { peerRegistry =>
            assert(!peerRegistry.contains(myPid))
            peerRegistry.updated(myPid, channel)
          }

          instancesRef <- Ref[F].of(Map.empty[InstanceId, PaxosInstance])
          resultsRef   <- Ref[F].of(Map.empty[InstanceId, Deferred[F, p.Value]])

          requestQueue <- ConcurrentQueue.unbounded[F, ClientRequest](None)

          service = new PaxosService[F](
            myPid,
            writer = pid => peerRegistryRef.get.map(_.get(pid)),
            reader = channel,
            requestQueue,
            instancesRef,
            resultsRef
          )
        } yield service
      ) { _ =>
        peerRegistryRef.update(_ - myPid)
      }

      for {
        service <- res
        _ <- Concurrent[F].background {
          service.handleEvent.foreverM.void
        }
      } yield service
    }
  }

}
