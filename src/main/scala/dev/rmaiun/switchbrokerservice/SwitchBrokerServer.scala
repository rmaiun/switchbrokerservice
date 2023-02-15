package dev.rmaiun.switchbrokerservice

import cats.data.Kleisli
import cats.effect.*
import cats.effect.std.Dispatcher
import cats.syntax.all.*
import cats.{Monad, MonadError}
import com.comcast.ip4s.*
import dev.profunktor.fs2rabbit.config.Fs2RabbitConfig
import dev.profunktor.fs2rabbit.config.declaration.*
import dev.profunktor.fs2rabbit.effects.MessageEncoder
import dev.profunktor.fs2rabbit.interpreter.RabbitClient
import dev.profunktor.fs2rabbit.model.*
import dev.profunktor.fs2rabbit.model.ExchangeType.Direct
import dev.rmaiun.switchbrokerservice.SwitchBrokerRoutes.SwitchBrokerCommand
import dev.rmaiun.switchbrokerservice.sevices.RabbitService.AmqpPublisher
import dev.rmaiun.switchbrokerservice.sevices.{LogService, RabbitService, SwitchBrokerService}
import fs2.Stream as Fs2Stream
import fs2.concurrent.SignallingRef
import org.http4s.HttpApp
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits.*
import org.http4s.server.middleware.Logger as MiddlewareLogger
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import java.nio.charset.Charset
import scala.concurrent.duration.*
import scala.language.postfixOps

object SwitchBrokerServer:

  def stream[F[_]: Async](switch: SignallingRef[F, Boolean]): Fs2Stream[F, Nothing] = {
    given logger: Logger[F] = Slf4jLogger.getLogger[F]
    val defaultBrokerCfg    = SwitchBrokerCommand("localhost", 5672, "dev", "guest", "guest")
    for
      _            <- Fs2Stream.resource(RabbitService.initRabbitRoutes(defaultBrokerCfg))
      structs      <- RabbitService.initRabbitStructs(RabbitService.reconfig(defaultBrokerCfg))
      publisherRef <- Fs2Stream.eval(Ref[F].of(structs.instructionPublisher))
      swapSlotAlg   = SwitchBrokerService.impl(switch, publisherRef)
      httpApp       = (SwitchBrokerRoutes.swapSlotRoutes[F](swapSlotAlg)).orNotFound
      finalHttpApp  = MiddlewareLogger.httpApp(true, true)(httpApp)
      exitCode <-
        runServer(finalHttpApp)
          .concurrently(runPingSignals(publisherRef, switch))
          .concurrently(
            structs.instructionConsumer
              .evalTap(x => LogService.logPingResult(x.payload))
              .interruptWhen(switch)
          )
    yield exitCode
  }.drain

  def runServer[F[_]: Async](httpApp: HttpApp[F]): Fs2Stream[F, Nothing] = Fs2Stream.resource(
    EmberServerBuilder
      .default[F]
      .withHost(ipv4"0.0.0.0")
      .withPort(port"8080")
      .withHttpApp(httpApp)
      .build >>
      Resource.eval(Async[F].never)
  )

  private def runPingSignals[F[_]: Async](publisher: Ref[F, AmqpPublisher[F]], switch: SignallingRef[F, Boolean]): Fs2Stream[F, FiniteDuration] =
    Fs2Stream
      .awakeDelay(2 seconds)
      .evalTap { _ =>
        for
          pb <- publisher.get
          _  <- pb(AmqpMessage("init", new AmqpProperties()))
        yield ()
      }
      .interruptWhen(switch)

end SwitchBrokerServer
