package dev.rmaiun.learnhttp4s

import cats.effect.{ExitCode, IO, IOApp}

object Main extends IOApp.Simple:
  def run: IO[Unit] =
    Learnhttp4sServer.stream[IO].compile.drain.as(ExitCode.Success)

