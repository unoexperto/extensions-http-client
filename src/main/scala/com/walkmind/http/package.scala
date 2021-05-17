package com.walkmind

import java.nio.charset.Charset
import java.util.concurrent.CompletableFuture

import cats.Applicative
import cats.effect.kernel.Async
import cats.implicits._
import io.netty.handler.codec.http.cookie.DefaultCookie
import io.netty.util
import io.netty.util.concurrent.FutureListener
import org.asynchttpclient.util.HttpUtils
import org.asynchttpclient.{AsyncCompletionHandler, AsyncHttpClient, Request, RequestBuilderBase, Response}
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Document.OutputSettings.Syntax
import org.jsoup.nodes.Entities.EscapeMode
import spray.json.{JsValue, JsonParser, JsonReader, ParserInput}

import scala.jdk.CollectionConverters._
import scala.collection.compat._
import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Right, Success, Try}

//noinspection ConvertExpressionToSAM
package object http {
  def completableToAsync[F[_], T](f: => CompletableFuture[T])(implicit ce: Async[F]): F[T] =
    ce.onCancel(ce.async_[T] { k =>
      f.whenCompleteAsync((value: T, ex: Throwable) => {
        if (ex != null)
          k.apply(Left(ex))
        else
          k.apply(Right(value))
      })
    }, ce.delay(f.cancel(true)))

  implicit def wrapPromise[T](p: Promise[T]): FutureListener[T] =
    (future: util.concurrent.Future[T]) =>
      if (future.isSuccess)
        p.success(future.get)
      else
        p.failure(future.cause())

  implicit def wrapPromise(p: Promise[Try[Response]]): AsyncCompletionHandler[Response] = new AsyncCompletionHandler[Response] {
    override def onThrowable(t: Throwable): Unit = {
      p.success(Failure(t))
      super.onThrowable(t)
    }

    override def onCompleted(response: Response): Response = {
      p.success(Success(response))
      response
    }
  }

  implicit def wrapNettyFuture[T](f: util.concurrent.Future[T]): Future[T] = {
    val p = Promise[T]()
    f.addListener(wrapPromise(p))
    p.future
  }

  case class InvalidStatusCodeException(code: Int, request: RequestLike) extends
    Throwable(s"Unexpected status code while calling ${request.getMethod} ${request.getUrl}: $code")

  class DefaultOptionErrorHandler(validStatusCodes: Array[Int] = Array(200), ignoreStatusCodes: Array[Int] = Array.empty) extends AHCErrorHandler[Option] {
    override def handleInvalidStatusCode[T]: PartialFunction[(Int, RequestLike), Try[Option[T]]] = {
      case (404, _) => // NotFound
        Success(None)
      case (code, _) if ignoreStatusCodes.contains(code) =>
        Success(None)
      case (code, req) =>
        Failure(InvalidStatusCodeException(code, req))
    }

    override def handleResponseException[T]: PartialFunction[(Throwable, RequestLike), Try[Option[T]]] = {
      case (ex, _) =>
        Failure(ex)
    }

    override def isValidStatusCode(code: Int): Boolean =
      validStatusCodes.contains(code)
  }

  implicit class AHCExtension(val client: AsyncHttpClient) extends AnyVal {

    def sendTypedRequest[A[_], F[_], T](request: Request)(implicit as: Async[A], appl: Applicative[F], um: AHCErrorHandler[F], decoder: AHCBodyDecoder[T]): A[F[T]] = {

      val req = new RequestLike {
        override def getUrl: String = request.getUrl
        override def getMethod: String = request.getMethod
        override def getCookies: Map[String, Seq[String]] =
          request.getCookies.asScala.map(c => c.name() -> c.value())
            .view.groupBy { case (key, _) => key }
            .view.mapValues(_.map { case (_, value) => value }.toSeq)
            .toMap
      }

      try {
        completableToAsync[A, Response](client.executeRequest(request).toCompletableFuture)
          .map { res =>
            try {
              val responseLike = new ResponseLike {
                override def getUrl: String = res.getUri.toFullUrl
                override def getCharset: Charset = Option(HttpUtils.extractContentTypeCharsetAttribute(res.getContentType)).getOrElse(Charset.forName("UTF-8"))
                override def getBody: Array[Byte] = res.getResponseBodyAsBytes
                override def getHeaders: Map[String, Seq[String]] =
                  res.getHeaders.entries().asScala
                    .map(e => e.getKey -> e.getValue)
                    .view.groupBy { case (key, _) => key }
                    .view.mapValues(_.map { case (_, value) => value }.toSeq)
                    .toMap
              }

              val pf: PartialFunction[(Int, RequestLike), Try[F[T]]] = {
                case (code, _) if um.isValidStatusCode(code) =>
                  Try(appl.pure(decoder.decode(responseLike)))
                    .recoverWith {
                      case ex: Throwable =>
                        um.handleResponseException[T](ex -> req)
                    }
              }

              pf.orElse(um.handleInvalidStatusCode[T])(res.getStatusCode -> req)
            } catch {
              case ex: Throwable =>
                um.handleResponseException[T](ex -> req)
            }
          }
          .flatMap(as.fromTry _)
      } catch {
        case ex: Throwable =>
          as.fromTry(um.handleResponseException[T](ex -> req))
      }
    }
  }

  implicit class RequestBuilderBaseExt[T <: RequestBuilderBase[T]](val rb: T) extends AnyVal {
    @inline
    def addHeaders(headers: Map[String, String]*): T = {
      headers.filter(_.nonEmpty).foldLeft(rb) { case (builder, valueMap) =>
        valueMap.foldLeft(builder) { case (b, (name, value)) =>
          b.addHeader(name, value)
        }
      }
    }

    @inline
    def addCookies(cookies: Map[String, String]*): T =
      cookies.filter(_.nonEmpty).foldLeft(rb) { case (builder, valueMap) =>
        valueMap.foldLeft(builder) { case (b, (name, value)) =>
          b.addCookie(new DefaultCookie(name, value))
        }
      }

    @inline
    def addFormParams(formParams: Map[String, String]*): T = {
      formParams.filter(_.nonEmpty).foldLeft(rb) { case (builder, valueMap) =>
        valueMap.foldLeft(builder) { case (b, (name, value)) =>
          b.addFormParam(name, value)
        }
      }
    }
  }

  // Multiple body decoders

  val stringUnmarshaller: AHCBodyDecoder[String] =
    (r: ResponseLike) => new java.lang.String(r.getBody, r.getCharset)

  val jsoupHtmlUnmarshaller: AHCBodyDecoder[Document] =
    (r: ResponseLike) => Jsoup.parse(new java.lang.String(r.getBody, r.getCharset), r.getUrl)

  val jsoupXmlUnmarshaller: AHCBodyDecoder[Document] =
    (r: ResponseLike) => {
      try {
        val doc = try Jsoup.parse(new java.lang.String(r.getBody, r.getCharset), r.getUrl, org.jsoup.parser.Parser.xmlParser()) catch {
          case ex: Throwable =>
            throw new java.lang.RuntimeException(s"jsoupXmlUnmarshaller: Cannot parse document at ${r.getUrl} with Jsoup [${ex.getMessage}}]")
        }
        doc.outputSettings.escapeMode(EscapeMode.xhtml)
        doc.outputSettings.outline(false)
        doc.outputSettings.prettyPrint(false)
        doc.outputSettings.syntax(Syntax.xml)
        doc
      } catch {
        case ex: Throwable =>
          throw new RuntimeException(s"jsoupXmlUnmarshaller: Cannot parse document with Jsoup [${ex.getMessage}}]")
      }
    }

  // Be careful not to bring it as implicit along with `sprayJsonUnmarshaller[T]`. You'll get silent implicit ambiguity because implicit JsonReader[JsValue] exists.
  val sprayJsValueUnmarshaller: AHCBodyDecoder[JsValue] =
    (r: ResponseLike) => try {
      JsonParser(ParserInput(new java.lang.String(r.getBody, r.getCharset)))
    } catch {
      case spray.json.DeserializationException(msg, cause, fieldNames) =>
        val body = new java.lang.String(r.getBody, r.getCharset).take(1024)
        throw spray.json.DeserializationException(msg + s"\n>>>$body<<<", cause, fieldNames)
    }

  def sprayJsonUnmarshaller[T](implicit reader: JsonReader[T]): AHCBodyDecoder[T] =
    (r: ResponseLike) => try {
      JsonParser(ParserInput(new java.lang.String(r.getBody, r.getCharset))).convertTo[T]
    } catch {
      case spray.json.DeserializationException(msg, cause, fieldNames) =>
        val body = new java.lang.String(r.getBody, r.getCharset).take(1024)
        throw spray.json.DeserializationException(msg + s"\n>>>$body<<<", cause, fieldNames)
    }
}
