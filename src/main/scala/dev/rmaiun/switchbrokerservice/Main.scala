package dev.rmaiun.switchbrokerservice

import cats.effect.{ ExitCode, IO, IOApp }
import fs2.concurrent.SignallingRef

object Main extends IOApp.Simple:
  def run: IO[Unit] =
    fs2.Stream
      .eval(SignallingRef[IO, Boolean](false))                  // (1)
      .flatMap(switch => SwitchBrokerServer.stream[IO](switch)) // (2)
      .compile
      .drain
      .as(ExitCode.Success)
