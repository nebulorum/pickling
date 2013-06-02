package scala.pickling

import scala.language.experimental.macros
import scala.language.higherKinds

import scala.reflect.runtime.universe._

import scala.collection.immutable.::
import scala.collection.generic.CanBuildFrom
import scala.collection.IndexedSeq

trait CorePicklersUnpicklers extends GenPicklers with GenUnpicklers {
  implicit def genListPickler[T](implicit format: PickleFormat): Pickler[::[T]] with Unpickler[::[T]] = macro ListPicklerUnpicklerMacro.impl[T]
  implicit def genVectorPickler[T](implicit format: PickleFormat): Pickler[Vector[T]] with Unpickler[Vector[T]] = macro VectorPicklerUnpicklerMacro.impl[T]
}

trait ListPicklerUnpicklerMacro extends CollectionPicklerUnpicklerMacro {
  import c.universe._
  import definitions._
  lazy val ConsClass = c.mirror.staticClass("scala.collection.immutable.$colon$colon")
  def mkType(eltpe: c.Type) = appliedType(ConsClass.toTypeConstructor, List(eltpe))
  def mkArray(picklee: c.Tree) = q"$picklee.toArray"
  def mkBuffer(eltpe: c.Type) = q"scala.collection.mutable.ListBuffer[$eltpe]()"
  def mkResult(buffer: c.Tree) = q"$buffer.toList"
}

trait VectorPicklerUnpicklerMacro extends CollectionPicklerUnpicklerMacro {
  import c.universe._
  import definitions._
  lazy val VectorClass = c.mirror.staticClass("scala.collection.immutable.Vector")
  def mkType(eltpe: c.Type) = appliedType(VectorClass.toTypeConstructor, List(eltpe))
  def mkArray(picklee: c.Tree) = q"$picklee.toArray"
  def mkBuffer(eltpe: c.Type) = q"scala.collection.mutable.ListBuffer[$eltpe]()"
  def mkResult(buffer: c.Tree) = q"$buffer.toVector"
}

trait CollectionPicklerUnpicklerMacro extends Macro {
  def mkType(eltpe: c.Type): c.Type
  def mkArray(picklee: c.Tree): c.Tree
  def mkBuffer(eltpe: c.Type): c.Tree
  def mkResult(buffer: c.Tree): c.Tree

  def impl[T: c.WeakTypeTag](format: c.Tree): c.Tree = {
    import c.universe._
    val tpe = mkType(weakTypeOf[T])
    val eltpe = weakTypeOf[T]
    val isPrimitive = eltpe.isEffectivelyPrimitive
    val picklerUnpicklerName = c.fresh(syntheticPicklerUnpicklerName(tpe).toTermName)
    q"""
      implicit object $picklerUnpicklerName extends scala.pickling.Pickler[$tpe] with scala.pickling.Unpickler[$tpe] {
        import scala.reflect.runtime.universe._
        import scala.pickling._
        import scala.pickling.`package`.PickleOps
        val format = new ${format.tpe}()
        implicit val elpickler: Pickler[$eltpe] = {
          val elpickler = "bam!"
          implicitly[Pickler[$eltpe]]
        }
        implicit val elunpickler: Unpickler[$eltpe] = {
          val elunpickler = "bam!"
          implicitly[Unpickler[$eltpe]]
        }
        implicit val eltag: scala.pickling.FastTypeTag[$eltpe] = {
          val eltag = "bam!"
          implicitly[scala.pickling.FastTypeTag[$eltpe]]
        }
        def pickle(picklee: $tpe, builder: PickleBuilder): Unit = {
          if (!$isPrimitive) throw new PicklingException(s"implementation restriction: non-primitive collections aren't supported")
          builder.beginEntry()
          // TODO: this needs to be adjusted to work with non-primitive types
          // 1) elisions might need to be set on per-element basis
          // 2) val elpicker needs to be turned into def elpickler(el: $$eltpe) which would do dispatch
          // 3) hint pinning would need to work with potentially nested picklings of elements
          // ============
          builder.hintStaticallyElidedType()
          builder.hintTag(eltag)
          builder.pinHints()
          // ============
          val arr = ${mkArray(q"picklee")}
          val length = arr.length
          builder.beginCollection(arr.length)
          var i = 0
          while (i < arr.length) {
            builder.putElement(b => elpickler.pickle(arr(i), b))
            i += 1
          }
          builder.unpinHints()
          builder.endCollection(i)
          builder.endEntry()
        }
        def unpickle(tag: => scala.pickling.FastTypeTag[_], reader: PickleReader): Any = {
          if (!$isPrimitive) throw new PicklingException(s"implementation restriction: non-primitive collections aren't supported")
          var buffer = ${mkBuffer(eltpe)}
          val arrReader = reader.beginCollection()
          // TODO: this needs to be adjusted to work with non-primitive types
          arrReader.hintStaticallyElidedType()
          arrReader.hintTag(eltag)
          arrReader.pinHints()
          val length = arrReader.readLength()
          var i = 0
          while (i < length) {
            arrReader.beginEntry()
            buffer += arrReader.readPrimitive().asInstanceOf[$eltpe]
            arrReader.endEntry()
            i += 1
          }
          arrReader.unpinHints()
          arrReader.endCollection()
          ${mkResult(q"buffer")}
        }
      }
      $picklerUnpicklerName
    """
  }
}