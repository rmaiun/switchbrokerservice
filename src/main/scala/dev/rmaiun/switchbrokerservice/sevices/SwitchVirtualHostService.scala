package dev.rmaiun.switchbrokerservice.sevices

import cats.Monad
import cats.data.EitherT
import cats.effect.*
import cats.effect.std.Dispatcher
import cats.implicits.*
import dev.profunktor.fs2rabbit.model.*
import dev.rmaiun.switchbrokerservice.SwitchBrokerRoutes.{ SwitchBrokerResult, SwitchVirtualHostCommand }
import dev.rmaiun.switchbrokerservice.sevices.RabbitService.{ AmqpPublisher, AmqpStructures, MonadThrowable }
import dev.rmaiun.switchbrokerservice.sevices.{ RabbitService, SwitchVirtualHostService }
import fs2.Stream as Fs2Stream
import fs2.concurrent.SignallingRef
import org.typelevel.log4cats.{ Logger, StructuredLogger }

import java.time.LocalDateTime
import scala.concurrent.duration.{ DurationInt, FiniteDuration }
import scala.language.postfixOps
import scala.util.{ Random, Try }

trait SwitchVirtualHostService[F[_]]:
  def switchBroker(dto: SwitchVirtualHostCommand): F[SwitchBrokerResult]

object SwitchVirtualHostService:
  def impl[F[_]: Concurrent: Async: Logger](
    switch: SignallingRef[F, Boolean],
    structsRef: Ref[F, AmqpStructures[F]],
    dispatcher: Dispatcher[F],
    logger: StructuredLogger[F]
  )(using MT: MonadThrowable[F]): SwitchVirtualHostService[F] = new SwitchVirtualHostService[F]:

    override def switchBroker(dto: SwitchVirtualHostCommand): F[SwitchBrokerResult] =
      val switchBrokerF = for
        structs <- structsRef.get
        _       <- RabbitService.closeConnection(structs)
        _       <- logger.info("Old connections are closed")
        _       <- refreshSwitch(switch)
        _       <- logger.info("Switch is refreshed")
        _       <- Concurrent[F].start(processReconnectionToBroker(dto, switch, structsRef))
        _       <- logger.info("Broker is reconnected")
      yield SwitchBrokerResult(LocalDateTime.now())
      MT.handleErrorWith(switchBrokerF)(err =>
        logger.error(err)(s"Error while switch to ${dto.virtualHost} vhost") *>
          MT.pure(SwitchBrokerResult(LocalDateTime.now()))
      )

    private def refreshSwitch(switch: SignallingRef[F, Boolean]): F[Unit] =
      switch.update(x => !x) *> switch.update(x => !x)

    private def processReconnectionToBroker(
      dto: SwitchVirtualHostCommand,
      switch: SignallingRef[F, Boolean],
      structsRef: Ref[F, AmqpStructures[F]]
    ): F[Unit] =
      val consumerStream = for
        structs <- RabbitService.initRabbitStructs(RabbitService.reconfig(dto))(dispatcher)
        _       <- Fs2Stream.eval(structsRef.update(_ => structs))
        _ <- structs.instructionConsumer
               .evalTap(msg => LogService.logPingResult(msg.payload))
               .interruptWhen(switch)
               .concurrently(runPingSignals(structs.instructionPublisher, switch))
      yield structs
      consumerStream.compile.drain

    private def runPingSignals(
      pub: AmqpPublisher[F],
      switch: SignallingRef[F, Boolean]
    ): Fs2Stream[F, FiniteDuration] =
      val randomInt = Random.nextInt(1000)
      Fs2Stream
        .awakeDelay(2 seconds)
        .evalTap(_ => pub(AmqpMessage(randomInt.toString, new AmqpProperties())))
        .interruptWhen(switch)
        .handleErrorWith(err => Fs2Stream.eval(logger.warn(err.getMessage)).map(_ => 0 seconds))
        .onFinalize(logger.info("Reconnected runPingSignals is finalized"))
