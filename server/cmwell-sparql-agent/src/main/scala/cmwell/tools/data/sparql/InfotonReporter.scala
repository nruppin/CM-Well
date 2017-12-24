/**
  * Copyright 2015 Thomson Reuters
  *
  * Licensed under the Apache License, Version 2.0 (the “License”); you may not use this file except in compliance with the License.
  * You may obtain a copy of the License at
  *
  *   http://www.apache.org/licenses/LICENSE-2.0
  *
  * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
  * an “AS IS” BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  *
  * See the License for the specific language governing permissions and
  * limitations under the License.
  */


package cmwell.tools.data.sparql

import akka.actor.{Actor, ActorRef, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.RawHeader
import akka.stream.{ActorMaterializer, Materializer}
import akka.pattern._
import akka.stream.scaladsl.{Sink, Source}
import cmwell.tools.data.downloader.consumer.Downloader.Token
import cmwell.tools.data.sparql.InfotonReporter.{RequestDownloadStats, ResponseDownloadStats}
import cmwell.tools.data.utils.ArgsManipulations
import cmwell.tools.data.utils.ArgsManipulations.HttpAddress
import cmwell.tools.data.utils.akka.stats.DownloaderStats.DownloadStats
import cmwell.tools.data.utils.logging.DataToolsLogging
import com.typesafe.config.ConfigFactory

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

object InfotonReporter {
  case object RequestDownloadStats
  case class ResponseDownloadStats(stats: Map[String, DownloadStats])
  def apply(baseUrl: String, path: String)(implicit mat: Materializer, ec: ExecutionContext) = Props(new InfotonReporter(baseUrl, path))
}

class InfotonReporter private(baseUrl: String, path: String)(implicit mat: Materializer, ec: ExecutionContext) extends Actor with SparqlTriggerProcessorReporter with DataToolsLogging {
  if(mat.isInstanceOf[ActorMaterializer]) {
    require(mat.asInstanceOf[ActorMaterializer].system eq context.system, "ActorSystem of materializer MUST be the same as the one used to create current actor")
  }
  val HttpAddress(protocol, host, port, _) = ArgsManipulations.extractBaseUrl(baseUrl)
  val format = "ntriples"
  val writeToken = ConfigFactory.load().getString("cmwell.agents.sparql-triggered-processor.write-token")
  var downloadStats: Map[String, DownloadStats] = Map()

  def extractLastPart(path: String) = {
    val p = if (path.endsWith("/")) path.init
    else path
    val (_, name) = p.splitAt(p.lastIndexOf("/"))
    name.tail.init
  }

  val name = extractLastPart(path)

  override def preStart(): Unit = readPreviousTokens().onComplete(self ! _)

  override val receive: Receive = receiveBeforeInitializes(Nil) //receiveWithMap(Map.empty)


  def receiveBeforeInitializes(recipients: List[ActorRef]) : Receive= {
    case RequestPreviousTokens =>
      context.become(receiveBeforeInitializes(sender() :: recipients))

    case Success(savedTokens: Map[String, Token]) =>
      recipients.foreach(_ ! ResponseWithPreviousTokens(savedTokens))
      context.become(receiveWithMap(savedTokens))

    case s:DownloadStats =>
      downloadStats += (s.label.getOrElse("") -> s)

    case RequestDownloadStats =>
      sender() ! ResponseDownloadStats(downloadStats)

    case Failure(ex) =>
      logger.error("cannot read previous tokens infoton")
      context.become(receiveWithMap(Map.empty))

    case RequestReference(path) =>
      val data = getReferencedData(path)
      data.map(ResponseReference.apply) pipeTo sender()
  }

  def receiveWithMap(tokens: Map[String, Token]): Receive = {
    case RequestPreviousTokens =>
      sender() ! ResponseWithPreviousTokens(tokens)

    case ReportNewToken(sensor, token) =>
      val updatedTokens = tokens + (sensor -> token)
      saveTokens(updatedTokens)
      context.become(receiveWithMap(updatedTokens))

    case s:DownloadStats =>
      downloadStats += (s.label.getOrElse("") -> s)

    case RequestDownloadStats =>
      sender() ! ResponseDownloadStats(downloadStats)

    case RequestReference(path) =>
      val data = getReferencedData(path)
      data.map(ResponseReference.apply) pipeTo sender()
  }

  def readPreviousTokens() = {
    import cmwell.util.http.SimpleResponse.Implicits.UTF8StringHandler

    cmwell.util.http.SimpleHttpClient.get(s"http://$baseUrl$path/tokens?op=stream&recursive&format=$format")
      .map(
        _.payload.split("\n")
        .map(_.split(" "))
        .collect { case Array(s, p, o, _) =>
          val token = if (o.startsWith("\"")) o.init.tail else o
          extractLastPart(s) -> token
        }
        .foldLeft(Map.empty[String, Token])(_ + _)
      )
  }

  override def getReferencedData(path: String): Future[String] = {
    import cmwell.util.http.SimpleResponse.Implicits.UTF8StringHandler
    cmwell.util.http.SimpleHttpClient.get(s"http://$baseUrl$path")
      .map(_.payload)
  }

  override def saveTokens(tokens: Map[String, Token]): Unit = {
    def createRequest(tokens: Map[String, Token]) = {
      val data = HttpEntity(tokens.foldLeft(Seq.empty[String]){case (agg,(sensor, token)) => agg :+ createTriple(sensor, token) }.mkString("\n"))
      HttpRequest(uri = s"http://$host:$port/_in?format=$format&replace-mode", method = HttpMethods.POST, entity = data)
        .addHeader(RawHeader("X-CM-WELL-TOKEN", writeToken))
    }

    def createTriple(sensor: String, token: Token) = {
      val p = if (path startsWith "/") path.tail else path
      s"""<cmwell://$p/tokens/$sensor> <cmwell://meta/nn#token> "$token" ."""
    }

    Source.single(tokens)
      .map(createRequest)
      .via(Http(context.system).outgoingConnection(host, port))
      .map {
        case HttpResponse(s, h, e, _) if s.isSuccess() =>
          logger.debug(s"successfully written tokens infoton to $path")
          e.discardBytes()
        case HttpResponse(s, h, e, _)  =>
          logger.error(s"problem writing tokens infoton to $path")
          e.discardBytes()
      }
      .runWith(Sink.ignore)
  }
}