package tuner

import breeze.math.{Complex}
import breeze.signal.{fourierTr}
import breeze.linalg._
import chisel3._
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
import tuner.Generator.params

import dsptools._
import scala.collection.mutable.Map

trait HasIPXACTParameters {
  def getIPXACTParameters: Map[String, String]
}

case object PipelineDepth extends Field[Int]
case object TotalWidth extends Field[Int]
case object FractionalBits extends Field[Int]

// create a new DSP Configuration
class DspConfig extends Config(
  (pname, site, here) => pname match {
    case BuildDSP => q: Parameters => 
      implicit val p = q
      new LazyTunerBlock[DspReal]
    case TunerKey => q: Parameters =>  
      implicit val p = q
      TunerConfig[DspReal](pipelineDepth = site(PipelineDepth))
    case DspBlockId => "Tuner"
	  case NastiKey => NastiParameters(64, 32, 1)
    case TotalWidth => 30
    case FractionalBits => 24
    case PipelineDepth => 0
    case PAddrBits => 32
    case BaseAddr => 0
    case CacheBlockOffsetBits => 6
    case AmoAluOperandBits => 64
    case TLId => "Tuner"
    case TLKey("Tuner") =>
      TileLinkParameters(
        coherencePolicy = new MEICoherence(
          new NullRepresentation(2)),
        nManagers = 1,
        nCachingClients = 0,
        nCachelessClients = 1,
        maxClientXacts = 4,
        maxClientsPerPort = 1,
        maxManagerXacts = 1,
        dataBeats = 8,
        dataBits = 64 * 8)
    case DspBlockKey("Tuner") => DspBlockParameters(128, 128)
    case GenKey("Tuner") => new GenParameters {
      //def getReal(): FixedPoint = FixedPoint(width=site(TotalWidth), binaryPoint=site(FractionalBits)) 
      def getReal(): DspReal = DspReal()//DspReal(0.0).cloneType
      def genIn [T <: Data] = getReal().asInstanceOf[T]
      def lanesIn = 2
    }
    case _ => throw new CDEMatchError
  }) with HasIPXACTParameters {
  def getIPXACTParameters: Map[String, String] = {
    val parameterMap = Map[String, String]()

    // Conjure up some IPXACT synthsized parameters.
    val gk = params(GenKey(params(DspBlockId)))
    parameterMap ++= List(("InputLanes", gk.lanesIn.toString),
      ("InputTotalBits", params(TotalWidth).toString), ("OutputLanes", gk.lanesOut.toString), ("OutputTotalBits", params(TotalWidth).toString),
      ("OutputPartialBitReversed", "1"), ("PipelineDepth", params(PipelineDepth).toString))

    // add fractional bits if it's fixed point
    // TODO: check if it's fixed point or not
    parameterMap ++= List(("InputFractionalBits", params(FractionalBits).toString), 
      ("OutputFractionalBits", params(FractionalBits).toString))

    // tech stuff, TODO
    parameterMap ++= List(("ClockRate", "100"), ("Technology", "TSMC16nm"))

    parameterMap
  }
}

case object TunerKey extends Field[(Parameters) => TunerConfig[DspReal]]

trait HasTunerGenParameters[T <: Data] extends HasGenParameters[T, T] {
   def genMult: Option[T] = None
}

case class TunerConfig[T<:Data:Real](val pipelineDepth: Int)(implicit val p: Parameters) extends HasTunerGenParameters[T] {
  // sanity checks
  require(lanesIn == lanesOut, "Tuner must have an equal number of input and output lanes.")
  require(pipelineDepth >= 0, "Must have positive pipelining")
}

