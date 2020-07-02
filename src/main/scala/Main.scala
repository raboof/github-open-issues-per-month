import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration._

import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl._
import akka.http.scaladsl.client._
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.headers._
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source

import akka.http.scaladsl.server.Directives
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import spray.json._
import akka.http.scaladsl.unmarshalling._

object Main extends App {
  case class Issue(number: Integer, created_at: String, closed_at: Option[String]);
  object Issue {
    def apply(json: JsObject): Issue = 
      json.getFields("number", "created_at", "closed_at") match {
        case List(JsNumber(number), JsString(created_at), JsString(closed_at)) =>
          Issue(number.toInt, created_at, Some(closed_at))
        case List(JsNumber(number), JsString(created_at), _) =>
          Issue(number.toInt, created_at, None)
      }
  }

  implicit val system = ActorSystem(Behaviors.ignore, "root")
  implicit val ec = scala.concurrent.ExecutionContext.global

  def get(url: Uri): Future[(Option[Uri], Vector[JsObject])] = {
    import RequestBuilding._
    Http(system.classicSystem)
      .singleRequest(Get(url).addHeader(Authorization(OAuth2BearerToken("INSERT HERE"))))
      .flatMap(response => {
          val link = response.header[Link]
          println(link)
          // TODO handle rate limiting...
          if (!link.isDefined) println(response)
          // TODO using the 'next' links here limits concurrency,
          // so an optimization would be to make assumptions
          val next = link.get.values.find(_.params.find(p => p.key == "rel" && p.value == "next").isDefined)
          Unmarshal(response).to[JsValue].map(_ match {
            case JsArray(elements) => {
              (next.map(_.uri), elements.map(_.asInstanceOf[JsObject]))
      }})})
  }

  def getPaged(rootUrl: Uri): Source[JsObject, _] = {
    Source.unfoldAsync[Option[Uri], Seq[JsObject]](Some(rootUrl))(_ match {
      case None => Future.successful(None)
      case Some(url) => get(url).map(Some(_))
    })
      .flatMapConcat(seq => Source(seq.toList))
  }

  def getIssues(owner: String, repo: String): Source[Issue, _] = {
    getPaged(s"https://api.github.com/repos/$owner/$repo/issues?state=all")
      .filter(_.fields.get("pull_request") == None)
      .map(Issue(_))
  }

  case class Summary(created: Map[String, Int], closed: Map[String, Int]) {
    def updateWith(issue: Issue): Summary = {
      val cr = issue.created_at.take(7)
      Summary(
        created.updated(cr, created.get(cr).getOrElse(0) + 1),
        issue.closed_at match {
          case None => closed
          case Some(closed_at) =>
            val cl = closed_at.take(7)
            closed.updated(cl, closed.get(cl).getOrElse(0) + 1)
        })
    }
    override def toString: String = {
      (created.keys ++ closed.keys)
        .toList
        .sorted
        .map(key => s"$key\t${created.get(key).getOrElse(0)}\t${closed.get(key).getOrElse(0)}")
        .mkString("\n")
    }
  }
  object Summary {
    val empty = Summary(Map.empty, Map.empty)
  }

  try {
    val done = getIssues("nixos", "nixpkgs")
      .runWith(Sink.fold[Summary, Issue](Summary.empty)((m, issue) =>
          m.updateWith(issue)))
    Await.result(done, 10.hours)
    done.foreach(println)
  } finally {
    system.terminate()
  }
}
