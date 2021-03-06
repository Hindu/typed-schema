package ru.tinkoff.tschema.swagger

import java.util.{Date, UUID}

import akka.util.ByteString
import cats.Eval
import enumeratum.{Enum, EnumEntry}
import io.circe.JsonObject
import cats.syntax.option._
import shapeless.labelled.FieldType
import shapeless.{:+:, ::, CNil, Coproduct, HList, HNil, LabelledGeneric, Lazy, Witness, ops}
import SwaggerTypeable.{Config, seq}
import magnolia.{CaseClass, Magnolia, SealedTrait}
import cats.syntax.traverse._
import cats.instances.vector._
import cats.instances.list._
import enumeratum.values.{ValueEnum, ValueEnumEntry}

import scala.reflect.runtime.universe.TypeTag

trait SwaggerTypeable[T] {
  self =>
  def typ: SwaggerType
  def as[U]: SwaggerTypeable[U] = this.asInstanceOf[SwaggerTypeable[U]]

  def optional: Boolean = false

  def updateTyp(f: SwaggerType => SwaggerType): SwaggerTypeable[T] =
    SwaggerTypeable.make[T](f(self.typ))

  def anon: SwaggerTypeable[T] = new SwaggerTypeable[T] {
    override def typ: SwaggerType = self.typ.deref.value
  }

  def named(name: String): SwaggerTypeable[T] = new SwaggerTypeable[T] {
    override def typ: SwaggerType =
      (SwaggerType.refPrism composeLens SwaggerRef.name).set(name)(self.typ)
  }

  def describe(description: String): SwaggerTypeable[T] = updateTyp(_.describe(description))

  def describeFields(descriptions: (String, String)*): SwaggerTypeable[T] =
    updateTyp(SwaggerType.setObj.modify(_.describeFields(descriptions: _*)))

  def xml(
           name: Option[String] = None,
           attribute: Boolean = false,
           prefix: Option[String] = None,
           namespace: Option[String] = None,
           wrapped: Boolean = false): SwaggerTypeable[T] =
    updateTyp(SwaggerXML.wrap(SwaggerXMLOptions(
      name = name,
      attribute = attribute,
      prefix = prefix,
      namespace = namespace,
      wrapped = wrapped)))

  def xmlFields(fieldOpts: (String, SwaggerXMLOptions)*) =
    updateTyp(SwaggerType.setObj.modify(_.xmlFields(fieldOpts: _*)))

  //Safe versions
  def descr[S <: Symbol, L <: HList]
  (fld: FieldType[S, String])
  (implicit lgen: LabelledGeneric.Aux[T, L], sel: ops.record.Selector[L, S], witness: Witness.Aux[S]) =
    describeFields(witness.value.name -> fld)

  def xmlFld[S <: Symbol, L <: HList](fld: FieldType[S, SwaggerXMLOptions])
                                     (implicit lgen: LabelledGeneric.Aux[T, L], sel: ops.record.Selector[L, S], witness: Witness.Aux[S]) =
    xmlFields(witness.value.name -> fld)
}

trait LowLevelSwaggerTypeable {
  @inline final def make[T](t: SwaggerType): SwaggerTypeable[T] = new SwaggerTypeable[T] {
    val typ = t
  }
  @inline final def makeNamed[T](t: SwaggerType, name: String): SwaggerTypeable[T] = new SwaggerTypeable[T] {
    val typ = SwaggerRef(name, None, Eval.later(t))
  }
  @inline final def seq[X[_], T](implicit items: Lazy[SwaggerTypeable[T]]): SwaggerTypeable[X[T]] = make[X[T]](SwaggerArray(items.later))

  final implicit def seqTypeable[T: SwaggerTypeable]: SwaggerTypeable[Seq[T]] = seq[Seq, T]
}

trait SwaggerTypeableInstances extends LowLevelSwaggerTypeable with CirceSwaggerTypeableInstances {
  final implicit val swaggerTypeableInteger: SwaggerTypeable[Int] = make[Int](SwaggerPrimitive.integer)
  final implicit val swaggerTypeableLong: SwaggerTypeable[Long] = make[Long](SwaggerPrimitive.long)
  final implicit val swaggerTypeableFloat: SwaggerTypeable[Float] = make[Float](SwaggerPrimitive.float)
  final implicit val swaggerTypeableDouble: SwaggerTypeable[Double] = make[Double](SwaggerPrimitive.double)
  final implicit val swaggerTypeableString: SwaggerTypeable[String] = make[String](SwaggerPrimitive.string)
  final implicit val swaggerTypeableByte: SwaggerTypeable[Byte] = make[Byte](SwaggerPrimitive.byte)
  final implicit val swaggerTypeableBinary: SwaggerTypeable[ByteString] = make[ByteString](SwaggerPrimitive.binary)
  final implicit val swaggerTypeableDateTime: SwaggerTypeable[Date] = make[Date](SwaggerPrimitive.dateTime)
  final implicit val swaggerTypeableBoolean: SwaggerTypeable[Boolean] = make[Boolean](SwaggerPrimitive.boolean)
  final implicit val swaggerTypeableBigIng: SwaggerTypeable[BigInt] = make[BigInt](SwaggerPrimitive.integer)
  final implicit val swaggerTypeableBigDecimal: SwaggerTypeable[BigDecimal] = make[BigDecimal](SwaggerPrimitive.double)
  final implicit val swaggerTypeableUUID: SwaggerTypeable[UUID] = make[UUID](new SwaggerPrimitive(SwaggerStringValue.uuid))
  final implicit val swaggerTypeableUnit: SwaggerTypeable[Unit] = make[Unit](SwaggerObject())

  final implicit val swaggerTypeableJsonObject: SwaggerTypeable[JsonObject] = make[JsonObject](SwaggerObject())

  final implicit def swaggerVectorTypeable[T: SwaggerTypeable]: SwaggerTypeable[Vector[T]] = seq[Vector, T]
  final implicit def swaggerListTypeable[T: SwaggerTypeable]: SwaggerTypeable[List[T]] = seq[List, T]
  final implicit def swaggerSetTypeable[T: SwaggerTypeable]: SwaggerTypeable[Set[T]] = seq[Set, T]

  implicit def optionTypeable[T](implicit inst: Lazy[SwaggerTypeable[T]]): SwaggerTypeable[Option[T]] =
    new SwaggerTypeable[Option[T]] {
      override def typ: SwaggerType = inst.value.typ
      override def optional: Boolean = true
    }

  final implicit def swaggerMapTypeable[K, T](implicit values: Lazy[SwaggerTypeable[T]], keys: SwaggerMapKey[K]): SwaggerTypeable[Map[K, T]] =
    make[Map[K, T]](SwaggerMap(values.later))

  private def typeSum[X[_, _], A, B](implicit left: Lazy[SwaggerTypeable[A]], right: Lazy[SwaggerTypeable[B]]): SwaggerTypeable[X[A, B]] =
    make[X[A, B]](SwaggerOneOf(Vector(None -> left.later, None -> right.later)))

  final implicit def swaggerEitherTypeable[A: SwaggerTypeable, B: SwaggerTypeable]: SwaggerTypeable[Either[A, B]] = typeSum[Either, A, B]
}

object SwaggerTypeable extends SwaggerTypeableInstances {
  def apply[T](implicit typeable: SwaggerTypeable[T]): SwaggerTypeable[T] = typeable

  val snakeCaseModifier: String => String = _.replaceAll(
    "([A-Z]+)([A-Z][a-z])",
    "$1_$2"
  ).replaceAll("([a-z\\d])([A-Z])", "$1_$2").toLowerCase

  case class Config(propMod: String => String = identity,
                    altMod: String => String = identity,
                    plainCoproducts: Boolean = false,
                    discriminator: Option[String] = None,
                    nameMod: String => String = identity) {

    def snakeCaseProps = copy(propMod = snakeCaseModifier)

    def snakeCaseAlts = copy(altMod = snakeCaseModifier)

    @deprecated("use snakeCaseProps", since = "0.9.3")
    def withCamelCase = snakeCaseProps

    def withDiscriminator(name: String) = copy(discriminator = Some(name), plainCoproducts = true)

    def withNamePrefix(prefix: String) = copy(nameMod = nameMod andThen (prefix + _))
  }

  val defaultConfig = Config()

  private[tschema] implicit class LazyTypeableOps[T](val lazt: Lazy[SwaggerTypeable[T]]) extends AnyVal {
    def later = Eval.later(lazt.value.typ)
  }

  def defer[T](t: => SwaggerType) = new SwaggerTypeable[T] {
    lazy val typ = t
  }

  def genTypeable[T](implicit gen: Lazy[GenericSwaggerTypeable[T]]) = make[T](gen.value.typ)
  def genNamedTypeable[T](name: String)(implicit gen: Lazy[GenericSwaggerTypeable[T]]) = make[T](SwaggerRef(name, gen.value.description, gen.later))
  def deriveNamedTypeable[T](implicit gen: Lazy[GenericSwaggerTypeable[T]], typeTag: TypeTag[T], config: Config = defaultConfig): SwaggerTypeable[T] =
    genNamedTypeable[T](config.nameMod(typeTag.tpe.typeSymbol.name.toString))

  trait SwaggerTypeableEnum[X <: EnumEntry] {
    self: Enum[X] =>

    lazy implicit val swaggerTypeable: SwaggerTypeable[X] = make(SwaggerEnumeration(namesToValuesMap.keys.toVector))
  }

  trait SwaggerTypeableStringEnum[X <: ValueEnumEntry[String]] {
    self: ValueEnum[String, X] =>

    lazy implicit val swaggerTypeable: SwaggerTypeable[X] = make(SwaggerEnumeration(valuesToEntriesMap.keys.toVector))
  }

  trait SwaggerTypeableEnumeration {
    self: Enumeration =>

    lazy implicit val swaggerTypeable: SwaggerTypeable[Value] = make(SwaggerEnumeration(values.map(_.toString).toVector))
  }
}

trait GenericSwaggerTypeable[T] extends SwaggerTypeable[T] {
  def typ: SwaggerType
  def description: Option[String] = None
}

object GenericSwaggerTypeable {
  final case class HListProps[L <: HList](props: List[(String, Eval[(SwaggerType, Boolean)])])
  final case class CoproductAlts[C <: Coproduct](alts: List[(Option[String], Eval[SwaggerType])])

  private implicit class DescribeTypeOps(val typ: SwaggerType) extends AnyVal {
    def describeWith(descr: Option[String]): SwaggerType = descr.fold(typ)(description => typ.describe(description))
  }

  def apply[T](implicit typ: GenericSwaggerTypeable[T]): GenericSwaggerTypeable[T] = typ

  def make[T](t: SwaggerType, descript: Option[String] = None) = new GenericSwaggerTypeable[T] {
    val typ = t
    override val description = descript
  }

  implicit val hNilProps = HListProps[HNil](Nil)

  implicit def hConProps[Name <: Symbol, Value, Tail <: HList]
  (implicit headProp: Lazy[SwaggerTypeable[Value]], tail: HListProps[Tail], name: Witness.Aux[Name], cfg: Config = SwaggerTypeable.defaultConfig) =
    HListProps[FieldType[Name, Value] :: Tail]((cfg.propMod(name.value.name), Eval.later(headProp.value).map(t => t.typ -> t.optional)) :: tail.props)

  implicit def genericProductTypeable[T, L <: HList]
  (implicit lgen: LabelledGeneric.Aux[T, L],
   list: HListProps[L],
   descr: DescribeTypeable[T] = DescribeTypeable.empty[T]): GenericSwaggerTypeable[T] = {
    def required = Eval.later(list.props.collect { case (name, tt) if !tt.value._2 => name }.toVector)

    def props = list.props.map { case (name, tt) => SwaggerProperty(name, descr.element(name), tt.map(_._1)) }.toVector

    make[T](SwaggerObject(props, required), descr.whole)
  }

  implicit val cNilAlts = CoproductAlts[CNil](Nil)

  implicit def cConsAlts[Name <: Symbol, Value, Tail <: Coproduct]
  (implicit headAlt: Lazy[SwaggerTypeable[Value]], tail: CoproductAlts[Tail], name: Witness.Aux[Name], cfg: Config = SwaggerTypeable.defaultConfig) = {
    val keepName = cfg.discriminator.isDefined || !cfg.plainCoproducts
    val altName = Some(name.value.name).filter(_ => keepName) map cfg.altMod
    CoproductAlts[FieldType[Name, Value] :+: Tail]((altName -> headAlt.later) :: tail.alts)
  }

  implicit def genericSumTypeable[T, C <: Coproduct]
  (implicit gen: LabelledGeneric.Aux[T, C],
   sum: CoproductAlts[C],
   cfg: Config = SwaggerTypeable.defaultConfig,
   descr: DescribeTypeable[T] = DescribeTypeable.empty[T]): GenericSwaggerTypeable[T] =
    make[T](SwaggerOneOf(
      sum.alts.map { case (name, typ) => name -> typ.map(_.describeWith(name.flatMap(descr.element))) }.toVector,
      cfg.discriminator
    ), descr.whole)
}

sealed trait CirceSwaggerTypeableInstances {
  implicit def jsonObjectSwagger: SwaggerTypeable[JsonObject] = SwaggerTypeable.make(SwaggerObject()).as[JsonObject]
}

trait DescribeTypeable[T] {
  def whole: Option[String]
  def element(name: String): Option[SwaggerDescription]
}

object DescribeTypeable {
  def apply[T](implicit instance: DescribeTypeable[T]): DescribeTypeable[T] = instance

  def empty[T]: DescribeTypeable[T] = new DescribeTypeable[T] {
    def whole = None
    def element(name: String) = None
  }

  def make[T](wholeDescr: String, elements: (String, String)*): DescribeTypeable[T] = new DescribeTypeable[T] {
    private[this] val map = elements.toMap
    override def element(name: String) = map.get(name)
    override val whole = Some(wholeDescr)
  }
}

object MagnoliaSwagger {
  type Typeclass[T] = SwaggerTypeable[T]

  def combine[T](caseClass: CaseClass[Typeclass, T])(
    implicit cfg: Config = SwaggerTypeable.defaultConfig,
    desc: DescribeTypeable[T] = DescribeTypeable.empty[T]): SwaggerTypeable[T] =
    new Typeclass[T] {
      lazy val typ: SwaggerType =
        SwaggerRef(
          name = cfg.nameMod(caseClass.typeName.short),
          descr = desc.whole,
          typ = Eval.now(SwaggerObject(
            properties = caseClass.parameters.map { param =>
              SwaggerProperty(
                name = cfg.propMod(param.label),
                description = desc.element(param.label),
                typ = Eval.later(param.typeclass.typ)
              )
            }.toVector,
            required = Eval.later(caseClass.parameters.toVector
              .collect { case prop if !prop.typeclass.optional => cfg.propMod(prop.label) }))))
    }

  def dispatch[T](sealedTrait: SealedTrait[Typeclass, T])(
    implicit cfg: Config = SwaggerTypeable.defaultConfig,
    desc: DescribeTypeable[T] = DescribeTypeable.empty[T]): Typeclass[T] =
    new Typeclass[T] {
      lazy val typ: SwaggerType =
        SwaggerRef(
          name = cfg.nameMod(sealedTrait.typeName.short),
          descr = desc.whole,
          typ = Eval.now(SwaggerOneOf(
            alts = sealedTrait.subtypes.map { sub =>
              cfg.altMod(sub.typeName.short).some -> Eval.later(sub.typeclass.typ)
            }.toVector,
            discriminator = cfg.discriminator
          )))
    }

  final implicit def swaggerListTypeable[T: SwaggerTypeable]: SwaggerTypeable[List[T]] = seq[List, T]

  implicit def derivedInstance[T]: Typeclass[T] = macro Magnolia.gen[T]
  def derive[T]: Typeclass[T] = macro Magnolia.gen[T]
}
