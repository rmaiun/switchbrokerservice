package dev.rmaiun.switchbrokerservice

import cats.Monad
import cats.effect.*
import cats.implicits.*
import dev.profunktor.fs2rabbit.model.*
import dev.rmaiun.switchbrokerservice.SwapSlotRoutes.{SwapSlotCommand, SwapSlotResult}
import dev.rmaiun.switchbrokerservice.helper.RabbitHelper.{AmqpPublisher, MonadThrowable}
import dev.rmaiun.switchbrokerservice.SwapSlotRoutes.{SwapSlotCommand, SwapSlotResult}
import dev.rmaiun.switchbrokerservice.helper.RabbitHelper
import fs2.Stream as Fs2Stream
import fs2.concurrent.SignallingRef
import org.typelevel.log4cats.Logger

import scala.util.Random

trait SwapSlotService[F[_]]:
  def swapSlot(dto: SwapSlotCommand): F[SwapSlotResult]

object SwapSlotService {
  def impl[F[_]: Concurrent: Async: Logger](
    switch: SignallingRef[F, Boolean],
    pub: Ref[F, AmqpPublisher[F]]
  )(using MT: MonadThrowable[F]): SwapSlotService[F] = new SwapSlotService[F] {
    override def swapSlot(dto: SwapSlotCommand): F[SwapSlotResult] = {
      val switchBrokerF = for {
        _      <- refreshSwitch(switch)
        _      <- Concurrent[F].start(processReconnectionToBroker(dto, switch, pub))
        sender <- pub.get
        _      <- sender(AmqpMessage("test", AmqpProperties()))
      } yield SwapSlotResult(true)
      MT.handleErrorWith(switchBrokerF) { case _ =>
        MT.pure(SwapSlotResult(false))
      }
    }

    def refreshSwitch(switch: SignallingRef[F, Boolean]): F[Unit] =
      switch.update(x => !x) *> switch.update(x => !x)

    def processReconnectionToBroker(
      dto: SwapSlotCommand,
      switch: SignallingRef[F, Boolean],
      pub: Ref[F, AmqpPublisher[F]]
    ): F[Unit] = {
      val randomInt = Random.nextInt(1000)
      val consumerStream = for {
        structs <- RabbitHelper.initConnection(RabbitHelper.reconfig(dto))
        _       <- Fs2Stream.eval(pub.update(_ => structs.botInPublisher))
        consumer <- structs.botInConsumer
                      .evalTap(msg => SomeService.doSomeRepeatableAction(randomInt.toString, msg.payload))
                      .interruptWhen(switch)
      } yield consumer
      consumerStream.compile.drain
    }
  }
}
