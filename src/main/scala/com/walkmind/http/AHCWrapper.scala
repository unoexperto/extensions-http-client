package com.walkmind.http

import cats.Applicative
import cats.effect.IO
import org.asynchttpclient.request.body.multipart.StringPart
import org.asynchttpclient.{AsyncHttpClientConfig, BoundRequestBuilder, DefaultAsyncHttpClient, DefaultAsyncHttpClientConfig, Request}

import scala.concurrent.{ExecutionContext, Future}
import scala.language.higherKinds

@deprecated(message = "Use AHCExtension and RequestBuilderBaseExt extension directly with AsyncHttpClient", since = "2.34")
class AHCWrapper(baseUrl: Option[String] = None,
                 commonHeaders: Map[String, String] = Map.empty,
                 httpConfig: AsyncHttpClientConfig = new DefaultAsyncHttpClientConfig.Builder().build(),
                 validStatusCodes: Set[Int] = Set.empty,
                 ignoreStatusCodes: Set[Int] = Set.empty)(implicit ec: ExecutionContext) extends AHCWrapperBase(commonHeaders, httpConfig) {

  private implicit val handler: AHCErrorHandler[Option] = new DefaultOptionErrorHandler(validStatusCodes.toArray :+ 200, ignoreStatusCodes.toArray)

  import cats.instances.option._ // For 'InvariantMonoidal[Option]'

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  def httpGetUrl[T: AHCBodyDecoder](url: String,
                                    headers: Map[String, String] = Map.empty[String, String],
                                    cookies: Map[String, String] = Map.empty[String, String],
                                    followRedirect: Option[Boolean] = None): Future[Option[T]] =
    super.httpGet[Option, T](url, headers, cookies, followRedirect).unsafeToFuture()

  def httpPostUrlForm[T: AHCBodyDecoder](url: String,
                                         form: Map[String, String],
                                         headers: Map[String, String] = Map.empty[String, String],
                                         cookies: Map[String, String] = Map.empty[String, String],
                                         followRedirect: Option[Boolean] = None): Future[Option[T]] =
    super.httpPostForm[Option, T](url, form, headers, cookies, followRedirect).unsafeToFuture()


  def httpPostUrlMultipart[T: AHCBodyDecoder](url: String,
                                              form: Map[String, String],
                                              headers: Map[String, String] = Map.empty[String, String],
                                              cookies: Map[String, String] = Map.empty[String, String],
                                              followRedirect: Option[Boolean] = None): Future[Option[T]] =
    super.httpPostMultipart[Option, T](url, form, headers, cookies, followRedirect).unsafeToFuture()

  def httpPost[T: AHCBodyDecoder](url: String,
                                  data: Array[Byte],
                                  headers: Map[String, String] = Map.empty[String, String],
                                  cookies: Map[String, String] = Map.empty[String, String],
                                  followRedirect: Option[Boolean] = None): Future[Option[T]] =
    super.httpPostData[Option, T](url, data, headers, cookies, followRedirect).unsafeToFuture()

  protected def sendTypedRequest[T: AHCBodyDecoder](request: Request): Future[Option[T]] =
    client.sendTypedRequest[IO, Option, T](request).unsafeToFuture()
}

protected class AHCWrapperBase(commonHeaders: Map[String, String] = Map.empty,
                               httpConfig: AsyncHttpClientConfig = new DefaultAsyncHttpClientConfig.Builder().build())(implicit ec: ExecutionContext) {

  protected val client = new DefaultAsyncHttpClient(httpConfig)

  def httpGet[F[_] : Applicative : AHCErrorHandler, T: AHCBodyDecoder](url: String,
                                                                       headers: Map[String, String] = Map.empty[String, String],
                                                                       cookies: Map[String, String] = Map.empty[String, String],
                                                                       followRedirect: Option[Boolean] = None): IO[F[T]] = {

    client.sendTypedRequest[IO, F, T](client.prepareGet(url).addHeaders(commonHeaders, headers).build())
  }

  def httpPostForm[F[_] : Applicative : AHCErrorHandler, T: AHCBodyDecoder](url: String,
                                                                            form: Map[String, String],
                                                                            headers: Map[String, String] = Map.empty[String, String],
                                                                            cookies: Map[String, String] = Map.empty[String, String],
                                                                            followRedirect: Option[Boolean] = None): IO[F[T]] = {

    val builder = form.foldLeft(client.preparePost(url)) { case (bldr, (name, value)) => bldr.addFormParam(name, value) }
    client.sendTypedRequest[IO, F, T](buildRequest(builder, headers, cookies, followRedirect))
  }

  def httpPostMultipart[F[_] : Applicative : AHCErrorHandler, T: AHCBodyDecoder](url: String,
                                                                                 form: Map[String, String],
                                                                                 headers: Map[String, String] = Map.empty[String, String],
                                                                                 cookies: Map[String, String] = Map.empty[String, String],
                                                                                 followRedirect: Option[Boolean] = None): IO[F[T]] = {

    val builder = form.foldLeft(client.preparePost(url)) { case (bldr, (name, value)) => bldr.addBodyPart(new StringPart(name, value)) }
    val request = buildRequest(builder, headers, cookies, followRedirect)
    client.sendTypedRequest[IO, F, T](request)
  }

  def httpPostData[F[_] : Applicative : AHCErrorHandler, T: AHCBodyDecoder](url: String,
                                                                            data: Array[Byte],
                                                                            headers: Map[String, String] = Map.empty[String, String],
                                                                            cookies: Map[String, String] = Map.empty[String, String],
                                                                            followRedirect: Option[Boolean] = None): IO[F[T]] = {

    val builder = client.preparePost(url).setBody(data)
    client.sendTypedRequest[IO, F, T](buildRequest(builder, headers, cookies, followRedirect))
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  @inline
  private def buildRequest(request: BoundRequestBuilder, headers: Map[String, String], cookies: Map[String, String], followRedirect: Option[Boolean]): Request =
    request
      .addHeaders(commonHeaders, headers)
      .addCookies(cookies)
      .setFollowRedirect(followRedirect.getOrElse(httpConfig.isFollowRedirect))
      .build
}
