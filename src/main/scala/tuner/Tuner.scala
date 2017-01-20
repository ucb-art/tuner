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
}

class Tuner[T<:Data:Real]()(implicit val p: Parameters) extends Module with HasTunerGenParameters[T] {
  val io = IO(new TunerIO[T])
  val config = p(TunerKey)(p)
  
  io.out.bits := Reg(next=io.in.bits)
  io.out.valid := io.in.valid
  io.out.sync := Mux(io.in.valid, io.in.sync, false.B)
}
