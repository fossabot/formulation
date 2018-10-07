package formulation.rpc
import cats.Show
import cats.effect.Resource
import scodec.bits.BitVector

package object pipes {
  implicit val showBitVector: Show[BitVector] = Show.show(_.toHex)
  implicit def showEnvelope[A]: Show[Envelope[A]] = Show.fromToString
  implicit def showResource[F[_], A]: Show[Resource[F, A]] = Show.show(_ => "<resource>")

}
