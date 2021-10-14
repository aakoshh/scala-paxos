package paxos.core

import cats._

trait Zero[T] {
  def zero: T
}

object Zero {
  def apply[T](implicit ev: Zero[T]) = ev

  def instance[T](value: T): Zero[T] = new Zero[T] {
    override val zero: T = value
  }
}
