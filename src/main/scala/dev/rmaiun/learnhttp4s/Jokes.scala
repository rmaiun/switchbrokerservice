package dev.rmaiun.learnhttp4s

import cats.Monad
import cats.effect.*
import cats.implicits.*
import dev.profunktor.fs2rabbit.model.*
import dev.rmaiun.learnhttp4s.helper.RabbitHelper
import dev.rmaiun.learnhttp4s.helper.RabbitHelper.{AmqpPublisher, AmqpStructures}
import fs2.Stream as Fs2Stream
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
  def get2: F[Jokes.Joke]

object Jokes:
  def apply[F[_]](using ev: Jokes[F]): Jokes[F] = ev

  final case class Joke(joke: String)
  object Joke:
    given Decoder[Joke]                              = Decoder.derived[Joke]
    given [F[_]: Concurrent]: EntityDecoder[F, Joke] = jsonOf
    given Encoder[Joke]                              = Encoder.AsObject.derived[Joke]
    given [F[_]]: EntityEncoder[F, Joke]             = jsonEncoderOf

  final case class JokeError(e: Throwable) extends RuntimeException

  def impl[F[_]: Concurrent: Monad: Async: Logger](
    C: Client[F],
    switch: SignallingRef[F, Boolean],
    pub: Ref[F, AmqpPublisher[F]]
  ): Jokes[F] =
    new Jokes[F]:
      val dsl: Http4sClientDsl[F] = new Http4sClientDsl[F] {}
      import dsl.*
      def get: F[Jokes.Joke] =
        val response: F[Jokes.Joke] = C.expect[Joke](GET(uri"https://icanhazdadjoke.com/")).adaptError { case t =>
          JokeError(t)
        }
        for
          r       <- response
          _       <- refreshSwitch(switch)
          _       <- Concurrent[F].start(processReconnectionToBroker(switch, pub))
          sender <- pub.get
          _ <- sender(AmqpMessage(r.joke, AmqpProperties()))
        yield r

      def processReconnectionToBroker(switch: SignallingRef[F, Boolean], pub: Ref[F, AmqpPublisher[F]]): F[Unit] ={
        val consumerStream = for{
          structs <- RabbitHelper.initConnection(RabbitHelper.config2)
          _ <- Fs2Stream.eval(pub.update(_ => structs.botInPublisher))
          consumer <- structs.botInConsumer
            .evalTap(msg => SomeService.doSomeRepeatableAction(2.toString, msg.payload))
            .interruptWhen(switch)
        }yield consumer
        consumerStream.compile.drain
      }

      def get2: F[Jokes.Joke] =
        val response: F[Jokes.Joke] = C.expect[Joke](GET(uri"https://icanhazdadjoke.com/")).adaptError { case t =>
          JokeError(t)
        }
        for
          r       <- response
//          _       <- refreshSwitch(switch)
          sender <- pub.get
          _ <- sender(AmqpMessage(r.joke, AmqpProperties()))
        yield r

      def refreshSwitch[F[_]: Sync](switch: SignallingRef[F, Boolean]): F[Unit] =
        switch.update(x => !x) *> switch.update(x => !x)
