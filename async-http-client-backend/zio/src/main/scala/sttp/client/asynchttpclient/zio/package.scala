package sttp.client.asynchttpclient

import sttp.client._
import _root_.zio._
import sttp.capabilities.zio.ZioStreams
import sttp.capabilities.{Effect, WebSockets}
import sttp.client.impl.zio.ExtendEnv

package object zio {

  /**
    * ZIO-environment service definition, which is an SttpBackend.
    */
  type SttpClient = Has[SttpBackend[Task, ZioStreams with WebSockets]]

  object SttpClient {

    /**
      * Sends the request. Only requests for which the method & URI are specified can be sent.
      *
      * @return An effect resulting in a [[Response]], containing the body, deserialized as specified by the request
      *         (see [[RequestT.response]]), if the request was successful (1xx, 2xx, 3xx response codes), or if there
      *         was a protocol-level failure (4xx, 5xx response codes).
      *
      *         A failed effect, if an exception occurred when connecting to the target host, writing the request or
      *         reading the response.
      *
      *         Known exceptions are converted to one of [[SttpClientException]]. Other exceptions are kept unchanged.
      */
    def send[T](
        request: Request[T, Effect[Task] with ZioStreams with WebSockets]
    ): ZIO[SttpClient, Throwable, Response[T]] =
      ZIO.accessM(env => env.get[SttpBackend[Task, ZioStreams with WebSockets]].send(request))

    /**
      * A variant of [[send]] which allows the effects that are part of the response handling specification to use
      * the `R` environment.
      */
    def sendR[T, R](
        request: Request[T, Effect[RIO[R, *]] with ZioStreams with WebSockets]
    ): ZIO[SttpClient with R, Throwable, Response[T]] =
      ZIO.accessM(env => env.get[SttpBackend[Task, ZioStreams with WebSockets]].extendEnv[R].send(request))
  }
}
