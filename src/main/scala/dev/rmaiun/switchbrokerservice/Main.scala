package dev.rmaiun.switchbrokerservice

import cats.effect.*
import cats.implicits.*
import fs2.Stream as Fs2Stream
import fs2.concurrent.SignallingRef

object Main extends IOApp.Simple:
  def run: IO[Unit] =
    for
      switch <- SignallingRef[IO, Boolean](false)                   // (1)
      _      <- SwitchBrokerServer.stream[IO](switch).compile.drain       // (2)
    yield ExitCode.Success
