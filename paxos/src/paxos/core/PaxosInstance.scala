package paxos.core

import cats.Monad
import cats.implicits._
import scala.math.Ordering.Implicits._
import scala.annotation.tailrec

case class PaxosInstance[P <: Paxos](
    id: InstanceId,
    myPid: P#Pid,
    members: Set[P#Pid],
    maxBallotOrdinal: BallotOrdinal[P#Pid],
    vote: Option[Vote[P]],
    requestedValue: Option[P#Value],
    safeValueVote: Option[Vote[P]],
    promises: Set[P#Pid],
    acceptingVote: Option[Vote[P]],
    accepts: Set[P#Pid],
    decided: Boolean
)

object PaxosInstance {

  def apply[P <: Paxos](id: InstanceId, myPid: P#Pid, members: Set[P#Pid])(implicit
      ev1: Zero[P#Pid]
  ): PaxosInstance[P] =
    PaxosInstance[P](
      id,
      myPid,
      members,
      maxBallotOrdinal = Zero[BallotOrdinal[P#Pid]].zero,
      vote = None,
      requestedValue = None,
      safeValueVote = None,
      promises = Set.empty[P#Pid],
      acceptingVote = None,
      accepts = Set.empty[P#Pid],
      decided = false
    )

  type TransitionAttempt[P <: Paxos, A] =
    Either[String, (PaxosInstance[P], List[Effect[P]], A)]

  /** Monadic operations for transitioning.
    * Wraps all the functions so the generic signature doesn't need to be repeated.
    */
  trait Ops[P <: Paxos] {
    implicit def pidOrd: Ordering[P#Pid]

    case class Transition[A](
        run: PaxosInstance[P] => TransitionAttempt[P, A]
    )
    private implicit val transitionMonad: Monad[Transition] =
      new Monad[Transition] {
        def pure[A](a: A): Transition[A] =
          Transition[A] { inst => Right((inst, Nil, a)) }

        def flatMap[A, B](fa: Transition[A])(f: A => Transition[B]): Transition[B] =
          Transition[B] { inst =>
            fa.run(inst) match {
              case Left(err) => Left(err)
              case Right((nextInst, effectsa, a)) =>
                f(a).run(nextInst).map { case (nextb, effectsb, b) =>
                  (nextb, effectsa ++ effectsb, b)
                }
            }
          }

        def tailRecM[A, B](a: A)(f: A => Transition[Either[A, B]]): Transition[B] = {
          @tailrec
          def loop(
              inst: PaxosInstance[P],
              effects: List[Effect[P]],
              a: A
          ): TransitionAttempt[P, B] =
            f(a).run(inst) match {
              case Left(err) =>
                Left(err)
              case Right((nextInst, nextEffects, Right(b))) =>
                Right((nextInst, effects ++ nextEffects, b))
              case Right((nextInst, nextEffects, Left(nextA))) =>
                loop(nextInst, effects ++ nextEffects, nextA)
            }

          Transition[B](loop(_, Nil, a))
        }
      }

    private implicit val ballotOrd = BallotOrdinal.ordering[P#Pid]

    private def checkQuorum(inst: PaxosInstance[P], voters: Set[P#Pid]) = {
      val allowed = inst.members intersect voters
      2 * allowed.size > inst.members.size
    }

    private def get: Transition[PaxosInstance[P]] =
      Transition(inst => Right((inst, Nil, inst)))

    private def set(inst: PaxosInstance[P]): Transition[Unit] =
      Transition(_ => Right((inst, Nil, ())))

    private def update(f: PaxosInstance[P] => PaxosInstance[P]): Transition[Unit] =
      Transition(inst => Right((f(inst), Nil, ())))

    private def addEffect(effect: Effect[P]): Transition[Unit] =
      Transition(inst => Right((inst, List(effect), ())))

    private def error[A](msg: String): Transition[A] =
      Transition(_ => Left(msg))

    private val unit = ().pure[Transition]

    private def errorIf(pred: PaxosInstance[P] => Boolean, msg: String): Transition[Unit] =
      get >>= { inst =>
        if (pred(inst)) error(msg) else unit
      }

    /** Add to a set of PIDs unless we already have quorum.
      * Return true if adding has just result in a quorum.
      */
    private def doAddUntilQuorum(
        pid: P#Pid,
        getPids: PaxosInstance[P] => Set[P#Pid],
        setPids: (PaxosInstance[P], Set[P#Pid]) => PaxosInstance[P]
    ): Transition[Boolean] = get >>= { inst =>
      val pids = getPids(inst)
      if (checkQuorum(inst, pids))
        false.pure[Transition]
      else {
        val next = setPids(inst, pids + pid)
        set(next).as(checkQuorum(next, pids + pid))
      }
    }

    private def doAddAccept(pid: P#Pid) =
      doAddUntilQuorum(pid, _.accepts, (inst, ps) => inst.copy(accepts = ps))

    private def doAddPromise(pid: P#Pid) =
      doAddUntilQuorum(pid, _.promises, (inst, ps) => inst.copy(promises = ps))

    private def doSetMaxBallotOrdinal(ord: BallotOrdinal[P#Pid]) =
      update { inst =>
        if (ord <= inst.maxBallotOrdinal) inst
        else
          inst.copy(maxBallotOrdinal = ord, promises = Set.empty[P#Pid])
      }

    private def sendAll(detail: PaxosMessage.Detail[P]) =
      for {
        inst <- get
        msg = PaxosMessage(
          src = inst.myPid,
          instanceId = inst.id,
          members = inst.members,
          ballotOrdinal = inst.maxBallotOrdinal,
          detail = detail
        )
        _ <- addEffect(Effect.Broadcast(msg))
      } yield ()

    /** Create a 1a message. */
    private val sendPrepare =
      sendAll(PaxosMessage.Prepare)

    /** Create a 1b message. */
    private def sendPromise(dest: P#Pid) =
      for {
        inst <- get
        msg = PaxosMessage(
          src = inst.myPid,
          instanceId = inst.id,
          members = inst.members,
          ballotOrdinal = inst.maxBallotOrdinal,
          detail = PaxosMessage.Promise(inst.vote)
        )
        _ <- addEffect(Effect.Unicast(dest, msg))
      } yield ()

    /** Create a 2b message. */
    private def sendAccept(value: P#Value) =
      sendAll(PaxosMessage.Accept(value))

    /** Create a 2a message. */
    private def sendPropose(value: P#Value) =
      sendAll(PaxosMessage.Propose(value))

    private val maybeSendPropose =
      get >>= { inst =>
        val maybeValue = inst.safeValueVote.map(_.value) orElse inst.requestedValue

        maybeValue.map(sendPropose).getOrElse(unit)
      }

    /** A client request a value to be prepared.
      *
      * If we already have a requested value, keep it.
      * Then it's just a mechanism to initiate a retry.
      */
    def doPrepare(value: P#Value): Transition[Unit] =
      for {
        inst <- get
        _    <- update(_.copy(requestedValue = value.some)).whenA(inst.requestedValue.isEmpty)
        _    <- doSetMaxBallotOrdinal(inst.maxBallotOrdinal.incr(inst.myPid))
        _    <- sendPrepare
      } yield ()

    /** Handle a message and detect the edge where the instance transitions
      * to decided. Return the decided value on that edge; return #f in all
      * other cases.
      */
    def doHandleMessage(msg: PaxosMessage[P]): Transition[Option[P#Value]] =
      for {
        _    <- errorIf(_.id != msg.instanceId, "Wrong instance id.")
        _    <- errorIf(_.members != msg.members, "Wrong members.")
        inst <- get
        msgOrd = msg.ballotOrdinal
        nodec  = none[P#Value]
        ignore = nodec.pure[Transition]

        (maybeDecision: Option[P#Value]) <- msg.detail match {
          case PaxosMessage.Accept(acceptedValue) =>
            // Accept messages are special because they can cause the
            // instance to become decided.
            val acceptingOrd = inst.acceptingVote.map(_.ord)
            // Process new accepts only if we are not yet decided and the
            // accept's ord is >= accepting-ord
            if (!inst.decided && acceptingOrd.fold(true)(msgOrd >= _)) {
              // If this is a newer ord, start with a clean learner state.
              for {
                _ <- set {
                  inst.copy(
                    accepts = Set.empty[P#Pid],
                    acceptingVote = Vote(acceptedValue, msgOrd).some
                  )
                }.whenA(!inst.decided && acceptingOrd.fold(true)(msgOrd > _))

                reachedQuorum <- doAddAccept(msg.src)
                _             <- update(_.copy(decided = inst.decided || reachedQuorum))

              } yield if (reachedQuorum) Some(acceptedValue) else None
            } else {
              ignore
            }

          case PaxosMessage.Prepare =>
            get.map(msgOrd <= _.maxBallotOrdinal) >>= {
              case true => ignore
              case false =>
                for {
                  _ <- doSetMaxBallotOrdinal(msgOrd)
                  _ <- sendPromise(msg.src)
                } yield nodec
            }

          case PaxosMessage.Propose(value) =>
            get.map(msgOrd < _.maxBallotOrdinal) >>= {
              case true => ignore
              case false =>
                for {
                  _ <- doSetMaxBallotOrdinal(msgOrd)
                  _ <- update(_.copy(vote = Vote(value, msgOrd).some))
                  _ <- sendAccept(value)
                } yield nodec
            }

          case PaxosMessage.Promise(maybeVote) =>
            for {
              inst <- get
              _ <- maybeVote match {
                case Some(vote) if msgOrd == inst.maxBallotOrdinal =>
                  val newSafeValueVote = inst.safeValueVote match {
                    case Some(safeValueVote) if vote.ord <= safeValueVote.ord => safeValueVote.some
                    case _                                                    => vote.some
                  }
                  set(inst.copy(safeValueVote = newSafeValueVote))
                case _ =>
                  unit
              }
              _ <- doAddPromise(msg.src)
              _ <- maybeSendPropose
            } yield nodec
        }
      } yield maybeDecision

  }
}
