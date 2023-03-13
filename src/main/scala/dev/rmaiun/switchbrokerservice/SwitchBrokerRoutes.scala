package dev.rmaiun.switchbrokerservice

import cats.effect.{Async, Concurrent, Sync}
import cats.implicits.*
import dev.rmaiun.switchbrokerservice.SwitchBrokerRoutes.SwitchBrokerCommand
import dev.rmaiun.switchbrokerservice.sevices.RabbitService.MonadThrowable
import dev.rmaiun.switchbrokerservice.sevices.SwitchBrokerService
import io.circe.{Decoder, Encoder}
import org.http4s.circe.*
import org.http4s.dsl.Http4sDsl
import org.http4s.{EntityDecoder, EntityEncoder, HttpRoutes}
import org.typelevel.log4cats.Logger

import java.time.LocalDateTime
object SwitchBrokerRoutes:
  case class SwitchBrokerCommand(virtualHost: String)
  case class SwitchBrokerResult(timestamp: LocalDateTime)
  given Decoder[SwitchBrokerCommand]                                 = Decoder.derived[SwitchBrokerCommand]
  given [F[_]: Concurrent]: EntityDecoder[F, SwitchBrokerCommand] = jsonOf
  given Encoder[SwitchBrokerResult]                                      = Encoder.AsObject.derived[SwitchBrokerResult]
  given [F[_]]: EntityEncoder[F, SwitchBrokerResult]                     = jsonEncoderOf

  def swapSlotRoutes[F[_]: Async: Concurrent: Logger: MonadThrowable](
    switchBrokerService: SwitchBrokerService[F]
  ): HttpRoutes[F] =
    val dsl = new Http4sDsl[F] {}
    import dsl.*
    HttpRoutes.of[F] { case req @ POST -> Root / "vhost" / "switch" =>
      for
        body   <- req.as[SwitchBrokerCommand]
        result <- switchBrokerService.switchBroker(body)
        resp   <- Ok(result)
      yield resp
    }
