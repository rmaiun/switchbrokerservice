package dev.rmaiun.learnhttp4s

import cats.data.Kleisli
import cats.effect.std.Dispatcher
import cats.effect.*
import cats.syntax.all.*
import cats.{Monad, MonadError}
import com.comcast.ip4s.*
import dev.profunktor.fs2rabbit.config.Fs2RabbitConfig
import dev.profunktor.fs2rabbit.config.declaration.*
import dev.profunktor.fs2rabbit.effects.MessageEncoder
import dev.profunktor.fs2rabbit.interpreter.RabbitClient
import dev.profunktor.fs2rabbit.model.*
import dev.profunktor.fs2rabbit.model.ExchangeType.Direct
import dev.rmaiun.learnhttp4s.helper.RabbitHelper
import dev.rmaiun.learnhttp4s.helper.RabbitHelper.AmqpPublisher
import fs2.Stream as Fs2Stream
import fs2.concurrent.SignallingRef
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits.*
import org.http4s.server.middleware.Logger as MiddlewareLogger
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import java.nio.charset.Charset
import scala.concurrent.duration.*
import scala.language.postfixOps

object Learnhttp4sServer:

  def stream[F[_]: Async](switch: SignallingRef[F, Boolean]): Fs2Stream[F, Nothing] = {
    given logger[F[_]: Sync]: Logger[F] = Slf4jLogger.getLogger[F]
    for
      client <- Fs2Stream.resource(EmberClientBuilder.default[F].build)
      // Combine Service Routes into an HttpApp.
      // Can also be done via a Router if you
      // want to extract a segments not checked
      // in the underlying routes.
      structs <- RabbitHelper.initConnection(RabbitHelper.config)

      helloWorldAlg = HelloWorld.impl[F]
      publisher    <- Fs2Stream.eval(Ref[F].of(structs.botInPublisher))
      p <- Fs2Stream.eval(publisher.get)
      _ <- Fs2Stream.eval(p(AmqpMessage("hello there", AmqpProperties())))
      jokeAlg = Jokes.impl[F](client, switch, publisher)
      httpApp = (
                  Learnhttp4sRoutes.helloWorldRoutes[F](helloWorldAlg) <+>
                    Learnhttp4sRoutes.jokeRoutes[F](jokeAlg)
                ).orNotFound

      // With Middlewares in place
      finalHttpApp = MiddlewareLogger.httpApp(true, true)(httpApp)
      exitCode <-
        Fs2Stream
          .resource(
            EmberServerBuilder
              .default[F]
              .withHost(ipv4"0.0.0.0")
              .withPort(port"8080")
              .withHttpApp(finalHttpApp)
              .build >>
              Resource.eval(Async[F].never)
          )
          .concurrently(Fs2Stream.eval(Sync[F].delay(println("starting..."))))
//          .concurrently(
//            Fs2Stream.awakeDelay(1 seconds).evalTap(_ => SomeService.doSomeRepeatableAction("1")).interruptWhen(switch)
//          )
          .concurrently(
            structs.botInConsumer.evalTap(x => SomeService.doSomeRepeatableAction("1", x.payload)).interruptWhen(switch)
          )
    yield exitCode
  }.drain
