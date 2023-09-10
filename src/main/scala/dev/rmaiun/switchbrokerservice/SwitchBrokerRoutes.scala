package dev.rmaiun.switchbrokerservice

import cats.effect.{ Async, Concurrent, Sync }
import cats.implicits.*
import dev.rmaiun.switchbrokerservice.SwitchBrokerRoutes.{ Contextual, RequestContext, SwitchVirtualHostCommand }
import dev.rmaiun.switchbrokerservice.sevices.RabbitService.MonadThrowable
import dev.rmaiun.switchbrokerservice.sevices.SwitchVirtualHostService
import io.circe.{ Decoder, Encoder }
import org.http4s.circe.*
import org.http4s.dsl.Http4sDsl
import org.http4s.server.middleware.RequestId
import org.http4s.{ EntityDecoder, EntityEncoder, HttpRoutes, Request, Response }
import org.typelevel.log4cats.{ Logger, StructuredLogger }

import java.time.LocalDateTime
import java.util.UUID
object SwitchBrokerRoutes:
  case class SwitchVirtualHostCommand(virtualHost: String)
  case class SwitchBrokerResult(timestamp: LocalDateTime)
  given Decoder[SwitchVirtualHostCommand]                              = Decoder.derived[SwitchVirtualHostCommand]
  given [F[_]: Concurrent]: EntityDecoder[F, SwitchVirtualHostCommand] = jsonOf
  given Encoder[SwitchBrokerResult]                                    = Encoder.AsObject.derived[SwitchBrokerResult]
  given [F[_]]: EntityEncoder[F, SwitchBrokerResult]                   = jsonEncoderOf

  type Contextual[T] = RequestContext ?=> T

  final case class RequestContext(requestId: String)
  def contextual[F[_]: Sync](request: Request[F])(f: Contextual[F[Response[F]]]): F[Response[F]] =
    val context: RequestContext =
      request.attributes.lookup(RequestId.requestIdAttrKey).fold(RequestContext(UUID.randomUUID.toString))(RequestContext.apply)
    f(using context)

  def switchVirtualHostRoutes[F[_]: Async: Concurrent: MonadThrowable](
    switchBrokerService: SwitchVirtualHostService[F]
  ): HttpRoutes[F] =
    val dsl = new Http4sDsl[F] {}
    import dsl.*
    HttpRoutes.of[F] { case req @ POST -> Root / "vhost" / "switch" =>
      contextual(req) {
        for
          body   <- req.as[SwitchVirtualHostCommand]
          result <- switchBrokerService.switchBroker(body)
          resp   <- Ok(result)
        yield resp
      }

    }

class ContextualLogger[F[_]](loggger: Logger[F]):
  private def contextual(message: => String): Contextual[String]   = s"${summon[RequestContext].requestId} - $message"
  def error(message: => String): Contextual[F[Unit]]               = loggger.error(contextual(message))
  def warn(message: => String): Contextual[F[Unit]]                = loggger.warn(contextual(message))
  def info(message: => String): Contextual[F[Unit]]                = loggger.info(contextual(message))
  def debug(message: => String): Contextual[F[Unit]]               = loggger.debug(contextual(message))
  def trace(message: => String): Contextual[F[Unit]]               = loggger.trace(contextual(message))
  def error(t: Throwable)(message: => String): Contextual[F[Unit]] = loggger.error(t)(contextual(message))
  def warn(t: Throwable)(message: => String): Contextual[F[Unit]]  = loggger.warn(t)(contextual(message))
  def info(t: Throwable)(message: => String): Contextual[F[Unit]]  = loggger.info(t)(contextual(message))
  def debug(t: Throwable)(message: => String): Contextual[F[Unit]] = loggger.debug(t)(contextual(message))
  def trace(t: Throwable)(message: => String): Contextual[F[Unit]] = loggger.trace(t)(contextual(message))
