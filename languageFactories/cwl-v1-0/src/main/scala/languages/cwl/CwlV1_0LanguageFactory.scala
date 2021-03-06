package languages.cwl

import better.files.File
import cats.Monad
import cats.data.EitherT.fromEither
import cats.effect.IO
import common.validation.Parse.{Parse, errorOrParse, goParse, tryParse}
import cromwell.core.path.DefaultPathBuilder
import cromwell.core.{WorkflowId, WorkflowOptions, WorkflowSourceFilesCollection, WorkflowSourceFilesWithDependenciesZip}
import cromwell.languages.util.LanguageFactoryUtil
import cromwell.languages.{LanguageFactory, ValidatedWomNamespace}
import cwl.CwlDecoder

class CwlV1_0LanguageFactory() extends LanguageFactory {
  override def validateNamespace(source: WorkflowSourceFilesCollection,
                                 workflowOptions: WorkflowOptions,
                                 importLocalFilesystem: Boolean,
                                 workflowIdForLogging: WorkflowId): Parse[ValidatedWomNamespace] = {
    // TODO WOM: CwlDecoder takes a file so write it to disk for now

    def writeCwlFileToNewTempDir(): Parse[File] = {
      goParse {
        val tempDir = File.newTemporaryDirectory(prefix = s"$workflowIdForLogging.temp.")
        val cwlFile: File = tempDir./(s"$workflowIdForLogging.cwl").write(source.workflowSource)
        cwlFile
      }
    }

    def unzipDependencies(cwlFile: File): Parse[Unit] = {
      source match {
        case wsfwdz: WorkflowSourceFilesWithDependenciesZip =>
          for {
            parent <- tryParse(DefaultPathBuilder.build(cwlFile.parent.pathAsString))
            _ <- errorOrParse(LanguageFactoryUtil.validateImportsDirectory(wsfwdz.importsZip, Option(parent)))
          } yield ()
        case _ => Monad[Parse].unit
      }
    }

    import cwl.AcceptAllRequirements
    for {
      cwlFile <- writeCwlFileToNewTempDir()
      _ <- unzipDependencies(cwlFile)
      cwl <- CwlDecoder.decodeCwlFile(cwlFile, source.workflowRoot)
      executable <- fromEither[IO](cwl.womExecutable(AcceptAllRequirements, Option(source.inputsJson)))
      validatedWomNamespace <- fromEither[IO](LanguageFactoryUtil.validateWomNamespace(executable))
      _ <- CwlDecoder.todoDeleteCwlFileParentDirectory(cwlFile.parent)
    } yield validatedWomNamespace
  }

}
