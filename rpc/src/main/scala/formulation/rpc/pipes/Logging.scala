package formulation.rpc.pipes
import cats.Show
import cats.effect.Sync
import fs2.Pipe
import io.chrisdavenport.log4cats.Logger

object Logging {
  def trace[F[_] : Sync, A : Show](logger: Logger[F], tag: String): Pipe[F, A, A] =
    _.evalTap(entry => logger.trace(s"$tag : ${Show[A].show(entry)}"))
}