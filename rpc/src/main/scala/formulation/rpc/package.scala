package formulation


import java.util.concurrent.TimeUnit

import cats.data.{Kleisli, NonEmptyList}
import cats.effect.{Clock, Sync}
import cats.implicits._
import scodec.bits.BitVector

import scala.concurrent.duration.Duration
import _root_.fs2._

import scala.util.Random

package object rpc {

  type ServerMiddleware[F[_]] = Kleisli[F, Envelope[Request], BitVector] => Kleisli[F, Envelope[Request], BitVector]


  implicit class RichIO[F[_], A](io: F[A])(implicit F: Sync[F]) {
    def summarized[B, C](f: (B, B) => C)(summary: F[B]): F[(C, A)] =
      for {
        start <- summary
        value <- io
        end   <- summary
      } yield (f(start, end), value)

    final def timed0(time: F[Long]): F[(Duration, A)] =
      summarized[Long, Duration]((start, end) => Duration.fromNanos(end - start))(time)

    final def timed: F[(Duration, A)] = timed0(nanoTime)

    def measure(histo: MeasureHisto[F]): F[A] =
      io.timed.flatMap { case (dur, res) => histo.observe(dur.toMillis) >> F.pure(res) }

    private val nanoTime = F.delay(System.nanoTime())
  }

  implicit class RichNonEmptyList[A](nel: NonEmptyList[A]) {
    def random[F[_]](implicit F: Sync[F]): F[A] = F.delay(Random.nextInt(nel.length)).map(idx => nel.toList(idx))
  }

}
