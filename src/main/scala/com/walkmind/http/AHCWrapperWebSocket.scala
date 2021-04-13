package com.walkmind.http

import akka.stream.scaladsl.Source
import akka.stream.{KillSwitch, OverflowStrategy}
import org.asynchttpclient.netty.ws.NettyWebSocket
import org.asynchttpclient.ws.{WebSocket, WebSocketListener, WebSocketUpgradeHandler}
import org.reactivestreams.{Publisher, Subscriber}

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.{ExecutionContext, Promise}

sealed trait WsMessage
sealed case class WsOpen(ws: WebSocket) extends WsMessage
sealed case class WsText(value: String) extends WsMessage
sealed case class WsBinary(value: Array[Byte]) extends WsMessage

trait AHCWrapperWebSocket {
  this: AHCWrapperBase =>

  def createWsObservable3(url: String, onStartAction: WebSocket => Option[KillSwitch] = _ => None, bufferSize: Int = 32)(implicit ec: ExecutionContext): Source[WsMessage, KillSwitch] = {
    val actorSource = Source.actorRef[WsMessage](bufferSize, OverflowStrategy.fail)
    actorSource.mapMaterializedValue { actor =>
      val listener: WebSocketListener = new WebSocketListener() {
        private val byteBuffer = new ArrayBuffer[Byte]()
        private val strBuffer = new StringBuffer()

        override def onOpen(ws: WebSocket): Unit =
          actor ! WsOpen(ws)

        override def onClose(ws: WebSocket, code: Int, reason: String): Unit =
          actor ! akka.actor.Status.Success(code)

        override def onBinaryFrame(payload: Array[Byte], finalFragment: Boolean, rsv: Int): Unit =
          if (!finalFragment)
            byteBuffer ++= payload
          else {
            if (byteBuffer.nonEmpty) {
              byteBuffer ++= payload
              actor ! WsBinary(byteBuffer.toArray)
              byteBuffer.clear()
            }
            else
              actor ! WsBinary(payload)
          }

        override def onTextFrame(payload: String, finalFragment: Boolean, rsv: Int): Unit =
          if (!finalFragment)
            strBuffer.append(payload)
          else {
            if (strBuffer.length() > 0) {
              strBuffer.append(payload)
              actor ! WsText(strBuffer.toString)
              strBuffer.setLength(0)
            }
            else
              actor ! WsText(payload)
          }

        override def onError(t: Throwable): Unit =
          actor ! akka.actor.Status.Failure(t)

        override def onPongFrame(payload: Array[Byte]): Unit = {
          super.onPingFrame(payload)
        }
      }

      val websocket =
        client
          .prepareGet(url)
          .execute(new WebSocketUpgradeHandler.Builder().addWebSocketListener(listener).build).get

      // Use Pong reply as indication that we've connected to the server
      val p = Promise[Void]()
      websocket.sendPingFrame().addListener(p)
      val onStartKillSwitchFut = p.future.map(_ => onStartAction(websocket))

      new KillSwitch {
        override def shutdown(): Unit = {
          onStartKillSwitchFut.map(_.foreach(_.shutdown()))
          websocket.sendCloseFrame()
        }

        override def abort(ex: Throwable): Unit = {
          onStartKillSwitchFut.map(_.foreach(_.abort(ex)))
          websocket.sendCloseFrame()
        }
      }
    }
  }

  def createWsObservable2(url: String, onStartAction: Option[WebSocket => Unit])(implicit ec: ExecutionContext): Source[WsMessage, KillSwitch] = {
    var websocket: NettyWebSocket = null
    val ppp: Publisher[WsMessage] = new Publisher[WsMessage] {
      override def subscribe(subs: Subscriber[_ >: WsMessage]): Unit = {
        val listener: WebSocketListener = new WebSocketListener() {
          private val byteBuffer = new ArrayBuffer[Byte]()
          private val strBuffer = new StringBuffer()

          override def onOpen(ws: WebSocket): Unit =
            subs.onNext(WsOpen(ws))

          override def onClose(ws: WebSocket, code: Int, reason: String): Unit =
            subs.onComplete()

          override def onBinaryFrame(payload: Array[Byte], finalFragment: Boolean, rsv: Int): Unit =
            if (!finalFragment)
              byteBuffer ++= payload
            else {
              if (byteBuffer.nonEmpty) {
                byteBuffer ++= payload
                subs.onNext(WsBinary(byteBuffer.toArray))
                byteBuffer.clear()
              }
              else
                subs.onNext(WsBinary(payload))
            }

          override def onTextFrame(payload: String, finalFragment: Boolean, rsv: Int): Unit =
            if (!finalFragment)
              strBuffer.append(payload)
            else {
              if (strBuffer.length() > 0) {
                strBuffer.append(payload)
                subs.onNext(WsText(strBuffer.toString))
                strBuffer.setLength(0)
              }
              else
                subs.onNext(WsText(payload))
            }

          override def onError(t: Throwable): Unit =
            subs.onError(t)

          override def onPongFrame(payload: Array[Byte]): Unit = {
            super.onPingFrame(payload)
          }
        }

        websocket =
          client
            .prepareGet(url)
            .execute(new WebSocketUpgradeHandler.Builder().addWebSocketListener(listener).build).get

        // Use Pong reply as indication that we've connected to the server
        onStartAction.map { handler =>
          val p = Promise[Void]()
          websocket.sendPingFrame().addListener(p)
          p.future.map { _ =>
            handler(websocket)
          }
        }
      }
    }

    Source.fromPublisher[WsMessage](ppp)
      .mapMaterializedValue[KillSwitch] { _ =>
      new KillSwitch {
        override def shutdown(): Unit = websocket.sendCloseFrame()

        override def abort(ex: Throwable): Unit = websocket.sendCloseFrame()
      }
    }
  }

  def createWsObservable(url: String, onStartAction: Option[WebSocket => Unit])(implicit ec: ExecutionContext): Source[WsMessage, KillSwitch] =
    Source.asSubscriber[WsMessage].mapMaterializedValue { subs: Subscriber[WsMessage] =>
      val listener: WebSocketListener = new WebSocketListener() {
        private val byteBuffer = new ArrayBuffer[Byte]()
        private val strBuffer = new StringBuffer()

        override def onOpen(ws: WebSocket): Unit =
          subs.onNext(WsOpen(ws))

        override def onClose(ws: WebSocket, code: Int, reason: String): Unit =
          subs.onComplete()

        override def onBinaryFrame(payload: Array[Byte], finalFragment: Boolean, rsv: Int): Unit =
          if (!finalFragment)
            byteBuffer ++= payload
          else {
            if (byteBuffer.nonEmpty) {
              byteBuffer ++= payload
              subs.onNext(WsBinary(byteBuffer.toArray))
              byteBuffer.clear()
            }
            else
              subs.onNext(WsBinary(payload))
          }

        override def onTextFrame(payload: String, finalFragment: Boolean, rsv: Int): Unit =
          if (!finalFragment)
            strBuffer.append(payload)
          else {
            if (strBuffer.length() > 0) {
              strBuffer.append(payload)
              subs.onNext(WsText(strBuffer.toString))
              strBuffer.setLength(0)
            }
            else
              subs.onNext(WsText(payload))
          }

        override def onError(t: Throwable): Unit =
          subs.onError(t)

        override def onPongFrame(payload: Array[Byte]): Unit = {
          super.onPingFrame(payload)
        }
      }

      val websocket =
        client
          .prepareGet(url)
          .execute(new WebSocketUpgradeHandler.Builder().addWebSocketListener(listener).build).get

      // Use Pong reply as indication that we've connected to the server
      onStartAction.map { handler =>
        val p = Promise[Void]()
        websocket.sendPingFrame().addListener(p)
        p.future.map { _ =>
          handler(websocket)
        }
      }

      new KillSwitch {
        override def shutdown(): Unit = websocket.sendCloseFrame()

        override def abort(ex: Throwable): Unit = websocket.sendCloseFrame()
      }
    }
}
