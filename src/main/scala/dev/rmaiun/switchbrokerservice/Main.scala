package dev.rmaiun.switchbrokerservice

import cats.effect.{ ExitCode, IO, IOApp }
import fs2.concurrent.SignallingRef

object Main extends IOApp.Simple:
  def run: IO[Unit] =
    fs2.Stream
      .eval(SignallingRef[IO, Boolean](false))
      .flatMap(switch => SwitchBrokerServer.stream[IO](switch))
      .compile
      .drain
      .as(ExitCode.Success)
