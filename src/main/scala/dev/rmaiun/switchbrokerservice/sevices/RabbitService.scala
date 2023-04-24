package dev.rmaiun.switchbrokerservice.sevices

import cats.Monad
import cats.data.Kleisli
import cats.effect.std.Dispatcher
import cats.effect.{ Async, MonadCancel, Resource, Sync }
import cats.implicits.*
import dev.profunktor.fs2rabbit.config.Fs2RabbitConfig
import dev.profunktor.fs2rabbit.config.declaration.*
import dev.profunktor.fs2rabbit.effects.{ EnvelopeDecoder, MessageEncoder }
import dev.profunktor.fs2rabbit.interpreter.RabbitClient
import dev.profunktor.fs2rabbit.model.*
import dev.profunktor.fs2rabbit.model.ExchangeType.Direct
import dev.rmaiun.switchbrokerservice.SwitchBrokerRoutes.SwitchVirtualHostCommand
import fs2.Stream as Fs2Stream
import org.typelevel.log4cats.Logger

import java.nio.charset.Charset
import java.util.UUID
import scala.concurrent.duration.*
import scala.language.postfixOps
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
    connectionTimeout = 5 seconds,
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

  def initRabbitStructs[F[_]: Async: Logger](cfg: Fs2RabbitConfig)(dispatcher: Dispatcher[F]): Fs2Stream[F, AmqpStructures[F]] =
    val structs = for
      rc               <- Resource.eval(RabbitClient[F](cfg, dispatcher))
      conn1            <- rc.createConnection
      conn2            <- rc.createConnection
      con1Id            = UUID.randomUUID().toString
      con2Id            = UUID.randomUUID().toString
      publisherChannel <- rc.createChannel(conn1)
      consumerChannel  <- rc.createChannel(conn2)
      _                <- Resource.eval(Logger[F].info(s"publisherChannel isOpen ${publisherChannel.value.isOpen} for connection $conn1"))
      _                <- Resource.eval(Logger[F].info(s"consumerChannel isOpen ${consumerChannel.value.isOpen} for connection $conn2"))
      publisher        <- Resource.eval(rc.createPublisher[AmqpMessage[String]](instructionEx, instructionRK)(publisherChannel, summon))
      consumer         <- Resource.eval(rc.createAutoAckConsumer(instructionQ)(consumerChannel, summon))
    yield
      conn1.value.setId(con1Id)
      conn2.value.setId(con2Id)
      AmqpStructures(publisher, consumer, List(conn1, conn2))
    Fs2Stream.resource(structs)

  private val instructionQ  = QueueName("instruction_q")
  private val instructionRK = RoutingKey("instruction_q_rk")
  private val instructionEx = ExchangeName("instruction_exchange")

  def initRabbitRoutes[F[_]: Async](dto: SwitchVirtualHostCommand)(dispatcher: Dispatcher[F]): Fs2Stream[F, Unit] =
    import cats.implicits.*
    val effect = for
      rc         <- Resource.eval(RabbitClient[F](reconfig(dto), dispatcher))
      channel    <- rc.createConnectionChannel
      _          <- Resource.eval(rc.declareQueue(DeclarationQueueConfig(instructionQ, Durable, NonExclusive, NonAutoDelete, Map()))(channel))
      exchangeCfg = DeclarationExchangeConfig(instructionEx, Direct, Durable, NonAutoDelete, NonInternal, Map())
      _          <- Resource.eval(rc.declareExchange(exchangeCfg)(channel))
      _          <- Resource.eval(rc.bindQueue(instructionQ, instructionEx, instructionRK)(channel))
    yield channel
    Fs2Stream.eval(effect.use_)

  def closeConnection[F[_]: Async: Logger](structs: AmqpStructures[F]): F[Unit] =
    structs.connections
      .map(conn =>
        Logger[F].info(s"Closing ${conn.value.getId} connection") *> Sync[F].delay {
          Try(conn.value.close())
          ()
        }
      )
      .sequence_
