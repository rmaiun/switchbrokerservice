package dev.rmaiun.learnhttp4s

import cats.Monad
import org.typelevel.log4cats.Logger
import cats.implicits.*

object SomeService {
  def doSomeRepeatableAction[F[_]:Monad:Logger](): F[Unit] = {
    Logger[F].info("Action was triggered") *> Monad[F].pure(())
  }
}
