package dev.rmaiun.learnhttp4s

import cats.effect.{ Async, Concurrent, Sync }
import cats.implicits.*
import dev.rmaiun.learnhttp4s.helper.RabbitHelper.MonadThrowable
import io.circe.Encoder
import org.http4s.HttpRoutes
import org.http4s.dsl.Http4sDsl
import io.circe.{ Decoder, Encoder }
import org.http4s.circe.*
import org.http4s.{ EntityDecoder, EntityEncoder }
import org.typelevel.log4cats.Logger
object Learnhttp4sRoutes:
  case class SwapSlotCommand(port: Int, virtualHost: String)

  case class SwapSlotResult(success: Boolean)

  given Decoder[SwapSlotCommand] = Decoder.derived[SwapSlotCommand]

  given [F[_]: Concurrent]: EntityDecoder[F, SwapSlotCommand] = jsonOf

  given Encoder[SwapSlotResult] = Encoder.AsObject.derived[SwapSlotResult]

  given [F[_]]: EntityEncoder[F, SwapSlotResult] = jsonEncoderOf

  def jokeRoutes[F[_]: Sync: MonadThrowable](J: Jokes[F]): HttpRoutes[F] =
    val dsl = new Http4sDsl[F] {}
    import dsl.*
    HttpRoutes.of[F] {
      case GET -> Root / "joke" =>
        val x = for
          joke <- J.get
          resp <- Ok(joke)
        yield resp
        x
      case GET -> Root / "joke2" =>
        for
          joke <- J.get2
          resp <- Ok(joke)
        yield resp
    }

  def swapSlotRoutes[F[_]: Async: Concurrent: Logger: MonadThrowable](
    swapSlotService: SwapSlotService[F]
  ): HttpRoutes[F] =
    val dsl = new Http4sDsl[F] {}
    import dsl.*
    HttpRoutes.of[F] { case req @ POST -> Root / "slot" / "swap" =>
      for
        body   <- req.as[SwapSlotCommand]
        result <- swapSlotService.swapSlot(body)
        resp   <- Ok(result)
      yield resp
    }

  def helloWorldRoutes[F[_]: Sync](H: HelloWorld[F]): HttpRoutes[F] =
    val dsl = new Http4sDsl[F] {}
    import dsl.*
    HttpRoutes.of[F] { case GET -> Root / "hello" / name =>
      for
        greeting <- H.hello(HelloWorld.Name(name))
        resp     <- Ok(greeting)
      yield resp
    }
