package tuner

import breeze.math.{Complex}
import breeze.signal.{fourierTr}
import breeze.linalg._
import chisel3._
import chisel3.experimental._
import chisel3.util._
import chisel3.iotesters._
import firrtl_interpreter.InterpreterOptions
import dsptools.numbers.{DspReal, SIntOrder, SIntRing}
import dsptools.{DspContext, DspTester, Grow}
import org.scalatest.{FlatSpec, Matchers}
import dsptools.numbers.implicits._
import dsptools.numbers.{DspComplex, Real}
import scala.util.Random
import scala.math._
import org.scalatest.Tag
import dspjunctions._
import dspblocks._

import cde._
import junctions._
import uncore.tilelink._
import uncore.coherence._

import craft._
import dsptools._
import dsptools.numbers.{Field=>_,_}
import dsptools.numbers.implicits._

import scala.collection.mutable.Map

// create a new DSP Configuration
object TunerConfigBuilder {
  def apply[T <: Data : Real](
    id: String, tunerConfig: TunerConfig, gen: () => T): Config = new Config(
    (pname, site, here) => pname match {
      case TunerKey(id) => tunerConfig
      case IPXACTParameters(id) => {
        val parameterMap = Map[String, String]()

        // Conjure up some IPXACT synthsized parameters.
        val gk = site(GenKey(id))
	val tunerconfig = site(TunerKey(id))
        val totalWidth = gk.lanesIn * gen().getWidth
        parameterMap ++= List(
          ("InputLanes", gk.lanesIn.toString),
          ("InputTotalBits", totalWidth.toString),
          ("OutputLanes", gk.lanesOut.toString),
          ("OutputTotalBits", totalWidth.toString),
          ("OutputPartialBitReversed", "1")
        )

        // add fractional bits if it's fixed point
        // TODO: check if it's fixed point or not
        gen() match {
          case fp: FixedPoint =>
            val fractionalBits = fp.binaryPoint
            parameterMap ++= List(
              ("InputFractionalBits", fractionalBits.toString),
              ("OutputFractionalBits", fractionalBits.toString)
            )
          case _ =>
        }

        // tech stuff, TODO
        parameterMap ++= List(("ClockRate", "100"), ("Technology", "TSMC16nm"))

        parameterMap
      }
      case _ => throw new CDEMatchError
    }) ++
  ConfigBuilder.dspBlockParams(id, tunerConfig.lanes, () => gen())
  def standalone[T <: Data : Real](
    id: String, tunerConfig: TunerConfig, gen: () => T): Config =
    apply(id, tunerConfig, gen) ++
    ConfigBuilder.buildDSP(id, {implicit p: Parameters => new LazyTunerBlock[T]})
}

class DefaultStandaloneRealTunerConfig extends Config(TunerConfigBuilder.standalone("tuner", TunerConfig(), () => DspReal()))
class DefaultStandaloneFixedPointTunerConfig extends Config(TunerConfigBuilder.standalone("tuner", TunerConfig(), () => FixedPoint(32.W, 16.BP)))

case class TunerKey(id: String) extends Field[TunerConfig]

trait HasTunerGenParameters[T <: Data] extends HasGenParameters[T, T] {
	val genMult: Option[T] = None
}

/**
  * Case class for holding Tuner configuration information
  * @param lanes Number of parallel input and output lanes
  */
case class TunerConfig(lanes:Int = 8)
