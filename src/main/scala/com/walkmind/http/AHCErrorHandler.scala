package com.walkmind.http

import java.nio.charset.Charset

import scala.util.Try

trait RequestLike {
  def getUrl: String
  def getMethod: String
  def getCookies: Map[String, Seq[String]]
}

trait ResponseLike {
  def getUrl: String
  def getCharset: Charset
  def getBody: Array[Byte]
  def getHeaders: Map[String, Seq[String]]
}

trait AHCErrorHandler[F[_]] {
  that =>

  def isValidStatusCode(code: Int): Boolean

  def handleInvalidStatusCode[T]: PartialFunction[(Int, RequestLike), Try[F[T]]]

  def handleResponseException[T]: PartialFunction[(Throwable, RequestLike), Try[F[T]]]
}
