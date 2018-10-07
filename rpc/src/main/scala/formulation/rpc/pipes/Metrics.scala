package formulation.rpc.pipes

import _root_.fs2._
import cats.effect.Sync
import formulation.rpc.{MeasureCount, MeasureGauge}

object Metrics {

  def gauge[F[_]: Sync, A, B](gauge: MeasureGauge[F])(pipe: Pipe[F, A, B]): Pipe[F, A, B] =
    _.evalTap(_ => gauge.inc()).through(pipe).evalTap(_ => gauge.dec())

  def countPipe[F[_], A](counter: MeasureCount[F], amount: A => Long, labels: String*)(implicit F: Sync[F]): Pipe[F, A, A] =
    _.evalTap(elem => counter.inc(amount(elem), labels))

  def incPipe[F[_], A](counter: MeasureCount[F], labels: String*)(implicit F: Sync[F]): Pipe[F, A, A] =
    _.evalTap(_ => counter.inc(1, labels))

}
