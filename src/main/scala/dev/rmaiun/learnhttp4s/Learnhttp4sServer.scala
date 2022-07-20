package dev.rmaiun.learnhttp4s

import cats.effect.{Async, Resource, Sync}
import cats.syntax.all.*
import com.comcast.ip4s.*
import fs2.Stream as Fs2Stream
import fs2.concurrent.SignallingRef
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits.*
import org.http4s.server.middleware.Logger as MiddlewareLogger
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import scala.concurrent.duration.*
import scala.language.postfixOps

object Learnhttp4sServer:

  implicit def logger[F[_]: Sync]: Logger[F] = Slf4jLogger.getLogger[F]

    def stream[F[_]: Async](switch: SignallingRef[F, Boolean]): Fs2Stream[F, Nothing] = {
    for {
      client <- Fs2Stream.resource(EmberClientBuilder.default[F].build)
      helloWorldAlg = HelloWorld.impl[F]
      jokeAlg = Jokes.impl[F](client, switch)

      // Combine Service Routes into an HttpApp.
      // Can also be done via a Router if you
      // want to extract a segments not checked
      // in the underlying routes.
      httpApp = (
        Learnhttp4sRoutes.helloWorldRoutes[F](helloWorldAlg) <+>
        Learnhttp4sRoutes.jokeRoutes[F](jokeAlg)
      ).orNotFound

      // With Middlewares in place
      finalHttpApp = MiddlewareLogger.httpApp(true, true)(httpApp)

      exitCode <- Fs2Stream.resource(
        EmberServerBuilder.default[F]
          .withHost(ipv4"0.0.0.0")
          .withPort(port"8080")
          .withHttpApp(finalHttpApp)
          .build >>
        Resource.eval(Async[F].never)
      ).concurrently(Fs2Stream.eval(Sync[F].delay(println("starting..."))))
        .concurrently(Fs2Stream.awakeDelay(1 seconds).evalTap(_ => SomeService.doSomeRepeatableAction("1")).interruptWhen(switch))
    } yield exitCode
  }.drain

