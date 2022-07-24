package dev.rmaiun.learnhttp4s

import cats.data.Kleisli
import cats.effect.std.Dispatcher
import cats.effect.{Async, MonadCancel, Resource, Sync}
import cats.syntax.all.*
import cats.{Monad, MonadError}
import com.comcast.ip4s.*
import dev.profunktor.fs2rabbit.config.Fs2RabbitConfig
import dev.profunktor.fs2rabbit.config.declaration.*
import dev.profunktor.fs2rabbit.effects.MessageEncoder
import dev.profunktor.fs2rabbit.interpreter.RabbitClient
import dev.profunktor.fs2rabbit.model.*
import dev.profunktor.fs2rabbit.model.ExchangeType.Direct
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
  type AmqpPublisher[F[_]]  = AmqpMessage[String] => F[Unit]
  type AmqpConsumer[F[_]]   = Fs2Stream[F, AmqpEnvelope[String]]
  type MonadThrowable[F[_]] = MonadCancel[F, Throwable]

  case class AmqpStructures[F[_]](
    botInPublisher: AmqpPublisher[F],
    botInConsumer: AmqpConsumer[F]
  )(implicit MC: MonadCancel[F, Throwable])

  implicit def logger[F[_]: Sync]: Logger[F] = Slf4jLogger.getLogger[F]

  def config: Fs2RabbitConfig = Fs2RabbitConfig(
    virtualHost = "dev",
    host = "localhost",
    port = 5672,
    connectionTimeout = 5000.seconds,
    username = Some("guest"),
    password = Some("guest"),
    ssl = false,
    requeueOnNack = false,
    requeueOnReject = false,
    internalQueueSize = Some(500),
    automaticRecovery = true
  )

  private val inputQ      = QueueName("input_q")
  private val inRK        = RoutingKey("bot_in_rk")
  private val botExchange = ExchangeName("bot_exchange")

  private def initRabbitRoutes[F[_]](rc: RabbitClient[F])(implicit MC: MonadCancel[F, Throwable]): F[Unit] =
    import cats.implicits.*
    val channel = rc.createConnectionChannel
    channel.use { implicit ch =>
      for
        _ <- rc.declareQueue(DeclarationQueueConfig(inputQ, Durable, NonExclusive, NonAutoDelete, Map()))
        _ <-
          rc.declareExchange(DeclarationExchangeConfig(botExchange, Direct, Durable, NonAutoDelete, NonInternal, Map()))
        _ <- rc.bindQueue(inputQ, botExchange, inRK)
      yield ()
    }

  def createRabbitConnection[F[_]](
    rc: RabbitClient[F]
  )(implicit MC: MonadCancel[F, Throwable]): Fs2Stream[F, AmqpStructures[F]] =
    implicit val stringMessageCodec: Kleisli[F, AmqpMessage[String], AmqpMessage[Array[Byte]]] =
      Kleisli[F, AmqpMessage[String], AmqpMessage[Array[Byte]]](s =>
        Monad[F].pure(s.copy(payload = s.payload.getBytes(Charset.defaultCharset())))
      )
    for
      inPub      <- publisher(inRK, rc)
      inConsumer <- autoAckConsumer(inputQ, rc)
    yield AmqpStructures(inPub, inConsumer)

  private def autoAckConsumer[F[_]: MonadThrowable](
    q: QueueName,
    rc: RabbitClient[F]
  ): Fs2Stream[F, Fs2Stream[F, AmqpEnvelope[String]]] =
    Fs2Stream
      .resource(rc.createConnectionChannel)
      .flatMap(implicit ch => Fs2Stream.eval(rc.createAutoAckConsumer(q)))

  private def publisher[F[_]: MonadThrowable](rk: RoutingKey, rc: RabbitClient[F])(implicit
    me: MessageEncoder[F, AmqpMessage[String]]
  ): Fs2Stream[F, AmqpMessage[String] => F[Unit]] =
    Fs2Stream
      .resource(rc.createConnectionChannel)
      .flatMap(implicit ch => Fs2Stream.eval(rc.createPublisher[AmqpMessage[String]](botExchange, rk)))

  def stream[F[_]: Async](switch: SignallingRef[F, Boolean]): Fs2Stream[F, Nothing] = {
    for
      client       <- Fs2Stream.resource(EmberClientBuilder.default[F].build)
      helloWorldAlg = HelloWorld.impl[F]
      jokeAlg       = Jokes.impl[F](client, switch)

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
      dispatcher <- Fs2Stream.resource(Dispatcher[F])
      rc         <- Fs2Stream.eval(RabbitClient[F](config, dispatcher))
      _          <- Fs2Stream.eval(initRabbitRoutes(rc))
      structs    <- createRabbitConnection(rc)
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
          .concurrently(structs.botInConsumer.evalTap(x => SomeService.doSomeRepeatableAction(x.payload)).interruptWhen(switch))

    yield exitCode
  }.drain
