package dev.rmaiun.switchbrokerservice.sevices

import cats.Monad
import cats.effect.kernel.Sync
import cats.implicits.*
import dev.rmaiun.switchbrokerservice.ContextualLogger
import dev.rmaiun.switchbrokerservice.SwitchBrokerRoutes.RequestContext
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

object LogService:
  def logPingResult[F[_]: Monad:Sync](msg: String)(using rk:RequestContext): F[Unit] =
    val logger: ContextualLogger[F] = ContextualLogger[F](Slf4jLogger.getLogger[F])
    logger.info(s"Detected message: $msg") *> Monad[F].pure(())
