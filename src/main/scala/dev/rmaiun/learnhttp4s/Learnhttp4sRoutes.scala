package dev.rmaiun.learnhttp4s

import cats.effect.Sync
import cats.implicits.*
import dev.rmaiun.learnhttp4s.helper.RabbitHelper.MonadThrowable
import org.http4s.HttpRoutes
import org.http4s.dsl.Http4sDsl

object Learnhttp4sRoutes:

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

  def helloWorldRoutes[F[_]: Sync](H: HelloWorld[F]): HttpRoutes[F] =
    val dsl = new Http4sDsl[F] {}
    import dsl.*
    HttpRoutes.of[F] { case GET -> Root / "hello" / name =>
      for
        greeting <- H.hello(HelloWorld.Name(name))
        resp     <- Ok(greeting)
      yield resp
    }
