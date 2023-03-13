package dev.rmaiun.switchbrokerservice.sevices

import cats.Monad
import cats.effect.*
import cats.implicits.*
import dev.profunktor.fs2rabbit.model.*
import dev.rmaiun.switchbrokerservice.SwitchBrokerRoutes.{SwitchBrokerCommand, SwitchBrokerResult}
import dev.rmaiun.switchbrokerservice.sevices.RabbitService.{AmqpPublisher, MonadThrowable}
import dev.rmaiun.switchbrokerservice.sevices.{RabbitService, SwitchBrokerService}
import fs2.Stream as Fs2Stream
import fs2.concurrent.SignallingRef
import org.typelevel.log4cats.Logger

import java.time.LocalDateTime
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.language.postfixOps
import scala.util.Random
trait SwitchBrokerService[F[_]]:
  def switchBroker(dto: SwitchBrokerCommand): F[SwitchBrokerResult]

object SwitchBrokerService:
  def impl[F[_]: Concurrent: Async: Logger](
    switch: SignallingRef[F, Boolean],
    pub: Ref[F, AmqpPublisher[F]]
  )(using MT: MonadThrowable[F]): SwitchBrokerService[F] = new SwitchBrokerService[F]:
    override def switchBroker(dto: SwitchBrokerCommand): F[SwitchBrokerResult] =
      val switchBrokerF = for
        _ <- refreshSwitch(switch)
        _ <- Concurrent[F].start(processReconnectionToBroker(dto, switch, pub))
      yield SwitchBrokerResult(LocalDateTime.now())
      MT.handleErrorWith(switchBrokerF)(_ => MT.pure(SwitchBrokerResult(LocalDateTime.now())))

    def refreshSwitch(switch: SignallingRef[F, Boolean]): F[Unit] =
      switch.update(x => !x) *> switch.update(x => !x)

    def processReconnectionToBroker(
      dto: SwitchBrokerCommand,
      switch: SignallingRef[F, Boolean],
      pub: Ref[F, AmqpPublisher[F]]
    ): F[Unit] =

      val consumerStream = for
        structs <- RabbitService.initRabbitStructs(RabbitService.reconfig(dto))
        _       <- Fs2Stream.eval(pub.update(_ => structs.instructionPublisher))
        consumer <- structs.instructionConsumer
                      .evalTap(msg => LogService.logPingResult(msg.payload))
                      .interruptWhen(switch)
      yield consumer
      consumerStream
        .concurrently(runPingSignals(pub, switch))
        .compile.drain

  private def runPingSignals[F[_]: Async](pub: Ref[F, AmqpPublisher[F]], switch: SignallingRef[F, Boolean]): Fs2Stream[F, FiniteDuration] =
    val randomInt = Random.nextInt(1000)
    Fs2Stream
      .awakeDelay(2 seconds)
      .evalTap(_ => Monad[F].flatMap(pub.get)(p => p(AmqpMessage(randomInt.toString, new AmqpProperties()))))
      .interruptWhen(switch)
