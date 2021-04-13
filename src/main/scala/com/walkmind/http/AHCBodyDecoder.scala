package com.walkmind.http

trait AHCBodyDecoder[T] {
  that =>

  def decode(response: ResponseLike): T

  def map[V](f: T => V): AHCBodyDecoder[V] = {
    new AHCBodyDecoder[V] {
      override def decode(response: ResponseLike): V =
        f(that.decode(response))
    }
  }
}