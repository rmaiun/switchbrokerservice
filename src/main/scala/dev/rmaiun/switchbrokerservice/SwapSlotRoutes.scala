package dev.rmaiun.switchbrokerservice

import cats.effect.{ Async, Concurrent, Sync }
import cats.implicits.*
import dev.rmaiun.switchbrokerservice.helper.RabbitHelper.MonadThrowable
import io.circe.{ Decoder, Encoder }
import org.http4s.circe.*
import org.http4s.dsl.Http4sDsl
import org.http4s.{ EntityDecoder, EntityEncoder, HttpRoutes }
import org.typelevel.log4cats.Logger
object SwapSlotRoutes:
  case class SwitchBrokerCommand(host: String, port: Int, virtualHost: String, user: String, password: String)
  case class SwapSlotResult(success: Boolean)
  given Decoder[SwitchBrokerCommand]                              = Decoder.derived[SwitchBrokerCommand]
  given [F[_]: Concurrent, T]: EntityDecoder[F, SwitchBrokerCommand] = jsonOf
  given Encoder[SwapSlotResult]                               = Encoder.AsObject.derived[SwapSlotResult]
  given [F[_]]: EntityEncoder[F, SwapSlotResult]              = jsonEncoderOf

  def swapSlotRoutes[F[_]: Async: Concurrent: Logger: MonadThrowable](
    swapSlotService: SwitchBrokerService[F]
  ): HttpRoutes[F] = {
    val dsl = new Http4sDsl[F] {}
    import dsl.*
    HttpRoutes.of[F] { case req @ POST -> Root / "slot" / "swap" =>
      for {
        body   <- req.as[SwitchBrokerCommand]
        result <- swapSlotService.swapSlot(body)
        resp   <- Ok(result)
      } yield resp
    }
  }
