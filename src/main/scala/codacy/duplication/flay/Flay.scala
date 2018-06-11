package codacy.duplication.flay


import java.io.File

import codacy.docker.api.duplication.{DuplicationClone, DuplicationTool}
import codacy.docker.api.duplication._
import codacy.docker.api.utils.CommandRunner
import codacy.docker.api.{DuplicationConfiguration, Source}
import com.codacy.api.dtos.Language
import play.api.libs.json.{JsValue, Json}

import scala.util.{Failure, Properties, Success, Try}

object Flay extends DuplicationTool {

  private val defaultMinTokenMatch = 10

  override def apply(path: Source.Directory,
                     language: Option[Language],
                     options: Map[DuplicationConfiguration.Key, DuplicationConfiguration.Value]): Try[List[DuplicationClone]] = {

    val flayBinDirectory = new File("src/main/resources/flay")
    val result = CommandRunner.exec(command(path), Some(flayBinDirectory))

    (for {
      output <- result
      reports <- parseOutput(output.stdout, options, s"${path.path}/")
    } yield reports) match {
      case Left(e) => Failure(e)
      case Right(reports) => Success(reports)
    }
  }

  private def command(srcDir: Source.Directory): List[String] = {
    List("rake", s"codacy[${srcDir.path}]", "2>", "/dev/null")
  }

  private def minTokenMatch(options: Map[DuplicationConfiguration.Key, DuplicationConfiguration.Value]) = {
    options.get(DuplicationConfiguration.Key("minTokenMatch")).map(value => value: JsValue).flatMap(_.asOpt[Int]).getOrElse(defaultMinTokenMatch)
  }

  private def parseOutput(output: Seq[String], options: Map[DuplicationConfiguration.Key, DuplicationConfiguration.Value], sourcePrefix: String): Either[Throwable, List[DuplicationClone]] = {

    val jsonStringEither = output match {
      case Nil => Left(new Exception("No output"))
      case singleLine :: Nil => Right(singleLine)
      case _ => Left(new Exception(s"Output should contain a single line, it contains ${output.size}"))
    }

    def flayReportEither(jsonString: String) = {
      Json.parse(jsonString).validate[FlayReport].asEither.left.map(e => new Exception(s"Error parsing Flay output: $e"))
    }

    for {
      jsonStr <- jsonStringEither
      report <- flayReportEither(jsonStr)
    } yield {
      reportToDuplication(report, options, sourcePrefix)
    }
  }

  private def reportToDuplication(report: FlayReport, options: Map[DuplicationConfiguration.Key, DuplicationConfiguration.Value], sourcePrefix: String): List[DuplicationClone] = {
    report.clones.flatMap {
      clone =>
        val dupCloneFiles = clone.files.map {
          fileReport =>
            val startLine = fileReport.line
            val endLine = (startLine + fileReport.contents.size) - 1
            val filePath = fileReport.filename.stripPrefix("/src/").stripPrefix(sourcePrefix)
            DuplicationCloneFile(filePath, startLine, endLine)
        }

        dupCloneFiles match {
          case Nil =>
            List.empty
          case headClone :: _ =>
            val nrTokens = minTokenMatch(options)
            val nrLines = headClone.endLine - headClone.startLine + 1
            val cloneLines = clone.files.map(_.contents.mkString(Properties.lineSeparator)).mkString(Properties.lineSeparator)

            List(DuplicationClone(cloneLines, nrTokens, nrLines, dupCloneFiles))
        }
    }
  }

}

