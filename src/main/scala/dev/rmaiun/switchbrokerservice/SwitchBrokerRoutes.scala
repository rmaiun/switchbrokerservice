package dev.rmaiun.switchbrokerservice

import cats.effect.{ Async, Concurrent, Sync }
import cats.implicits.*
import dev.rmaiun.switchbrokerservice.SwitchBrokerRoutes.SwitchVirtualHostCommand
import dev.rmaiun.switchbrokerservice.sevices.RabbitService.MonadThrowable
import dev.rmaiun.switchbrokerservice.sevices.SwitchVirtualHostService
import io.circe.{ Decoder, Encoder }
import org.http4s.circe.*
import org.http4s.dsl.Http4sDsl
import org.http4s.{ EntityDecoder, EntityEncoder, HttpRoutes }
import org.typelevel.log4cats.Logger

import java.time.LocalDateTime
object SwitchBrokerRoutes:
  case class SwitchVirtualHostCommand(virtualHost: String)
  case class SwitchBrokerResult(timestamp: LocalDateTime)
  given Decoder[SwitchVirtualHostCommand]                              = Decoder.derived[SwitchVirtualHostCommand]
  given [F[_]: Concurrent]: EntityDecoder[F, SwitchVirtualHostCommand] = jsonOf
  given Encoder[SwitchBrokerResult]                                    = Encoder.AsObject.derived[SwitchBrokerResult]
  given [F[_]]: EntityEncoder[F, SwitchBrokerResult]                   = jsonEncoderOf

  def switchVirtualHostRoutes[F[_]: Async: Concurrent: Logger: MonadThrowable](
    switchBrokerService: SwitchVirtualHostService[F]
  ): HttpRoutes[F] =
    val dsl = new Http4sDsl[F] {}
    import dsl.*
    HttpRoutes.of[F] { case req @ POST -> Root / "vhost" / "switch" =>
      for
        body   <- req.as[SwitchVirtualHostCommand]
        result <- switchBrokerService.switchBroker(body)
        resp   <- Ok(result)
      yield resp
    }
