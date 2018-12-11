/*
 * Scala (https://www.scala-lang.org)
 *
 * Copyright EPFL and Lightbend, Inc.
 *
 * Licensed under Apache License 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0).
 *
 * See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 */

package scala.collection
package mutable

import scala.annotation.meta.{getter, setter}
import scala.annotation.tailrec
import scala.collection.generic.DefaultSerializationProxy

/** This class implements mutable maps using a hashtable.
  *
  *  @author  Stefan Zeiger
  *  @since 1
  *  @see [[http://docs.scala-lang.org/overviews/collections/concrete-mutable-collection-classes.html#hash-tables "Scala's Collection Library overview"]]
  *  section on `Hash Tables` for more information.
  *
  *  @tparam K    the type of the keys contained in this hash map.
  *  @tparam V    the type of the values assigned to keys in this hash map.
  *
  *  @define Coll `mutable.HashMap`
  *  @define coll mutable hash map
  *  @define mayNotTerminateInf
  *  @define willNotTerminateInf
  */
@deprecatedInheritance("HashMap wil be made final; use .withDefault for the common use case of computing a default value", "2.13.0")
class HashMap[K, V](initialCapacity: Int, loadFactor: Double)
  extends AbstractMap[K, V]
    with MapOps[K, V, HashMap, HashMap[K, V]]
    with StrictOptimizedIterableOps[(K, V), Iterable, HashMap[K, V]]
    with StrictOptimizedMapOps[K, V, HashMap, HashMap[K, V]] {

  def this() = this(HashMap.defaultInitialCapacity, HashMap.defaultLoadFactor)

  import HashMap.Node

  /** The actual hash table. */
  private[this] var table = new Array[Node[K, V]](tableSizeFor(initialCapacity))

  /** The next size value at which to resize (capacity * load factor). */
  private[this] var threshold: Int = newThreshold(table.length)

  private[this] var contentSize = 0

  override def size: Int = contentSize

  @`inline` private[this] def computeHash(o: K): Int = {
    // Improve the hash by xoring the high 16 bits into the low 16 bits just in case entropy is skewed towards the
    // high-value bits. We only use the lowest bits to determine the hash bucket. This is the same improvement
    // algorithm as in java.util.HashMap.
    val h = o.##
    h ^ (h >>> 16)
  }

  @`inline` private[this] def index(hash: Int) = hash & (table.length - 1)

  override def contains(key: K): Boolean = findNode(key) ne null

  @`inline` private[this] def findNode(key: K): Node[K, V] = {
    val hash = computeHash(key)
    table(index(hash)) match {
      case null => null
      case nd => nd.findNode(key, hash)
    }
  }

  override def sizeHint(size: Int): Unit = {
    val target = tableSizeFor(((size + 1).toDouble / loadFactor).toInt)
    if(target > table.length) growTable(target)
  }

  override def addAll(xs: IterableOnce[(K, V)]): this.type = {
    sizeHint(xs.knownSize)
    super.addAll(xs)
  }

  private[this] def put0(key: K, value: V, getOld: Boolean): Some[V] = {
    if(contentSize + 1 >= threshold) growTable(table.length * 2)
    val hash = computeHash(key)
    val idx = index(hash)
    put0(key, value, getOld, hash, idx)
  }

  private[this] def put0(key: K, value: V, getOld: Boolean, hash: Int, idx: Int): Some[V] = {
    table(idx) match {
      case null =>
        table(idx) = new Node[K, V](key, hash, value, null)
      case old =>
        var prev: Node[K, V] = null
        var n = old
        while((n ne null) && n.hash <= hash) {
          if(n.hash == hash && key == n.key) {
            val old = n.value
            n.value = value
            return (if(getOld) Some(old) else null)
          }
          prev = n
          n = n.next
        }
        if(prev eq null) table(idx) = new Node(key, hash, value, old)
        else prev.next = new Node(key, hash, value, prev.next)
    }
    contentSize += 1
    null
  }

  private def remove0(elem: K) : Node[K, V] = {
    val hash = computeHash(elem)
    var idx = index(hash)
    table(idx) match {
      case null => null
      case nd if nd.hash == hash && nd.key == elem =>
        // first element matches
        table(idx) = nd.next
        contentSize -= 1
        nd
      case nd =>
        // find an element that matches
        var prev = nd
        var next = nd.next
        while((next ne null) && next.hash <= hash) {
          if(next.hash == hash && next.key == elem) {
            prev.next = next.next
            contentSize -= 1
            return next
          }
          prev = next
          next = next.next
        }
        null
    }
  }

  private[this] abstract class HashMapIterator[A] extends AbstractIterator[A] {
    private[this] var i = 0
    private[this] var node: Node[K, V] = null
    private[this] val len = table.length

    protected[this] def extract(nd: Node[K, V]): A

    def hasNext: Boolean = {
      if(node ne null) true
      else {
        while(i < len) {
          val n = table(i)
          i += 1
          if(n ne null) { node = n; return true }
        }
        false
      }
    }

    def next(): A =
      if(!hasNext) Iterator.empty.next()
      else {
        val r = extract(node)
        node = node.next
        r
      }
  }

  override def iterator: Iterator[(K, V)] =
    if(size == 0) Iterator.empty
    else new HashMapIterator[(K, V)] {
      protected[this] def extract(nd: Node[K, V]) = (nd.key, nd.value)
    }

  override def keysIterator: Iterator[K] =
    if(size == 0) Iterator.empty
    else new HashMapIterator[K] {
      protected[this] def extract(nd: Node[K, V]) = nd.key
    }

  override def valuesIterator: Iterator[V] =
    if(size == 0) Iterator.empty
    else new HashMapIterator[V] {
      protected[this] def extract(nd: Node[K, V]) = nd.value
    }

  private[this] def growTable(newlen: Int) = {
    var oldlen = table.length
    threshold = newThreshold(newlen)
    if(size == 0) table = new Array(newlen)
    else {
      table = java.util.Arrays.copyOf(table, newlen)
      val preLow: Node[K, V] = new Node(null.asInstanceOf[K], 0, null.asInstanceOf[V], null)
      val preHigh: Node[K, V] = new Node(null.asInstanceOf[K], 0, null.asInstanceOf[V], null)
      // Split buckets until the new length has been reached. This could be done more
      // efficiently when growing an already filled table to more than double the size.
      while(oldlen < newlen) {
        var i = 0
        while (i < oldlen) {
          val old = table(i)
          if(old ne null) {
            preLow.next = null
            preHigh.next = null
            var lastLow: Node[K, V] = preLow
            var lastHigh: Node[K, V] = preHigh
            var n = old
            while(n ne null) {
              val next = n.next
              if((n.hash & oldlen) == 0) { // keep low
                lastLow.next = n
                lastLow = n
              } else { // move to high
                lastHigh.next = n
                lastHigh = n
              }
              n = next
            }
            lastLow.next = null
            if(old ne preLow.next) table(i) = preLow.next
            if(preHigh.next ne null) {
              table(i + oldlen) = preHigh.next
              lastHigh.next = null
            }
          }
          i += 1
        }
        oldlen *= 2
      }
    }
  }

  private[this] def tableSizeFor(capacity: Int) =
    (Integer.highestOneBit((capacity-1).max(4))*2).min(1 << 30)

  private[this] def newThreshold(size: Int) = (size.toDouble * loadFactor).toInt

  override def clear(): Unit = {
    java.util.Arrays.fill(table.asInstanceOf[Array[AnyRef]], null)
    contentSize = 0
  }

  def get(key: K): Option[V] = findNode(key) match {
    case null => None
    case nd => Some(nd.value)
  }

  @throws[NoSuchElementException]
  override def apply(key: K): V = findNode(key) match {
    case null => default(key)
    case nd => nd.value
  }

  override def getOrElse[V1 >: V](key: K, default: => V1): V1 = {
    if (getClass != classOf[HashMap[_, _]]) {
      // subclasses of HashMap might customise `get` ...
      super.getOrElse(key, default)
    } else {
      // .. but in the common case, we can avoid the Option boxing.
      val nd = findNode(key)
      if (nd eq null) default else nd.value
    }
  }

  override def getOrElseUpdate(key: K, defaultValue: => V): V = {
    if (getClass != classOf[HashMap[_, _]]) {
      // subclasses of HashMap might customise `get` ...
      super.getOrElseUpdate(key, defaultValue)
    } else {
      val hash = computeHash(key)
      val idx = index(hash)
      val nd = table(idx) match {
        case null => null
        case nd => nd.findNode(key, hash)
      }
      if(nd != null) nd.value
      else {
        val table0 = table
        val default = defaultValue
        if(contentSize + 1 >= threshold) growTable(table.length * 2)
        // Avoid recomputing index if the `defaultValue()` or new element hasn't triggered a table resize.
        val newIdx = if (table0 eq table) idx else index(hash)
        put0(key, default, false, hash, newIdx)
        default
      }
    }
  }

  override def put(key: K, value: V): Option[V] = put0(key, value, true) match {
    case null => None
    case sm => sm
  }

  override def remove(key: K): Option[V] = remove0(key) match {
    case null => None
    case nd => Some(nd.value)
  }

  override def update(key: K, value: V): Unit = put0(key, value, false)

  def addOne(elem: (K, V)): this.type = { put0(elem._1, elem._2, false); this }

  def subtractOne(elem: K): this.type = { remove0(elem); this }

  override def knownSize: Int = size

  override def isEmpty: Boolean = size == 0

  override def foreach[U](f: ((K, V)) => U): Unit = {
    val len = table.length
    var i = 0
    while(i < len) {
      val n = table(i)
      if(n ne null) n.foreach(f)
      i += 1
    }
  }

  override protected[this] def writeReplace(): AnyRef = new DefaultSerializationProxy(new mutable.HashMap.DeserializationFactory[K, V](table.length, loadFactor), this)

  override def mapFactory: MapFactory[HashMap] = HashMap

  override protected[this] def stringPrefix = "HashMap"
}

/**
  * $factoryInfo
  *  @define Coll `mutable.HashMap`
  *  @define coll mutable hash map
  */
@SerialVersionUID(3L)
object HashMap extends MapFactory[HashMap] {

  def empty[K, V]: HashMap[K, V] = new HashMap[K, V]

  def from[K, V](it: collection.IterableOnce[(K, V)]): HashMap[K, V] = {
    val k = it.knownSize
    val cap = if(k > 0) ((k + 1).toDouble / defaultLoadFactor).toInt else defaultInitialCapacity
    new HashMap[K, V](cap, defaultLoadFactor).addAll(it)
  }

  def newBuilder[K, V]: Builder[(K, V), HashMap[K, V]] = newBuilder(defaultInitialCapacity, defaultLoadFactor)

  def newBuilder[K, V](initialCapacity: Int, loadFactor: Double): Builder[(K, V), HashMap[K, V]] =
    new GrowableBuilder[(K, V), HashMap[K, V]](new HashMap[K, V](initialCapacity, loadFactor)) {
      override def sizeHint(size: Int) = elems.sizeHint(size)
    }

  /** The default load factor for the hash table */
  final def defaultLoadFactor: Double = 0.75

  /** The default initial capacity for the hash table */
  final def defaultInitialCapacity: Int = 16

  @SerialVersionUID(3L)
  private final class DeserializationFactory[K, V](val tableLength: Int, val loadFactor: Double) extends Factory[(K, V), HashMap[K, V]] with Serializable {
    def fromSpecific(it: IterableOnce[(K, V)]): HashMap[K, V] = new HashMap[K, V](tableLength, loadFactor).addAll(it)
    def newBuilder: Builder[(K, V), HashMap[K, V]] = HashMap.newBuilder(tableLength, loadFactor)
  }

  private final class Node[K, V](_key: K, _hash: Int, private[this] var _value: V, private[this] var _next: Node[K, V]) {
    def key: K = _key
    def hash: Int = _hash
    def value: V = _value
    def value_= (v: V): Unit = _value = v
    def next: Node[K, V] = _next
    def next_= (n: Node[K, V]): Unit = _next = n

    @tailrec
    def findNode(k: K, h: Int): Node[K, V] =
      if(h == _hash && k == _key) this
      else if((_next eq null) || (_hash > h)) null
      else _next.findNode(k, h)

    @tailrec
    def foreach[U](f: ((K, V)) => U): Unit = {
      f((_key, _value))
      if(_next ne null) _next.foreach(f)
    }

    override def toString = s"Node($key, $value, $hash) -> $next"
  }
}
