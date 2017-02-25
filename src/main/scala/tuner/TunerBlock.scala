package tuner

import cde.Parameters
import chisel3._
import dsptools._
import dsptools.numbers._
import dspjunctions._
import dspblocks._
import ipxact._

class TunerBlock[T <: Data : Ring]()(implicit p: Parameters) extends DspBlock()(p) {
  def controls = Seq()
  def statuses = Seq()

  lazy val module = new TunerBlockModule[T](this)
  
  //val config = p(TunerKey(p(DspBlockId)))
  //(0 until config.numberOfTaps).map( i =>
  //  addControl(s"firCoeff$i", 0.U)
  //)
  //addStatus("firStatus")

}

class TunerBlockModule[T <: Data : Ring](outer: DspBlock)(implicit p: Parameters)
  extends GenDspBlockModule[T, T](outer)(p) with HasTunerParameters[T] {
  val module = Module(new Tuner)
  val config = p(TunerKey(p(DspBlockId)))
  
  module.io.in <> unpackInput(lanesIn, genIn())
  unpackOutput(lanesOut, genOut()) <> module.io.out

  //val taps = Wire(Vec(config.numberOfTaps, genTap.getOrElse(genIn())))
  //val w = taps.zipWithIndex.map{case (x, i) => x.fromBits(control(s"firCoeff$i"))}
  //module.io.taps := w
  //status("firStatus") := module.io.out.sync

  IPXactComponents._ipxactComponents += DspIPXact.makeDspBlockComponent
}
