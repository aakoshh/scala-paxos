package paxos.core

import shapeless.tag, tag.@@

/** Helper class to make it easier to tag raw types.
  *
  * ```
  * object MyType extends Tagger[Int]
  * type MyType = MyType.Tagged
  *
  * val myThing: MyType = MyType(0)
  * ```
  */
trait Tagger[U] {
  trait Tag
  type Tagged = U @@ Tag

  def apply(underlying: U): Tagged =
    tag[Tag][U](underlying)
}
