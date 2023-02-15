package dev.rmaiun.switchbrokerservice.sevices

import cats.Monad
import cats.implicits.*
import org.typelevel.log4cats.Logger

object LogService:
  def logPingResult[F[_]: Monad: Logger](msg: String): F[Unit] =
    Logger[F].info(s"Detected message: $msg") *> Monad[F].pure(())
