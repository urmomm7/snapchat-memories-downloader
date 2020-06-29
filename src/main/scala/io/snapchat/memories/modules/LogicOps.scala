package io.snapchat.memories
package modules

import java.util.concurrent.TimeUnit
import java.lang.{Runtime => JRuntime}
import java.text.SimpleDateFormat

import com.github.mlangc.slf4zio.api._
import zio._

import models._

object LogicOps extends LoggingSupport {

  private val NotDownloadedFileName   = "not-downloaded-memories"
  private val NotUpdatedDatesFileName = "not-updated-dates-memories"

  private case class ResultReport(
    savedMediasCount: Int,
    notSavedDates: List[MediaSetDateFailed],
    notSavedDownloads: List[MediaDownloadFailed]
  )

  def liveLayers(args: List[String]) =
    (OptionParserOps.liveLayer >>> OptionParserOps.parse(args).toLayer) ++
      JsonOps.liveLayer ++
      FileOps.liveLayer ++
      DownloaderOps.liveLayer

  def loadMemories() =
    for {
      config      <- ZIO.service[Config]
      filePath    <- Task(os.Path(config.memoriesFilePath))
      fileContent <- FileOps.readFile(filePath)
      memories    <- JsonOps.parse(fileContent)
    } yield memories

  def filterMemories(memories: SnapchatMemories) =
    filterByDateMemories(memories) >>= filterByNrOfMemories

  def downloadMemories(memories: SnapchatMemories) =
    for {
      config       <- ZIO.service[Config]
      nrOfMemories =  memories.`Saved Media`.size
      _            <- logger.infoIO(s"Got $nrOfMemories records from json file!")
      opsNr        <- ZIO.fromOption(config.nrOfOperations).orElse(UIO(JRuntime.getRuntime.availableProcessors()))
      results      <- ZIO.foreachParN(opsNr)(memories.`Saved Media`)(DownloaderOps.download)
      _            <- interpretResults(nrOfMemories, results)
    } yield ()

  private def filterByNrOfMemories(memories: SnapchatMemories) =
    ZIO.service[Config] >>= {
      _.memoriesFilter.numberOfMemories match {
        case None        => UIO(memories)
        case Some(value) =>
          if (value.takeLastMemories)
            UIO(SnapchatMemories(memories.`Saved Media`.takeRight(value.nrOfMemories)))
          else
            UIO(SnapchatMemories(memories.`Saved Media`.take(value.nrOfMemories)))
      }
    }

  private def filterByDateMemories(memories: SnapchatMemories) =
    for {
      memoriesDateFormat <- Task(new SimpleDateFormat(Config.MemoriesDateFormat))
      configDateFormat   <- Task(new SimpleDateFormat(Config.ConfigDateFormat))
      tmp1               <- beforeDateFilter(memories, configDateFormat, memoriesDateFormat)
      tmp2               <- afterDateFilter(tmp1, configDateFormat, memoriesDateFormat)
    } yield tmp2

  private def beforeDateFilter(memories: SnapchatMemories, configDateFormat: SimpleDateFormat, memoriesDateFormat: SimpleDateFormat) =
    ZIO.service[Config] >>= {
      _.memoriesFilter.memoriesBeforeDate match {
        case None             => UIO(memories)
        case Some(beforeDate) =>
          for {
            configDate <- Task(configDateFormat.parse(beforeDate))
            filtered   <- ZIO.filter(memories.`Saved Media`)(m => Task(memoriesDateFormat.parse(m.Date).before(configDate)))
          } yield SnapchatMemories(filtered)
      }
    }

  private def afterDateFilter(memories: SnapchatMemories, configDateFormat: SimpleDateFormat, memoriesDateFormat: SimpleDateFormat) =
    ZIO.service[Config] >>= {
      _.memoriesFilter.memoriesAfterDate match {
        case None            => UIO(memories)
        case Some(afterDate) =>
          for {
            configDate <- Task(configDateFormat.parse(afterDate))
            filtered   <- ZIO.filter(memories.`Saved Media`)(m => Task(memoriesDateFormat.parse(m.Date).after(configDate)))
          } yield SnapchatMemories(filtered)
      }
    }

  private def interpretResults(inputMemoriesCount: Int, results: List[MediaResult]) =
    for {
      report <- extractResults(results)
      millis <- clock.currentTime(TimeUnit.MILLISECONDS)
      _      <- saveAndLogResults(inputMemoriesCount, report, millis)
    } yield ()

  private def saveAndLogResults(inputMemoriesCount: Int, report: ResultReport, millis: Long) =
    if (report.savedMediasCount == inputMemoriesCount)
      logger.infoIO {
        s"""|************** Download finished! **************
            |************************************************
            |Successfully downloaded ${report.savedMediasCount} media files!
            |************************************************""".stripMargin
      }
    else
      saveFailedResult(report.notSavedDownloads, s"$NotDownloadedFileName-$millis.json") &>
        saveFailedResult(report.notSavedDates, s"$NotUpdatedDatesFileName-$millis.json") *>
        logger.infoIO {
          s"""|******************************** Download finished! ********************************
              |************************************************************************************
              |Successfully downloaded ${report.savedMediasCount} media files out of $inputMemoriesCount!
              |${getNotSavedDownloadsMessage(report.notSavedDownloads.size, millis)}
              |${getNotUpdatedDatesMessage(report.notSavedDates.size, millis)}
              |************************************************************************************""".stripMargin
        }

  private def getNotSavedDownloadsMessage(notSavedDownloadsCount: Int, millis: Long): String =
    if (notSavedDownloadsCount > 0)
      s"""|A number of $notSavedDownloadsCount media files were not downloaded because of various reasons,
          |but a new JSON file called '$NotDownloadedFileName-$millis.json'
          |was exported containing this data which can be used later to retry the download.""".stripMargin
    else ""

  private def getNotUpdatedDatesMessage(noDatesUpdatedCount: Int, millis: Long): String =
    if (noDatesUpdatedCount > 0)
      s"""|A number of $noDatesUpdatedCount media files don't have the last modified date updated due to various reason,
          |but a new JSON file called '$NotUpdatedDatesFileName-$millis.json'
          |was exported containing this data which can be used later to retry the download.""".stripMargin
    else ""

  private def extractResults(results: List[MediaResult]): Task[ResultReport] = {
    val savedZ       = UIO(results collect { case r @ MediaSaved => r }).map(_.size)
    val noDatesZ     = UIO(results collect { case r: MediaSetDateFailed => r })
    val noDownloadsZ = UIO(results collect { case r: MediaDownloadFailed => r })
    for {
      ((savedSize, noDates), noDownloads) <- savedZ <&> noDatesZ <&> noDownloadsZ
    } yield ResultReport(savedSize, noDates, noDownloads)
  }

  private def saveFailedResult(results: List[MediaResultFailed], fileName: String) =
    (
      for {
        json <- JsonOps.toJson(SnapchatMemories(results.map(_.media)))
        _    <- FileOps.writeFile(os.pwd / os.RelPath(fileName), json)
      } yield ()
    )
      .catchAll(e => logger.errorIO(e.getMessage, e))
      .when(results.nonEmpty)


}