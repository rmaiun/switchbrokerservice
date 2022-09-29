package dev.rmaiun.learnhttp4s

import cats.Monad
import cats.effect.{ Async, Concurrent, MonadCancel, Ref, Sync }
import cats.implicits.catsSyntaxApply
import dev.rmaiun.learnhttp4s.helper.RabbitHelper
import dev.rmaiun.learnhttp4s.helper.RabbitHelper.{ AmqpPublisher, MonadThrowable }
import fs2.Stream as Fs2Stream
import fs2.concurrent.SignallingRef
import org.typelevel.log4cats.Logger
import cats.implicits.*
import dev.profunktor.fs2rabbit.model.*
import dev.rmaiun.learnhttp4s.Learnhttp4sRoutes.{ SwapSlotCommand, SwapSlotResult }

import scala.util.Random

trait SwapSlotService[F[_]]:
  def swapSlot(dto: SwapSlotCommand): F[SwapSlotResult]

object SwapSlotService:

  def impl[F[_]: Concurrent: Async: Logger](
    switch: SignallingRef[F, Boolean],
    pub: Ref[F, AmqpPublisher[F]]
  )(using MT: MonadThrowable[F]): SwapSlotService[F] = new SwapSlotService[F]:
    override def swapSlot(dto: SwapSlotCommand): F[SwapSlotResult] =
      val switchBrokerF = for
        _      <- refreshSwitch(switch)
        _      <- Concurrent[F].start(processReconnectionToBroker(dto, switch, pub))
        sender <- pub.get
        _      <- sender(AmqpMessage("test", AmqpProperties()))
      yield SwapSlotResult(true)
      MT.handleErrorWith(switchBrokerF) { case _ =>
        MT.pure(SwapSlotResult(false))
      }

    def refreshSwitch(switch: SignallingRef[F, Boolean]): F[Unit] =
      switch.update(x => !x) *> switch.update(x => !x)

    def processReconnectionToBroker(
      dto: SwapSlotCommand,
      switch: SignallingRef[F, Boolean],
      pub: Ref[F, AmqpPublisher[F]]
    ): F[Unit] =
      val randomInt = Random.nextInt(1000)
      val consumerStream = for
        structs <- RabbitHelper.initConnection(RabbitHelper.reconfig(dto.port, dto.virtualHost))
        _       <- Fs2Stream.eval(pub.update(_ => structs.botInPublisher))
        consumer <- structs.botInConsumer
                      .evalTap(msg => SomeService.doSomeRepeatableAction(randomInt.toString, msg.payload))
                      .interruptWhen(switch)
      yield consumer
      consumerStream.compile.drain
