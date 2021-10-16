package paxos.core

import monix.eval.Task

object TestNode extends Node[Task, TestPaxos.type]
