package tuner

import chisel3.util._
import chisel3._
import dspjunctions._
import dspblocks._
import scala.math._
import dsptools.numbers.{Real, DspComplex}
import dsptools.numbers.implicits._
import dsptools.counters._
import dsptools._
import cde.Parameters
import internal.firrtl.{Width, BinaryPoint}

class TunerIO[T<:Data:Real]()(implicit val p: Parameters) extends Bundle with HasTunerGenParameters[T] {
  val config = p(TunerKey)(p)
  val in = Input(ValidWithSync(Vec(lanesIn, genIn())))
  val out = Output(ValidWithSync(Vec(lanesOut, genOut())))

  val mult = Input(Vec(lanesIn, genMult.getOrElse(genOut())))
}

class Tuner[T<:Data:Real]()(implicit val p: Parameters) extends Module with HasTunerGenParameters[T] {
  val io = IO(new TunerIO[T])
  val config = p(TunerKey)(p)

  // delay the data set signals
  val latency = config.pipelineDepth
  io.out.sync := ShiftRegisterWithReset(io.in.sync, latency, 0.U)
  io.out.valid := ShiftRegisterWithReset(io.in.valid, latency, 0.U)

  // feed in zeros when invalid
  val in = Wire(Vec(lanesIn, genIn()))
  //when (io.in.valid) {
    in := io.in.bits
  //} .otherwise {
  //  in := Wire(Vec(lanesIn, implicitly[Real[T]].zero))
  //}

  //io.out.bits.zip(in).zip(io.mult).foreach { case ((out, in), mult) => out := in * mult }
  io.out.bits.zip(in).zip(io.mult).foreach { case ((out, in), mult) => out := in + mult }

}
