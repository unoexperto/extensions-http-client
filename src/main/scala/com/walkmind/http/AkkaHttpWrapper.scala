package com.walkmind.http

import java.nio.charset.Charset

import akka.NotUsed
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, MergeHub, Sink, Source}
import cats.Applicative
import cats.effect.concurrent.Deferred
import cats.effect.{ContextShift, IO}

import scala.collection.compat._
import scala.concurrent.duration.FiniteDuration
import scala.util.Try

class AkkaHttpWrapper(flow: Flow[(HttpRequest, Deferred[IO, Try[HttpResponse]]), (Try[HttpResponse], Deferred[IO, Try[HttpResponse]]), _],
                      fetchingTimeout: FiniteDuration)(implicit mat: Materializer) {

  private implicit val cs: ContextShift[IO] = IO.contextShift(mat.executionContext)

  private val completionSink: Sink[(HttpRequest, Deferred[IO, Try[HttpResponse]]), NotUsed] =
    MergeHub.source[(HttpRequest, Deferred[IO, Try[HttpResponse]])]
      .via(flow)
      .mapAsync(Runtime.getRuntime.availableProcessors()) {
        case (tried, p) =>
          p.complete(tried).unsafeToFuture()
      }
      .to(Sink.ignore)
      .run()

  def sendTypedRequest[F[_], T](request: HttpRequest)(implicit appl: Applicative[F], um: AHCErrorHandler[F], decoder: AHCBodyDecoder[T]): IO[F[T]] = {

    val req = new RequestLike {
      override def getUrl: String = request.uri.toString
      override def getMethod: String = request.method.value
      override def getCookies: Map[String, Seq[String]] =
        request.cookies.map(c => c.name -> c.value)
          .view.groupBy { case (key, _) => key }
          .view.mapValues(_.map { case (_, value) => value }.toSeq)
          .toMap
    }

    try {
      val defer = Deferred.unsafeUncancelable[IO, Try[HttpResponse]]
      Source.single(request -> defer).runWith(completionSink)
      defer.get
        .flatMap(IO.fromTry)
        .flatMap(res => IO.fromFuture(IO.delay(res.entity.toStrict(fetchingTimeout))).map(e => res -> e.getData().toArray))
        .map { case (res, data) =>
          try {
            val responseLike = new ResponseLike {
              override def getUrl: String = request.getUri().toString
              override def getCharset: Charset = res.entity.contentType.charsetOption.map(_.nioCharset()).getOrElse(Charset.forName("UTF-8"))
              override def getBody: Array[Byte] = data
              override def getHeaders: Map[String, Seq[String]] =
                res.headers.map(h => h.name() -> h.value())
                  .view.groupBy { case (key, _) => key }
                  .view.mapValues(_.map { case (_, value) => value }.toSeq)
                  .toMap
            }

            val pf: PartialFunction[(Int, RequestLike), Try[F[T]]] = {
              case (code, _) if um.isValidStatusCode(code) =>
                Try(appl.pure(decoder.decode(responseLike)))
            }

            pf.orElse(um.handleInvalidStatusCode[T])(res.status.intValue() -> req)
          } catch {
            case ex: Throwable =>
              um.handleResponseException[T](ex -> req)
          }
        }
        .flatMap(IO.fromTry)
    } catch {
      case ex: Throwable =>
        IO.fromTry(um.handleResponseException[T](ex -> req))
    }
  }
}

