package tuner

import cde._
import chisel3._
import chisel3.util._
import dsptools._
import dsptools.numbers._
import dsptools.numbers.implicits._
import dspblocks._
import dspjunctions._
import scala.math._
import spire.syntax.module._
//import spire.implicits._


class TunerIO[T<:Data:Ring, V<:Data:Real]()(implicit val p: Parameters) extends Bundle with HasTunerGenParameters[T, DspComplex[V]] {
  val config = p(TunerKey(p(DspBlockId)))
  val in = Input(ValidWithSync(Vec(config.lanes, genIn())))
  val out = Output(ValidWithSync(Vec(config.lanes, genOut())))

  val data_set_end_status = Output(Bool())
  val data_set_end_clear = Input(Bool())

  // only used if phaseGenerator = SimpleFixed
  val fixed_tuner_phase_re = Input(Vec(config.lanes, genCoeff().asInstanceOf[DspComplex[V]].real))
  val fixed_tuner_phase_im = Input(Vec(config.lanes, genCoeff().asInstanceOf[DspComplex[V]].imag))

  // only used if phaseGenerator = Fixed
  val fixed_tuner_multiplier = Input(UInt(config.kBits.W))
}

class Tuner[T<:Data:Ring, V<:Data:Real]()(implicit val p: Parameters, ev: spire.algebra.Module[DspComplex[V],T]) extends Module with HasTunerGenParameters[T, DspComplex[V]] {
  val io = IO(new TunerIO[T, V])
  val config = p(TunerKey(p(DspBlockId)))

  // delay the data set signals
  val latency = config.pipelineDepth
  io.out.sync := ShiftRegisterWithReset(io.in.sync, latency, 0.U)
  io.out.valid := ShiftRegisterWithReset(io.in.valid, latency, 0.U)

  // feed in zeros when invalid
  val in = Wire(Vec(config.lanes, genIn()))
  when (io.in.valid) {
    in := io.in.bits
  } .otherwise {
    in := Vec.fill(config.lanes)(Ring[T].zero)
  }

  // data set end flag
  val valid_delay = Reg(next=io.out.valid)
  val dses = Reg(init=false.B)
  when (io.data_set_end_clear) {
    dses := false.B
  } .elsewhen (valid_delay & ~io.out.valid) {
    dses := true.B
  }
  io.data_set_end_status := dses

  // coefficients
  val coeffs = List.fill(config.lanes)(Wire(genCoeff()))
  if (config.phaseGenerator == "SimpleFixed") {
    // just grab from SCR File, one coeff per lane
    io.fixed_tuner_phase_re.zip(coeffs).foreach{ case(re, coeff) => coeff.real := re }
    io.fixed_tuner_phase_im.zip(coeffs).foreach{ case(im, coeff) => coeff.imag := im }
  } else { // Fixed
    // create table of coefficients, which are cos(2*pi*k/N)+i*sin(2*pi*k/N)
    val phases = (0 until config.mixerTableSize).map(x => Array(cos(2*Pi/config.mixerTableSize*x),sin(2*Pi/config.mixerTableSize*x)))
    val genCoeffReal = genCoeff().asInstanceOf[DspComplex[V]].real
    val genCoeffImag = genCoeff().asInstanceOf[DspComplex[V]].imag
    val tables = List.fill(config.lanes)(Vec(phases.map(x => {
      val real = Wire(genCoeffReal.cloneType)
      val imag = Wire(genCoeffImag.cloneType)
      real := genCoeffReal.fromDouble(x(0))
      imag := genCoeffImag.fromDouble(x(1))
      val coeff = Wire(DspComplex(genCoeffReal, genCoeffImag))
      coeff.real := real
      coeff.imag := imag
      coeff
    })))
    tables.zipWithIndex.zip(coeffs).foreach{ case ((table, laneID), coeff) => {
      coeff := table(io.fixed_tuner_multiplier*laneID.U)
    }}
  }

  // multiply, add pipeline registers at output
  // lots of weirdness to get handle arbitrary input type, mostly
  //io.out.bits.zip(in).zip(coeffs).foreach { case ((out, in), coeff) => out := ShiftRegister(config.pipelineDepth, coeff :* in) }
  io.out.bits.zip(in).zip(coeffs).foreach { case ((out: DspComplex[V], in: T), coeff: DspComplex[V]) => {
    //val ops = new spire.syntax.ModuleOps(coeff)
    //val x: DspComplex[V] = DspComplex[V].timesl(coeff, in) //coeff :* in
    //val x: DspComplex[V] = ops :* in
    val x: DspComplex[V] = ev.timesr(coeff, in)
    out := ShiftRegister(x, config.pipelineDepth)
  }}
}
