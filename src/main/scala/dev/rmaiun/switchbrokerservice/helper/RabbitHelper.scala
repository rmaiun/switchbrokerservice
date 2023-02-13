package dev.rmaiun.switchbrokerservice.helper

import cats.Monad
import cats.data.Kleisli
import cats.effect.std.Dispatcher
import cats.effect.{ Async, MonadCancel, Resource }
import dev.profunktor.fs2rabbit.config.Fs2RabbitConfig
import dev.profunktor.fs2rabbit.config.declaration.*
import dev.profunktor.fs2rabbit.effects.{ EnvelopeDecoder, MessageEncoder }
import dev.profunktor.fs2rabbit.interpreter.RabbitClient
import dev.profunktor.fs2rabbit.model.*
import dev.profunktor.fs2rabbit.model.ExchangeType.Direct
import dev.rmaiun.switchbrokerservice.SwapSlotRoutes.SwitchBrokerCommand
import fs2.Stream as Fs2Stream

import java.nio.charset.Charset
import scala.concurrent.duration.*

object RabbitHelper:
  type AmqpPublisher[F[_]]  = AmqpMessage[String] => F[Unit]
  type AmqpConsumer[F[_]]   = Fs2Stream[F, AmqpEnvelope[String]]
  type MonadThrowable[F[_]] = MonadCancel[F, Throwable]

  case class AmqpStructures[F[_]](
    resultPublisher: AmqpPublisher[F],
    instructionConsumer: AmqpConsumer[F]
  )(using MC: MonadCancel[F, Throwable])

  def reconfig(dto: SwitchBrokerCommand): Fs2RabbitConfig = Fs2RabbitConfig(
    virtualHost = dto.virtualHost,
    host = dto.host,
    port = dto.port,
    connectionTimeout = 5000.seconds,
    username = Some(dto.user),
    password = Some(dto.password),
    ssl = false,
    requeueOnNack = false,
    requeueOnReject = false,
    internalQueueSize = Some(500),
    automaticRecovery = true
  )

  def initRabbitStructs[F[_]: Async](cfg: Fs2RabbitConfig): Fs2Stream[F, AmqpStructures[F]] =
    given stringMessageCodec: Kleisli[F, AmqpMessage[String], AmqpMessage[Array[Byte]]] =
      Kleisli[F, AmqpMessage[String], AmqpMessage[Array[Byte]]](s => Monad[F].pure(s.copy(payload = s.payload.getBytes(Charset.defaultCharset()))))
    val structs = for
      dispatcher       <- Dispatcher[F]
      rc               <- Resource.eval(RabbitClient[F](cfg, dispatcher))
      publisherChannel <- rc.createConnectionChannel
      consumerChannel  <- rc.createConnectionChannel
      publisher        <- Resource.eval(rc.createPublisher[AmqpMessage[String]](instructionEx, instructionRK)(publisherChannel, summon))
      consumer         <- Resource.eval(rc.createAutoAckConsumer(instructionQ)(consumerChannel, summon))
    yield AmqpStructures(publisher, consumer)
    Fs2Stream.resource(structs)

  private val instructionQ  = QueueName("instruction_q")
  private val instructionRK = RoutingKey("instruction_q_rk")
  private val instructionEx = ExchangeName("instruction_exchange")

  def initRabbitRoutes[F[_]: Async](dto: SwitchBrokerCommand): Resource[F, Unit] =
    import cats.implicits.*
    for
      dispatcher <- Dispatcher[F]
      rc         <- Resource.eval(RabbitClient[F](reconfig(dto), dispatcher))
      channel    <- rc.createConnectionChannel
      _          <- Resource.eval(rc.declareQueue(DeclarationQueueConfig(instructionQ, Durable, NonExclusive, NonAutoDelete, Map()))(channel))
      exchangeCfg = DeclarationExchangeConfig(instructionEx, Direct, Durable, NonAutoDelete, NonInternal, Map())
      _          <- Resource.eval(rc.declareExchange(exchangeCfg)(channel))
      _          <- Resource.eval(rc.bindQueue(instructionQ, instructionEx, instructionRK)(channel))
    yield ()
