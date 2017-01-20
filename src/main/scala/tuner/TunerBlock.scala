package tuner

import cde.Parameters
import chisel3._
import dsptools._
import dsptools.numbers._
import dspjunctions._
import dspblocks._

class LazyTunerBlock[T <: Data : Real]()(implicit p: Parameters) extends LazyDspBlock()(p) {
  def controls = Seq()
  def statuses = Seq()

  lazy val module = new TunerBlock[T](this)
  val config = p(TunerKey)(p)
}

class TunerBlock[T <: Data : Real](outer: LazyDspBlock)(implicit p: Parameters)
  extends GenDspBlock[T, T](outer)(p) with HasTunerGenParameters[T] {

  val baseAddr = BigInt(0)
  val module = Module(new Tuner[T])
  val config = p(TunerKey)(p)
  val mio = module.io.asInstanceOf[TunerIO[T]]

  module.io.in <> unpackInput(lanesIn, genIn())
  unpackOutput(lanesOut, genOut()) <> module.io.out

}
