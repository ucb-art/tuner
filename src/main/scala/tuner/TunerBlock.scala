package tuner

import cde.Parameters
import chisel3._
import dsptools._
import dsptools.numbers._
import dsptools.numbers.implicits._
import dspjunctions._
import dspblocks._
import ipxact._

class TunerBlock[T <: Data:Ring, V <: Data:Real]()(implicit p: Parameters, ev: spire.algebra.Module[DspComplex[V],T]) extends DspBlock()(p) {
  def controls = Seq()
  def statuses = Seq()

  lazy val module = new TunerBlockModule[T, V](this)
  
  addStatus("Data_Set_End_Status")
  addControl("Data_Set_End_Clear", 0.U)

  val config = p(TunerKey(p(DspBlockId)))
  if (config.phaseGenerator == "SimpleFixed") {
    (0 until config.lanes).foreach { i =>
      addControl(s"FixedTunerPhaseRe_$i", 0.U)
      addControl(s"FixedTunerPhaseIm_$i", 0.U)
    }
  } else if (config.phaseGenerator == "Fixed") {
    addControl("FixedTunerMultiplier", 0.U)
  }
}

class TunerBlockModule[T <: Data:Ring, V <: Data:Real](outer: DspBlock)(implicit p: Parameters, ev: spire.algebra.Module[DspComplex[V],T])
  extends GenDspBlockModule[T, DspComplex[V]](outer)(p) with HasTunerGenParameters[T, DspComplex[V]] {

  val config = p(TunerKey(p(DspBlockId)))

  if (config.phaseGenerator == "SimpleFixed") {
    val module = Module(new TunerSimpleFixed[T, V])
    
    module.io.in <> unpackInput(lanesIn, genIn())
    unpackOutput(lanesOut, genOut()) <> module.io.out

    status("Data_Set_End_Status") := module.io.data_set_end_status
    module.io.data_set_end_clear := control("Data_Set_End_Clear")

    val fixed_tuner_phase_re = Wire(Vec(config.lanes, genCoeff().asInstanceOf[DspComplex[V]].real))
    val re = fixed_tuner_phase_re.zipWithIndex.map{case (x, i) => x.fromBits(control(s"FixedTunerPhaseRe_$i"))}
    module.io.fixed_tuner_phase_re := re
    val fixed_tuner_phase_im = Wire(Vec(config.lanes, genCoeff().asInstanceOf[DspComplex[V]].imag))
    val im = fixed_tuner_phase_im.zipWithIndex.map{case (x, i) => x.fromBits(control(s"FixedTunerPhaseIm_$i"))}
    module.io.fixed_tuner_phase_im := im

    IPXactComponents._ipxactComponents += DspIPXact.makeDspBlockComponent(baseAddr, uuid, module.name)

  } else if (config.phaseGenerator == "Fixed") {
    val module = Module(new TunerFixed[T, V])
    
    module.io.in <> unpackInput(lanesIn, genIn())
    unpackOutput(lanesOut, genOut()) <> module.io.out

    status("Data_Set_End_Status") := module.io.data_set_end_status
    module.io.data_set_end_clear := control("Data_Set_End_Clear")

    module.io.fixed_tuner_multiplier := control("FixedTunerMultiplier")
    
    IPXactComponents._ipxactComponents += DspIPXact.makeDspBlockComponent(baseAddr, uuid, this.name)

  }

}
