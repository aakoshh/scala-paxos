package paxos.core

import cats.implicits._
import scala.math.Ordering.Implicits._

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
)(implicit ev: Ordering[P#Pid]) {
  import PaxosInstance._

  /** A client request a value to be prepared.
    *
    * If we already have a requested value, keep it.
    * Then it's just a mechanism to initiate a retry.
    */
  def doPrepare(value: P#Value): Transition[P, Unit] = {
    val inst = copy(
      requestedValue = requestedValue orElse value.some
    ).doSetMaxBallotOrdinal(maxBallotOrdinal.incr(myPid))

    (inst, List(sendPrepare), ())
  }

  /** Handle a message and detect the edge where the instance transitions
    * to decided. Return the decided value on that edge; return #f in all
    * other cases.
    */
  def handleMessage(msg: PaxosMessage[P]): TransitionAttempt[P, Option[P#Value]] = {
    if (msg.instanceId != id) Left("Wrong instance id.")
    else if (msg.members != members) Left("Wrong members.")
    else {
      val msgOrd = msg.ballotOrdinal
      val ignore = Right((this, Nil, None))

      def nodecision(inst: PaxosInstance[P], maybeEffect: Option[Effect[P]]) =
        Right((inst, maybeEffect.toList, None))

      msg.detail match {
        case PaxosMessage.Accept(acceptedValue) =>
          // Accept messages are special because they can cause the
          // instance to become decided.
          val acceptingOrd = acceptingVote.map(_.ord)
          // Process new accepts only if we are not yet decided and the
          // accept's ord is >= accepting-ord
          if (!decided && acceptingOrd.fold(true)(msgOrd >= _)) {
            // If this is a newer ord, start with a clean learner state.
            val (inst, reachedQuorum) = {
              val inst = if (!decided && acceptingOrd.fold(true)(msgOrd > _)) {
                copy(accepts = Set.empty[P#Pid], acceptingVote = Vote(acceptedValue, msgOrd).some)
              } else {
                this
              }
              inst.doAddAccept(msg.src)
            }
            Right(
              (
                inst.copy(decided = decided || reachedQuorum),
                Nil,
                if (reachedQuorum) Some(acceptedValue) else None
              )
            )
          } else {
            ignore
          }

        case PaxosMessage.Prepare =>
          if (msgOrd <= maxBallotOrdinal) ignore
          else {
            val inst = doSetMaxBallotOrdinal(msgOrd)
            nodecision(inst, inst.sendPromise(msg.src).some)
          }

        case PaxosMessage.Propose(value) =>
          if (msgOrd < maxBallotOrdinal) ignore
          else {
            val inst = doSetMaxBallotOrdinal(msgOrd).copy(vote = Vote(value, msgOrd).some)
            nodecision(inst, inst.sendAccept(value).some)
          }

        case PaxosMessage.Promise(maybeVote) =>
          val (inst, reachedQuorum) = {
            val inst = maybeVote match {
              case Some(vote) if msgOrd == maxBallotOrdinal =>
                val newSafeValueVote = safeValueVote match {
                  case Some(safeValueVote) if vote.ord <= safeValueVote.ord => safeValueVote.some
                  case _                                                    => vote.some
                }
                copy(safeValueVote = newSafeValueVote)
              case _ =>
                this
            }
            inst.doAddPromise(msg.src)
          }
          nodecision(inst, inst.maybeSendPropose)
      }
    }
  }

  private def checkQuorum(voters: Set[P#Pid]) = {
    val allowed = members intersect voters
    2 * allowed.size > members.size
  }

  /** Add to a set of PIDs unless we already have quorum.
    * Return true if adding has just result in a quorum.
    */
  private def doAddUntilQuorum(
      pid: P#Pid,
      get: Set[P#Pid],
      set: Set[P#Pid] => PaxosInstance[P]
  ): (PaxosInstance[P], Boolean) = {
    if (checkQuorum(get)) (this, false)
    else {
      set(get + pid) -> checkQuorum(get + pid)
    }
  }

  private def doAddAccept(pid: P#Pid) =
    doAddUntilQuorum(pid, accepts, ps => copy(accepts = ps))

  private def doAddPromise(pid: P#Pid) =
    doAddUntilQuorum(pid, promises, ps => copy(promises = ps))

  /** If this is a new, higher ord, update max and clear any promises to the lower ordinal. */
  private def doSetMaxBallotOrdinal(ord: BallotOrdinal[P#Pid]) =
    if (ord <= maxBallotOrdinal) this
    else
      copy(maxBallotOrdinal = ord, promises = Set.empty[P#Pid])

  private def sendAll(detail: PaxosMessage.Detail[P]) = {
    val msg = PaxosMessage(
      src = myPid,
      instanceId = id,
      members = members,
      ballotOrdinal = maxBallotOrdinal,
      detail = detail
    )
    Effect.Broadcast(msg)
  }

  /** Create a 1a message. */
  private def sendPrepare =
    sendAll(PaxosMessage.Prepare)

  /** Create a 1b message. */
  private def sendPromise(dest: P#Pid) = {
    val msg = PaxosMessage(
      src = myPid,
      instanceId = id,
      members = members,
      ballotOrdinal = maxBallotOrdinal,
      detail = PaxosMessage.Promise(vote)
    )
    Effect.Unicast(dest, msg)
  }

  /** Create a 2b message. */
  private def sendAccept(value: P#Value) =
    sendAll(PaxosMessage.Accept(value))

  /** Create a 2a message. */
  private def sendPropose(value: P#Value) =
    sendAll(PaxosMessage.Propose(value))

  /** Send the safe value if we have one, or the requested one, which should be set at this point,
    * if the proposal was initiated by a client. If not, we can wait until a client sends a value.
    */
  private def maybeSendPropose =
    (safeValueVote.map(_.value) orElse requestedValue).map(sendPropose)
}

object PaxosInstance {
  type Transition[P <: Paxos, A] =
    (PaxosInstance[P], List[Effect[P]], A)

  type TransitionAttempt[P <: Paxos, A] =
    Either[String, Transition[P, A]]

  def apply[P <: Paxos](id: InstanceId, myPid: P#Pid, members: Set[P#Pid])(implicit
      ev1: Zero[P#Pid],
      ev2: Ordering[P#Pid]
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
}
