package dev.rmaiun.switchbrokerservice

import cats.effect.*
import cats.implicits.*
import fs2.Stream as Fs2Stream
import fs2.concurrent.SignallingRef

object Main extends IOApp.Simple:
  def run: IO[Unit] =
    for
      switch    <- SignallingRef[IO, Boolean](false)                              // (1)
      fiberList <- Ref.of[IO, List[Fiber[IO, Throwable, Unit]]](Nil)
      _         <- SwitchBrokerServer.stream[IO](switch, fiberList).compile.drain // (2)
      fibers    <- fiberList.get
      cancelled <- fibers.map(_.cancel).traverse(identity)
      _         <- IO(println(s"cancelled ${cancelled.size} fibers"))
    yield ExitCode.Success
