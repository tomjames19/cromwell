package cwl

import cats.instances.list._
import cats.syntax.traverse._
import cats.syntax.validated._
import cats.syntax.option._
import common.validation.ErrorOr.ErrorOr
import common.validation.Validation._
import io.circe.{Json, JsonNumber, JsonObject}
import io.circe._
import io.circe.generic.auto._
import io.circe.refined._
import io.circe.literal._
import wom.executable.Executable.DelayedCoercionFunction
import wom.types._
import wom.values._

private [cwl] object CwlInputCoercion extends Json.Folder[DelayedCoercionFunction] {
  import cwl.decoder._
  implicit val fileD = implicitly[Decoder[File]]
  implicit val directoryD = implicitly[Decoder[Directory]]
  
  private def simpleCoercion(value: Any)(womType: WomType) = womType.coerceRawValue(value).toErrorOr

  override def onNull = womType => { WomOptionalValue.none(womType).validNel }
  override def onBoolean(value: Boolean) = simpleCoercion(value)
  override def onNumber(value: JsonNumber) = {
    case WomFloatType => WomFloat(value.toDouble).validNel
    case WomIntegerType => value.toInt.map(WomInteger.apply).toValidNel(s"$value is not a valid Int")
    case other => other.coerceRawValue(value.toString).toErrorOr
  }
  override def onString(value: String) = simpleCoercion(value)

  override def onArray(value: Vector[Json]) = {
    case womArrayType: WomArrayType =>
      value.toList
        .traverse[ErrorOr, WomValue](_.foldWith(CwlInputCoercion).apply(womArrayType.memberType))
        .map {
          WomArray(womArrayType, _)
        }

    case other => s"Cannot convert an array input value into a non array type: $other".invalidNel
  }

  override def onObject(value: JsonObject) = {
    // CWL files are represented as Json objects, so this could be a file 
    case WomSingleFileType | WomMaybePopulatedFileType if value.toMap.get("class").flatMap(_.asString).contains("File") =>
      Json.fromJsonObject(value).as[File] match {
        case Left(errors) => errors.message.invalidNel
        case Right(file) => file.asWomValue
      }
    case WomMaybeListedDirectoryType | WomUnlistedDirectoryType if value.toMap.get("class").flatMap(_.asString).contains("Directory") =>
      Json.fromJsonObject(value).as[Directory] match {
        case Left(errors) => errors.message.invalidNel
        case Right(directory) => directory.asWomValue
      }
    case WomObjectType =>
      val foldedMap = value.toList.traverse[ErrorOr, (String, WomValue)]({
        case (k, v) => v.foldWith(CwlInputCoercion).apply(WomAnyType).map(k -> _)
      }).map(_.toMap[String, WomValue])

      foldedMap.map(WomObject.apply)
    case other => s"Cannot convert an array input value into a non array type: $other".invalidNel
  }
}
