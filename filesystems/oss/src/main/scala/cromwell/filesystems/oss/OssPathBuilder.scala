package cromwell.filesystems.oss

import java.net.URI

import com.google.common.net.UrlEscapers
import cromwell.core.WorkflowOptions
import cromwell.core.path.{NioPath, Path, PathBuilder}
import cromwell.filesystems.oss.OssPathBuilder._
import cromwell.filesystems.oss.nio.{OssStorageConfiguration, OssStorageFileSystem, OssStoragePath}

import scala.language.postfixOps
import scala.util.matching.Regex
import scala.util.{Failure, Try}

object OssPathBuilder {

  val URI_SCHEME = OssStorageFileSystem.URI_SCHEMA

  val OssBucketPattern:Regex =
    """
      (?x)                                      # Turn on comments and whitespace insensitivity
      ^oss://
      (                                         # Begin capturing group for oss bucket name
        [a-z0-9][a-z0-9-_\\.]+[a-z0-9]          # Regex for bucket name - soft validation, see comment above
      )                                         # End capturing group for gcs bucket name
      (?:
        /.*                                     # No validation here
      )?
    """.trim.r

  sealed trait OssPathValidation

  case class ValidFullOssPath(bucket: String, path: String) extends OssPathValidation

  case object PossiblyValidRelativeOssPath extends OssPathValidation

  sealed trait InvalidOssPath extends OssPathValidation {
    def pathString: String
    def errorMessage: String
  }

  final case class InvalidScheme(pathString: String) extends InvalidOssPath {
    def errorMessage = s"OSS URIs must have 'oss' scheme: $pathString"
  }

  final case class InvalidFullOssPath(pathString: String) extends InvalidOssPath {
    def errorMessage = {
      s"""
         |The path '$pathString' does not seem to be a valid OSS path.
         |Please check that it starts with oss:// and that the bucket and object follow OSS naming guidelines.
      """.stripMargin.replaceAll("\n", " ").trim
    }
  }

  final case class UnparseableOssPath(pathString: String, throwable: Throwable) extends InvalidOssPath {
    def errorMessage: String =
      List(s"The specified OSS path '$pathString' does not parse as a URI.", throwable.getMessage).mkString("\n")
  }

  private def softBucketParsing(string: String): Option[String] = string match {
    case OssBucketPattern(bucket) => Option(bucket)
    case _ => None
  }

  def validateOssPath(string: String): OssPathValidation = {
    Try {
      val uri = URI.create(UrlEscapers.urlFragmentEscaper().escape(string))
      if (uri.getScheme == null) PossiblyValidRelativeOssPath
      else if (uri.getScheme.equalsIgnoreCase(URI_SCHEME)) {
        if (uri.getHost == null) {
          softBucketParsing(string) map { ValidFullOssPath(_, uri.getPath) } getOrElse InvalidFullOssPath(string)
        } else ValidFullOssPath(uri.getHost, uri.getPath)
      } else InvalidScheme(string)
    } recover { case t => UnparseableOssPath(string, t) } get
  }

  def isOssPath(nioPath: NioPath): Boolean = {
    nioPath.getFileSystem.provider().getScheme.equalsIgnoreCase(URI_SCHEME)
  }

  def fromConfiguration(endpoint: String,
                        accessId: String,
                        accessKey: String,
                        securityToken: Option[String],
                        options: WorkflowOptions): OssPathBuilder = {

    val configuration = OssStorageConfiguration(endpoint, accessId, accessKey, securityToken)

    OssPathBuilder(configuration)
  }
}

final case class OssPathBuilder(ossStorageConfiguration: OssStorageConfiguration) extends PathBuilder {
  def build(string: String): Try[OssPath] = {
    validateOssPath(string) match {
      case ValidFullOssPath(bucket, path) =>
        Try {
          val nioPath = OssStorageFileSystem(bucket, ossStorageConfiguration).getPath(path)
          OssPath(nioPath)
        }
      case PossiblyValidRelativeOssPath => Failure(new IllegalArgumentException(s"$string does not have a oss scheme"))
      case invalid: InvalidOssPath => Failure(new IllegalArgumentException(invalid.errorMessage))
    }
  }

  override def name: String = "oss"
}

final case class BucketAndObj(bucket: String, obj: String)

final case class OssPath private[oss](nioPath: NioPath) extends Path {

  override def newPath(path: NioPath): OssPath = {
    OssPath(path)
  }

  override def pathAsString: String = ossStoragePath.pathAsString

  override def pathWithoutScheme: String = {
    ossStoragePath.bucket + ossStoragePath.toAbsolutePath.toString
  }

  def bucket: String = {
    ossStoragePath.bucket
  }

  def key: String = {
    ossStoragePath.key
  }

  def ossStoragePath: OssStoragePath = nioPath match {
    case ossPath: OssStoragePath => ossPath
    case _ => throw new RuntimeException(s"Internal path was not a cloud storage path: $nioPath")
  }
}
