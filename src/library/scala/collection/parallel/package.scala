package scala.collection


import java.lang.Thread._

import scala.collection.generic.CanBuildFrom
import scala.collection.generic.CanCombineFrom
import scala.collection.parallel.mutable.ParArray

import annotation.unchecked.uncheckedVariance


/** Package object for parallel collections.
 */
package object parallel {

  /* constants */
  val MIN_FOR_COPY = -1
  val CHECK_RATE = 512
  val SQRT2 = math.sqrt(2)
  val availableProcessors = java.lang.Runtime.getRuntime.availableProcessors
  private[parallel] val unrolledsize = 16

  /* functions */

  /** Computes threshold from the size of the collection and the parallelism level.
   */
  def thresholdFromSize(sz: Int, parallelismLevel: Int) = {
    val p = parallelismLevel
    if (p > 1) 1 + sz / (8 * p)
    else sz
  }

  private[parallel] def unsupported = throw new UnsupportedOperationException

  private[parallel] def unsupportedop(msg: String) = throw new UnsupportedOperationException(msg)

  /* implicit conversions */

  /** An implicit conversion providing arrays with a `par` method, which
   *  returns a parallel array.
   *
   *  @tparam T      type of the elements in the array, which is a subtype of AnyRef
   *  @param array   the array to be parallelized
   *  @return        a `Parallelizable` object with a `par` method=
   */
  implicit def array2ParArray[T <: AnyRef](array: Array[T]) = new Parallelizable[mutable.ParArray[T]] {
    def par = mutable.ParArray.handoff[T](array)
  }

  implicit def factory2ops[From, Elem, To](bf: CanBuildFrom[From, Elem, To]) = new {
    def isParallel = bf.isInstanceOf[Parallel]
    def asParallel = bf.asInstanceOf[CanCombineFrom[From, Elem, To]]
    def ifParallel[R](isbody: CanCombineFrom[From, Elem, To] => R) = new {
      def otherwise(notbody: => R) = if (isParallel) isbody(asParallel) else notbody
    }
  }

  implicit def traversable2ops[T](t: TraversableOnce[T]) = new {
    def isParallel = t.isInstanceOf[Parallel]
    def isParIterable = t.isInstanceOf[ParIterable[_]]
    def asParIterable = t.asInstanceOf[ParIterable[T]]
    def isParSeq = t.isInstanceOf[ParSeq[_]]
    def asParSeq = t.asInstanceOf[ParSeq[T]]
    def ifParSeq[R](isbody: ParSeq[T] => R) = new {
      def otherwise(notbody: => R) = if (isParallel) isbody(asParSeq) else notbody
    }
    def toParArray = if (t.isInstanceOf[ParArray[_]]) t.asInstanceOf[ParArray[T]] else {
      val it = t.toIterator
      val cb = mutable.ParArrayCombiner[T]()
      while (it.hasNext) cb += it.next
      cb.result
    }
  }

  implicit def throwable2ops(self: Throwable) = new {
    def alongWith(that: Throwable) = self match {
      case ct: CompositeThrowable => new CompositeThrowable(ct.throwables + that)
      case _ => new CompositeThrowable(Set(self, that))
    }
  }

  /* classes */

  /** Composite throwable - thrown when multiple exceptions are thrown at the same time. */
  final class CompositeThrowable(val throwables: Set[Throwable])
  extends Throwable("Multiple exceptions thrown during a parallel computation: " + throwables.mkString(", "))

  /** Unrolled list node.
   */
  private[parallel] class Unrolled[T: ClassManifest] {
    var size = 0
    var array = new Array[T](unrolledsize)
    var next: Unrolled[T] = null
    // adds and returns itself or the new unrolled if full
    def add(elem: T): Unrolled[T] = if (size < unrolledsize) {
      array(size) = elem
      size += 1
      this
    } else {
      next = new Unrolled[T]
      next.add(elem)
    }
    def foreach[U](f: T => U) {
      var unrolled = this
      var i = 0
      while (unrolled ne null) {
        val chunkarr = unrolled.array
        val chunksz = unrolled.size
        while (i < chunksz) {
          val elem = chunkarr(i)
          f(elem)
          i += 1
        }
        i = 0
        unrolled = unrolled.next
      }
    }
    override def toString = array.take(size).mkString("Unrolled(", ", ", ")") + (if (next ne null) next.toString else "")
  }

  /** A helper iterator for iterating very small array buffers.
   *  Automatically forwards the signal delegate when splitting.
   */
  private[parallel] class BufferIterator[T]
    (private val buffer: collection.mutable.ArrayBuffer[T], private var index: Int, private val until: Int, var signalDelegate: collection.generic.Signalling)
  extends ParIterableIterator[T] {
    def hasNext = index < until
    def next = {
      val r = buffer(index)
      index += 1
      r
    }
    def remaining = until - index
    def split: Seq[ParIterableIterator[T]] = if (remaining > 1) {
      val divsz = (until - index) / 2
      Seq(
        new BufferIterator(buffer, index, index + divsz, signalDelegate),
        new BufferIterator(buffer, index + divsz, until, signalDelegate)
      )
    } else Seq(this)
    private[parallel] override def debugInformation = {
      buildString {
        append =>
        append("---------------")
        append("Buffer iterator")
        append("buffer: " + buffer)
        append("index: " + index)
        append("until: " + until)
        append("---------------")
      }
    }
  }

  /** A helper combiner which contains an array of buckets. Buckets themselves
   *  are unrolled linked lists. Some parallel collections are constructed by
   *  sorting their result set according to some criteria.
   *
   *  A reference `heads` to bucket heads is maintained, as well as a reference
   *  `lasts` to the last unrolled list node. Size is kept in `sz` and maintained
   *  whenever 2 bucket combiners are combined.
   *
   *  Clients decide how to maintain these by implementing `+=` and `result`.
   *  Populating and using the buckets is up to the client.
   *  Note that in general the type of the elements contained in the buckets `Buck`
   *  doesn't have to correspond to combiner element type `Elem`.
   *
   *  This class simply gives an efficient `combine` for free - it chains
   *  the buckets together. Since the `combine` contract states that the receiver (`this`)
   *  becomes invalidated, `combine` reuses the receiver and returns it.
   *
   *  Methods `beforeCombine` and `afterCombine` are called before and after
   *  combining the buckets, respectively, given that the argument to `combine`
   *  is not `this` (as required by the `combine` contract).
   *  They can be overriden in subclasses to provide custom behaviour by modifying
   *  the receiver (which will be the return value).
   */
  private[parallel] abstract class BucketCombiner[-Elem, +To, Buck, +CombinerType <: BucketCombiner[Elem, To, Buck, CombinerType]]
    (private val bucketnumber: Int)
  extends Combiner[Elem, To] {
  self: EnvironmentPassingCombiner[Elem, To] =>
    protected var heads: Array[Unrolled[Buck]] @uncheckedVariance = new Array[Unrolled[Buck]](bucketnumber)
    protected var lasts: Array[Unrolled[Buck]] @uncheckedVariance = new Array[Unrolled[Buck]](bucketnumber)
    protected var sz: Int = 0

    def size = sz

    def clear = {
      heads = new Array[Unrolled[Buck]](bucketnumber)
      lasts = new Array[Unrolled[Buck]](bucketnumber)
      sz = 0
    }

    def beforeCombine[N <: Elem, NewTo >: To](other: Combiner[N, NewTo]) {}
    def afterCombine[N <: Elem, NewTo >: To](other: Combiner[N, NewTo]) {}

    def combine[N <: Elem, NewTo >: To](other: Combiner[N, NewTo]): Combiner[N, NewTo] = if (this ne other) {
      if (other.isInstanceOf[BucketCombiner[_, _, _, _]]) {
        beforeCombine(other)

        val that = other.asInstanceOf[BucketCombiner[Elem, To, Buck, CombinerType]]
        var i = 0
        while (i < bucketnumber) {
          if (lasts(i) eq null) {
            heads(i) = that.heads(i)
            lasts(i) = that.lasts(i)
          } else {
            lasts(i).next = that.heads(i)
            if (that.lasts(i) ne null) lasts(i) = that.lasts(i)
          }
          i += 1
        }
        sz = sz + that.size

        afterCombine(other)

        this
      } else error("Unexpected combiner type.")
    } else this

  }


}















