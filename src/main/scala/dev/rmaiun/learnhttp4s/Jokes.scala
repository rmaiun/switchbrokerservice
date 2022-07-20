package dev.rmaiun.learnhttp4s

import cats.Monad
import cats.effect.{Async, Concurrent}
import cats.implicits.*
import fs2.concurrent.SignallingRef
import io.circe.{Decoder, Encoder}
import org.http4s.*
import org.http4s.Method.*
import org.http4s.circe.*
import org.http4s.client.Client
import org.http4s.client.dsl.Http4sClientDsl
import org.http4s.implicits.*
import org.typelevel.log4cats.Logger

import scala.concurrent.duration.*
import scala.language.postfixOps
import scala.util.Random

trait Jokes[F[_]]:
  def get: F[Jokes.Joke]

object Jokes:
  def apply[F[_]](using ev: Jokes[F]): Jokes[F] = ev

  final case class Joke(joke: String)
  object Joke:
    given Decoder[Joke] = Decoder.derived[Joke]
    given [F[_]: Concurrent]: EntityDecoder[F, Joke] = jsonOf
    given Encoder[Joke] = Encoder.AsObject.derived[Joke]
    given [F[_]]: EntityEncoder[F, Joke] = jsonEncoderOf

  final case class JokeError(e: Throwable) extends RuntimeException

  def impl[F[_]: Concurrent:Monad: Async:Logger](C: Client[F], switch: SignallingRef[F, Boolean]): Jokes[F] = new Jokes[F]:
    val dsl: Http4sClientDsl[F] = new Http4sClientDsl[F]{}
    import dsl.*
    def get: F[Jokes.Joke] = {

      val response = C.expect[Joke](GET(uri"https://icanhazdadjoke.com/"))
        .adaptError{ case t => JokeError(t)} // Prevent Client Json Decoding Failure Leaking
      val id = Random.nextInt(1000).toString
      switch.getAndUpdate(x => !x) *>
        switch.getAndUpdate(x => !x) *>
        fs2.Stream.empty.concurrently(
          fs2.Stream.awakeDelay(1 second).evalTap(_ => SomeService.doSomeRepeatableAction(id)).interruptWhen(switch)
        ).compile.drain *>
        response
    }
