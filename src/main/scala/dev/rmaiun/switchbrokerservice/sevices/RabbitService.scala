package dev.rmaiun.switchbrokerservice.sevices

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
import dev.rmaiun.switchbrokerservice.SwitchBrokerRoutes.SwitchVirtualHostCommand
import fs2.Stream as Fs2Stream

import java.nio.charset.Charset
import scala.concurrent.duration.*
import scala.language.postfixOps
import cats.implicits.*

import scala.util.Try
object RabbitService:
  type AmqpPublisher[F[_]]  = AmqpMessage[String] => F[Unit]
  type AmqpConsumer[F[_]]   = Fs2Stream[F, AmqpEnvelope[String]]
  type MonadThrowable[F[_]] = MonadCancel[F, Throwable]

  case class AmqpStructures[F[_]](
    instructionPublisher: AmqpPublisher[F],
    instructionConsumer: AmqpConsumer[F],
    connections: List[AMQPConnection]
  )

  def reconfig(dto: SwitchVirtualHostCommand): Fs2RabbitConfig = Fs2RabbitConfig(
    virtualHost = dto.virtualHost,
    host = "localhost",
    port = 5672,
    connectionTimeout = 2 seconds,
    username = Some("guest"),
    password = Some("guest"),
    ssl = false,
    requeueOnNack = false,
    requeueOnReject = false,
    internalQueueSize = Some(500),
    automaticRecovery = true
  )

  given stringMessageCodec[F[_]: Monad]: Kleisli[F, AmqpMessage[String], AmqpMessage[Array[Byte]]] =
    Kleisli[F, AmqpMessage[String], AmqpMessage[Array[Byte]]](s => Monad[F].pure(s.copy(payload = s.payload.getBytes(Charset.defaultCharset()))))

  def initRabbitStructs[F[_]: Async](cfg: Fs2RabbitConfig): Fs2Stream[F, AmqpStructures[F]] =
    val structs = for
      dispatcher       <- Dispatcher[F]
      rc               <- Resource.eval(RabbitClient[F](cfg, dispatcher))
      conn1            <- rc.createConnection
      conn2            <- rc.createConnection
      publisherChannel <- rc.createChannel(conn1)
      consumerChannel  <- rc.createChannel(conn2)
      publisher        <- Resource.eval(rc.createPublisher[AmqpMessage[String]](instructionEx, instructionRK)(publisherChannel, summon))
      consumer         <- Resource.eval(rc.createAutoAckConsumer(instructionQ)(consumerChannel, summon))
    yield AmqpStructures(publisher, consumer, List(conn1, conn2))
    Fs2Stream.resource(structs)

  private val instructionQ  = QueueName("instruction_q")
  private val instructionRK = RoutingKey("instruction_q_rk")
  private val instructionEx = ExchangeName("instruction_exchange")

  def initRabbitRoutes[F[_]: Async](dto: SwitchVirtualHostCommand): Fs2Stream[F, Unit] =
    import cats.implicits.*
    val effect = for
      dispatcher <- Dispatcher[F]
      rc         <- Resource.eval(RabbitClient[F](reconfig(dto), dispatcher))
      channel    <- rc.createConnectionChannel
      _          <- Resource.eval(rc.declareQueue(DeclarationQueueConfig(instructionQ, Durable, NonExclusive, NonAutoDelete, Map()))(channel))
      exchangeCfg = DeclarationExchangeConfig(instructionEx, Direct, Durable, NonAutoDelete, NonInternal, Map())
      _          <- Resource.eval(rc.declareExchange(exchangeCfg)(channel))
      _          <- Resource.eval(rc.bindQueue(instructionQ, instructionEx, instructionRK)(channel))
    yield channel
    Fs2Stream.eval(effect.use_)

  def closeConnection[F[_]: Async](connections: List[AMQPConnection]): F[Unit] =
    connections
      .map(conn =>
        Async[F].delay {
          Try(conn.value.close())
          ()
        }
      )
      .sequence_
