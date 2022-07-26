package dev.rmaiun.learnhttp4s.helper

import cats.Monad
import cats.data.Kleisli
import cats.effect.{Async, MonadCancel}
import cats.effect.std.Dispatcher
import dev.profunktor.fs2rabbit.config.Fs2RabbitConfig
import dev.profunktor.fs2rabbit.config.declaration.*
import dev.profunktor.fs2rabbit.effects.MessageEncoder
import dev.profunktor.fs2rabbit.interpreter.RabbitClient
import dev.profunktor.fs2rabbit.model.*
import dev.profunktor.fs2rabbit.model.ExchangeType.Direct
import fs2.Stream as Fs2Stream

import java.nio.charset.Charset
import scala.concurrent.duration.*

object RabbitHelper:
  type AmqpPublisher[F[_]]  = AmqpMessage[String] => F[Unit]
  type AmqpConsumer[F[_]]   = Fs2Stream[F, AmqpEnvelope[String]]
  type MonadThrowable[F[_]] = MonadCancel[F, Throwable]

  case class AmqpStructures[F[_]](
    botInPublisher: AmqpPublisher[F],
    botInConsumer: AmqpConsumer[F]
  )(using MC: MonadCancel[F, Throwable])

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

  def config2: Fs2RabbitConfig = Fs2RabbitConfig(
    virtualHost = "dev2",
    host = "localhost",
    port = 5682,
    connectionTimeout = 5000.seconds,
    username = Some("guest"),
    password = Some("guest"),
    ssl = false,
    requeueOnNack = false,
    requeueOnReject = false,
    internalQueueSize = Some(500),
    automaticRecovery = true
  )

  def initConnection[F[_]:Async](cfg:Fs2RabbitConfig): Fs2Stream[F, AmqpStructures[F]] =
    for
      dispatcher <- Fs2Stream.resource(Dispatcher[F])
      rc         <- Fs2Stream.eval(RabbitClient[F](cfg, dispatcher))
      _          <- Fs2Stream.eval(RabbitHelper.initRabbitRoutes(rc))
      structs    <- RabbitHelper.createRabbitConnection(rc)
    yield structs

  private val inputQ      = QueueName("input_q")
  private val inRK        = RoutingKey("bot_in_rk")
  private val botExchange = ExchangeName("bot_exchange")

  def initRabbitRoutes[F[_]](rc: RabbitClient[F])(using MC: MonadCancel[F, Throwable]): F[Unit] =
    import cats.implicits.*
    val channel = rc.createConnectionChannel
    channel.use { ch =>
      for
        _ <- rc.declareQueue(DeclarationQueueConfig(inputQ, Durable, NonExclusive, NonAutoDelete, Map()))(ch)
        _ <-
          rc.declareExchange(
            DeclarationExchangeConfig(botExchange, Direct, Durable, NonAutoDelete, NonInternal, Map())
          )(ch)
        _ <- rc.bindQueue(inputQ, botExchange, inRK)(ch)
      yield ()
    }

  def createRabbitConnection[F[_]](rc: RabbitClient[F])(using
    MC: MonadCancel[F, Throwable]
  ): Fs2Stream[F, AmqpStructures[F]] =
    given stringMessageCodec: Kleisli[F, AmqpMessage[String], AmqpMessage[Array[Byte]]] =
      Kleisli[F, AmqpMessage[String], AmqpMessage[Array[Byte]]](s =>
        Monad[F].pure(s.copy(payload = s.payload.getBytes(Charset.defaultCharset())))
      )
    for
      inPub      <- publisher(inRK, rc)
      inConsumer <- autoAckConsumer(inputQ, rc)
    yield AmqpStructures(inPub, inConsumer)

  def autoAckConsumer[F[_]: MonadThrowable](
    q: QueueName,
    rc: RabbitClient[F]
  ): Fs2Stream[F, Fs2Stream[F, AmqpEnvelope[String]]] =
    Fs2Stream
      .resource(rc.createConnectionChannel)
      .flatMap(implicit ch => Fs2Stream.eval(rc.createAutoAckConsumer(q)))

  def publisher[F[_]: MonadThrowable](rk: RoutingKey, rc: RabbitClient[F])(using
    me: MessageEncoder[F, AmqpMessage[String]]
  ): Fs2Stream[F, AmqpMessage[String] => F[Unit]] =
    Fs2Stream
      .resource(rc.createConnectionChannel)
      .flatMap(implicit ch => Fs2Stream.eval(rc.createPublisher[AmqpMessage[String]](botExchange, rk)))
