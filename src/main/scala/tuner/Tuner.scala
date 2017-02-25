package tuner

import cde._
import chisel3._
import chisel3.util._
import dspjunctions.ValidWithSync
import dsptools.numbers._
import dsptools.numbers.implicits._
import dspblocks._
import scala.math._

class TunerIO[T<:Data:Ring]()(implicit val p: Parameters) extends Bundle with HasTunerParameters[T] {
  val config = p(TunerKey(p(DspBlockId)))
  val in = Input(ValidWithSync(Vec(config.lanes, genIn())))
  val out = Output(ValidWithSync(Vec(config.lanes, genOut())))

  // coefficient memory access control registers
  val wen = Input(Bool())
  val wdata = Input(Vec(config.lanes, genCoeff.getOrElse(genOut())))
  val waddr = Input(UInt(log2Up(config.tunerDepth).W))

  // tells the address counter register when to stop
  val counter_stop = Input(UInt(log2Up(config.tunerDepth).W))
}

class Tuner[T<:Data:Ring]()(implicit val p: Parameters) extends Module with HasTunerParameters[T] {
  val io = IO(new TunerIO[T])
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

  // counter tracks which memory address we're on
  // reset on the cycle after a sync or the same cycle as a valid low-to-high transition
  val valid_delay = Reg(next=io.in.valid)
  val counter = TunerCounter(true.B, log2Up(config.tunerDepth), io.counter_stop, io.in.sync, ~valid_delay & io.in.valid)._1

  // memory holding the tuner coefficients
  val coeff_mem = Mem(config.tunerDepth, Vec(config.lanes, genCoeff.getOrElse(genOut())))
  val mem_addr = Mux(io.wen, io.waddr, counter)
  val coeffs = coeff_mem(mem_addr)

  io.out.bits.zip(in).zip(coeffs).foreach { case ((out, in), coeff) => out := in * coeff }

}

object TunerCounter {
  def apply(cond: Bool, n: Int, stop: UInt, sync_reset: Bool, comb_reset: Bool = false.B): (UInt, Bool) = {
    val c = chisel3.util.Counter(cond, n)
    val out = Wire(UInt())
    out := c._1
    if (n > 1) { 
      when (c._1 === stop) { c._1 := 0.U }
      when (sync_reset) { c._1 := 0.U } 
      when (comb_reset) { c._1 := 1.U; out := 0.U }
    }
    (out, c._2)
  }
}
