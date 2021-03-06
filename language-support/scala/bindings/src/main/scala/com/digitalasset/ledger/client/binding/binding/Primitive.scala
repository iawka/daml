// Copyright (c) 2019 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.ledger.client.binding

import com.digitalasset.ledger.api.refinements.ApiTypes
import com.digitalasset.ledger.api.v1.{commands => rpccmd, value => rpcvalue}
import scalaz.syntax.std.boolean._
import scalaz.syntax.tag._

import scala.collection.{immutable => imm}
import scala.language.higherKinds
import java.time.{Instant, LocalDate, LocalDateTime}
import java.util.TimeZone
import com.github.ghik.silencer.silent

sealed abstract class Primitive {
  type Int64 = Long
  type Decimal = BigDecimal
  type Party = ApiTypes.Party
  val Party: ApiTypes.Party.type = ApiTypes.Party
  type Text = String

  /** A [[LocalDate]] in the range [0001-01-01, 9999-12-31] (`[Date.MIN,
    * Date.MAX]`).  This is the range that can be stored as primitive
    * `Date`s on a ledger, and matches the set of representable dates in
    * the [RFC-3339](https://www.ietf.org/rfc/rfc3339.txt) date-time
    * format.  Any [[LocalDate]] in that range can be converted to a
    * [[Date]] by calling `Date.fromLocalDate`.
    */
  type Date <: LocalDate
  val Date: DateApi

  /** An [[Instant]] with only microsecond resolution in the range
    * [0001-01-01T00:00:00Z, 9999-12-31T23:59:59.999999Z] (`[Timestamp.MIN,
    * Timestamp.MAX]`).  Only such times can be stored as primitive `Time`s
    * on the ledger.  Any [[Instant]] in that range can be converted to
    * a [[Timestamp]] by calling `Time.discardNanos`.
    */
  type Timestamp <: Instant
  val Timestamp: TimeApi
  type Unit = scala.Unit
  type Bool = scala.Boolean
  type List[+A] = imm.Seq[A]
  val List: imm.Seq.type = imm.Seq

  type Optional[+A] = scala.Option[A]
  val Optional: scala.Option.type = scala.Option

  type Map[+A] = imm.Map[String, A]
  val Map: imm.Map.type = imm.Map

  type ChoiceId = ApiTypes.Choice
  val ChoiceId: ApiTypes.Choice.type = ApiTypes.Choice

  // abstract primitives
  type ContractId[+Tpl] <: ApiTypes.ContractId
  val ContractId: ContractIdApi
  type TemplateId[+Tpl] <: ApiTypes.TemplateId
  val TemplateId: TemplateIdApi
  type Update[+A] <: DomainCommand

  sealed abstract class DateApi {
    val MIN: Date
    val MAX: Date

    /** Narrow `ld` if it's in the `[MIN, MAX]` range, `None` otherwise. */
    def fromLocalDate(ld: LocalDate): Option[Date]

    // bypass the value test
    private[binding] def subst[F[_]](tc: F[LocalDate]): F[Date]
  }

  sealed abstract class TimeApi {
    val MIN: Timestamp
    val MAX: Timestamp

    /** Reduce `t`'s resolution to exclude nanoseconds; return `None` if outside
      * the `[MIN, MAX]` range, the reduced-resolution value
      * otherwise.
      */
    def discardNanos(t: Instant): Option[Timestamp]

    // bypass the value test
    private[binding] def subst[F[_]](tc: F[Instant]): F[Timestamp]
  }

  sealed abstract class ContractIdApi {
    def apply[Tpl <: Template[Tpl]](contractId: String): ContractId[Tpl]
    def subst[F[_], Tpl](tc: F[ApiTypes.ContractId]): F[ContractId[Tpl]]
  }

  sealed abstract class TemplateIdApi {
    // private as the sole source of valid Template-associated template IDs is
    // their codegenned companions
    @deprecated("Use 3-argument version instead", since = "15.0.0")
    def apply[Tpl <: Template[Tpl]](packageId: String, name: String): TemplateId[Tpl]

    def apply[Tpl <: Template[Tpl]](
        packageId: String,
        moduleName: String,
        entityName: String): TemplateId[Tpl]

    private[binding] def substEx[F[_]](fa: F[rpcvalue.Identifier]): F[TemplateId[_]]

    // @deprecated("Use 3-argument version instead", since = "15.0.0")
    def unapply[Tpl](t: TemplateId[Tpl]): Option[(String, String)]

    // def unapply[Tpl](t: TemplateId[Tpl]): Option[(String, String, String)]
  }

  private[digitalasset] object LegacyIdentifier {
    // suppress deprecation warnings because we _need_ to use the deprecated .name here -- the entire
    // point of this method is to process it.
    @silent
    def unapply(t: rpcvalue.Identifier): Some[(String, String)] =
      // TODO SC DEL-6727 use this instead with daml-lf value interface
      // rpcvalue.Identifier unapply t.unwrap
      t match {
        case rpcvalue.Identifier(packageId, "", moduleName, entityName) =>
          Some((packageId, s"$moduleName.$entityName"))
        case rpcvalue.Identifier(packageId, entityName, _, _) =>
          Some((packageId, entityName))
      }
  }

  private[binding] def substContractId[F[_], Tpl](tc: F[ApiTypes.ContractId]): F[ContractId[Tpl]]

  private[binding] def createFromArgs[Tpl](
      companion: TemplateCompanion[_ <: Tpl],
      na: rpcvalue.Record): Update[ContractId[Tpl]]

  private[binding] def exercise[Tpl, Out](
      templateCompanion: TemplateCompanion[Tpl],
      contractId: ContractId[Tpl],
      choiceId: String,
      argument: rpcvalue.Value): Update[Out]

  private[binding] def arguments(
      recordId: rpcvalue.Identifier,
      args: Seq[(String, rpcvalue.Value)]): rpcvalue.Record
}

private[client] object OnlyPrimitive extends Primitive {
  type Date = LocalDate
  type Timestamp = Instant
  type ContractId[+Tpl] = ApiTypes.ContractId
  type TemplateId[+Tpl] = ApiTypes.TemplateId
  type Update[+A] = DomainCommand

  object Date extends DateApi {
    import com.digitalasset.api.util.TimestampConversion
    private val UTC = TimeZone.getTimeZone("UTC")
    override val MIN = LocalDateTime.ofInstant(TimestampConversion.MIN, UTC.toZoneId).toLocalDate
    override val MAX = LocalDateTime.ofInstant(TimestampConversion.MAX, UTC.toZoneId).toLocalDate

    override def fromLocalDate(ld: LocalDate) = {
      import scala.math.Ordering.Implicits._
      val ldc: java.time.chrono.ChronoLocalDate = ld
      (ldc >= MIN && ldc <= MAX) option ld
    }

    private[binding] override def subst[F[_]](tc: F[LocalDate]) = tc
  }

  object Timestamp extends TimeApi {
    import com.digitalasset.api.util.TimestampConversion
    override val MIN = TimestampConversion.MIN
    override val MAX = TimestampConversion.MAX

    override def discardNanos(t: Instant) = {
      import scala.math.Ordering.Implicits._
      (t >= MIN && t <= MAX) option (t truncatedTo java.time.temporal.ChronoUnit.MICROS)
    }

    private[binding] override def subst[F[_]](tc: F[Instant]) = tc
  }

  object TemplateId extends TemplateIdApi {
    override def apply[Tpl <: Template[Tpl]](packageId: String, name: String) =
      ApiTypes.TemplateId(rpcvalue.Identifier(packageId = packageId, name = name))

    // the ledger api still uses names with only dots in them, while QualifiedName.toString
    // separates the module and the name in the module with colon.
    override def apply[Tpl <: Template[Tpl]](
        packageId: String,
        moduleName: String,
        entityName: String): TemplateId[Tpl] =
      ApiTypes.TemplateId(
        rpcvalue.Identifier(
          packageId = packageId,
          name = s"$moduleName.$entityName",
          moduleName = moduleName,
          entityName = entityName))

    private[binding] override def substEx[F[_]](fa: F[rpcvalue.Identifier]) =
      ApiTypes.TemplateId subst fa

    override def unapply[Tpl](t: TemplateId[Tpl]): Option[(String, String)] =
      // TODO SC DEL-6727 use this instead with daml-lf value interface
      // rpcvalue.Identifier unapply t.unwrap
      LegacyIdentifier unapply t.unwrap
  }

  object ContractId extends ContractIdApi {
    override def apply[Tpl <: Template[Tpl]](contractId: String) =
      ApiTypes.ContractId(contractId)

    override def subst[F[_], Tpl](tc: F[ApiTypes.ContractId]): F[ContractId[Tpl]] = tc
  }

  private[binding] override def substContractId[F[_], Tpl](
      tc: F[ApiTypes.ContractId]): F[ContractId[Tpl]] = tc

  private[binding] override def createFromArgs[Tpl](
      companion: TemplateCompanion[_ <: Tpl],
      na: rpcvalue.Record): Update[ContractId[Tpl]] =
    DomainCommand(
      rpccmd.Command(
        rpccmd.Command.Command
          .Create(rpccmd.CreateCommand(templateId = Some(companion.id.unwrap), Some(na)))),
      companion)

  private[binding] override def exercise[Tpl, Out](
      templateCompanion: TemplateCompanion[Tpl],
      contractId: ContractId[Tpl],
      choiceId: String,
      argument: rpcvalue.Value): Update[Out] =
    DomainCommand(
      rpccmd.Command(
        rpccmd.Command.Command.Exercise(
          rpccmd.ExerciseCommand(
            templateId = Some(templateCompanion.id.unwrap),
            contractId = contractId.unwrap,
            choice = choiceId,
            choiceArgument = Some(argument)
          ))),
      templateCompanion
    )

  private[binding] override def arguments(
      recordId: rpcvalue.Identifier,
      args: Seq[(String, rpcvalue.Value)]): rpcvalue.Record =
    rpcvalue.Record(recordId = Some(recordId), args.map {
      case (k, v) => rpcvalue.RecordField(k, Some(v))
    })
}
