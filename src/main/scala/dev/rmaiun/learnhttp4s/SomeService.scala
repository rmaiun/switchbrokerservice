package dev.rmaiun.learnhttp4s

import cats.Monad
import org.typelevel.log4cats.Logger
import cats.implicits.*

object SomeService:
  def doSomeRepeatableAction[F[_]: Monad: Logger](marker: String, msg: String): F[Unit] =
    Logger[F].info(s"Action was triggered [$marker] $msg") *> Monad[F].pure(())
