package chipyard.iobinders

import chisel3._
import chisel3.reflect.DataMirror
import chisel3.experimental.Analog

import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.devices.debug._
import freechips.rocketchip.jtag.{JTAGIO}
import freechips.rocketchip.subsystem._
import freechips.rocketchip.system.{SimAXIMem}
import freechips.rocketchip.amba.axi4.{AXI4Bundle, AXI4SlaveNode, AXI4MasterNode, AXI4EdgeParameters}
import freechips.rocketchip.util._
import freechips.rocketchip.prci._
import freechips.rocketchip.groundtest.{GroundTestSubsystemModuleImp, GroundTestSubsystem}
import freechips.rocketchip.tilelink.{TLBundle}

import sifive.blocks.devices.gpio._
import sifive.blocks.devices.uart._
import sifive.blocks.devices.spi._
import sifive.blocks.devices.i2c._
import tracegen.{TraceGenSystemModuleImp}

import barstools.iocell.chisel._

import testchipip.serdes.{CanHavePeripheryTLSerial, SerialTLKey}
import testchipip.spi.{SPIChipIO}
import testchipip.boot.{CanHavePeripheryCustomBootPin}
import testchipip.soc.{CanHavePeripheryChipIdPin}
import testchipip.util.{ClockedIO}
import testchipip.iceblk.{CanHavePeripheryBlockDevice, BlockDeviceKey, BlockDeviceIO}
import testchipip.cosim.{CanHaveTraceIO, TraceOutputTop, SpikeCosimConfig}
import testchipip.tsi.{CanHavePeripheryUARTTSI, UARTTSIIO}
import icenet.{CanHavePeripheryIceNIC, SimNetwork, NicLoopback, NICKey, NICIOvonly}
import chipyard.{CanHaveMasterTLMemPort, HasCeaseSuccessIO, ChipyardSystem, ChipyardSystemModule}

import scala.reflect.{ClassTag}

object IOBinderTypes {
  type IOBinderTuple = (Seq[Port[_]], Seq[IOCell])
  type IOBinderFunction = (Boolean, => Any) => ModuleValue[IOBinderTuple]
}
import IOBinderTypes._

// System for instantiating binders based
// on the scala type of the Target (_not_ its IO). This avoids needing to
// duplicate harnesses (essentially test harnesses) for each target.

// IOBinders is map between string representations of traits to the desired
// IO connection behavior for tops matching that trait. We use strings to enable
// composition and overriding of IOBinders, much like how normal Keys in the config
// system are used/ At elaboration, the testharness traverses this set of functions,
// and functions which match the type of the DigitalTop are evaluated.

// You can add your own binder by adding a new (key, fn) pair, typically by using
// the OverrideIOBinder or ComposeIOBinder macros
case object IOBinders extends Field[Map[String, Seq[IOBinderFunction]]](
  Map[String, Seq[IOBinderFunction]]().withDefaultValue(Nil)
)

abstract trait HasIOBinders extends HasChipyardPorts { this: LazyModule =>
  val lazySystem: LazyModule
  private val iobinders = p(IOBinders)
  // Note: IOBinders cannot rely on the implicit clock/reset, as they may be called from the
  // context of a LazyRawModuleImp
  private val lzy = iobinders.map({ case (s,fns) => s -> fns.map(f => f(true, lazySystem)) })
  private val imp = iobinders.map({ case (s,fns) => s -> fns.map(f => f(false, lazySystem.module)) })

  private lazy val lzyFlattened: Map[String, IOBinderTuple] = lzy.map({
    case (s,ms) => s -> (ms.map(_._1).flatten, ms.map(_._2).flatten)
  })
  private lazy val impFlattened: Map[String, IOBinderTuple] = imp.map({
    case (s,ms) => s -> (ms.map(_._1).flatten, ms.map(_._2).flatten)
  })

  // A publicly accessible list of IO cells (useful for a floorplanning tool, for example)
  val iocells = InModuleBody { (lzyFlattened.values ++ impFlattened.values).unzip._2.flatten.toBuffer }

  // A mapping between stringified DigitalSystem traits and their corresponding ChipTop ports
  val portMap = InModuleBody { iobinders.keys.map(k => k -> (lzyFlattened(k)._1 ++ impFlattened(k)._1)).toMap }

  // A mapping between stringified DigitalSystem traits and their corresponding ChipTop iocells
  val iocellMap = InModuleBody { iobinders.keys.map(k => k -> (lzyFlattened(k)._2 ++ impFlattened(k)._2)).toMap }

  def ports = portMap.getWrappedValue.values.flatten.toSeq

  InModuleBody {
    println("IOCells generated by IOBinders:")
    for ((k, v) <- iocellMap) {
      if (!v.isEmpty) {
        val cells = v.map(_.getClass.getSimpleName).groupBy(identity).mapValues(_.size)

        println(s"  IOBinder for $k generated:")
        for ((t, c) <- cells) { println(s"    $c X $t") }
      }
    }
    println()
    val totals = iocells.map(_.getClass.getSimpleName).groupBy(identity).mapValues(_.size)
    println(s"  Total generated ${iocells.size} IOCells:")
    for ((t, c) <- totals) { println(s"    $c X $t") }
  }
}

// Note: The parameters instance is accessible only through LazyModule
// or LazyModuleImpLike. The self-type requirement in traits like
// CanHaveMasterAXI4MemPort is insufficient to make it accessible to the IOBinder
// As a result, IOBinders only work on Modules which inherit LazyModule or
// or LazyModuleImpLike
object GetSystemParameters {
  def apply(s: Any): Parameters = {
    s match {
      case s: LazyModule => s.p
      case s: LazyModuleImpLike => s.p
      case _ => throw new Exception(s"Trying to get Parameters from a system that is not LazyModule or LazyModuleImpLike")
    }
  }
}

class IOBinder[T](composer: Seq[IOBinderFunction] => Seq[IOBinderFunction])(implicit tag: ClassTag[T]) extends Config((site, here, up) => {
  case IOBinders => {
    val upMap = up(IOBinders)
    upMap + (tag.runtimeClass.toString -> composer(upMap(tag.runtimeClass.toString)))
  }
})

class ConcreteIOBinder[T](composes: Boolean, fn: T => IOBinderTuple)(implicit tag: ClassTag[T]) extends IOBinder[T](
  up => (if (composes) up else Nil) ++ Seq(((_, t) => { InModuleBody {
    t match {
      case system: T => fn(system)
      case _ => (Nil, Nil)
    }
  }}): IOBinderFunction)
)

class LazyIOBinder[T](composes: Boolean, fn: T => ModuleValue[IOBinderTuple])(implicit tag: ClassTag[T]) extends IOBinder[T](
  up => (if (composes) up else Nil) ++ Seq(((isLazy, t) => {
    val empty = new ModuleValue[IOBinderTuple] {
      def getWrappedValue: IOBinderTuple = (Nil, Nil)
    }
    if (isLazy) {
      t match {
        case system: T => fn(system)
        case _ => empty
      }
    } else {
      empty
    }
  }): IOBinderFunction)
)

// The "Override" binders override any previous IOBinders (lazy or concrete) defined on the same trait.
// The "Compose" binders do not override previously defined IOBinders on the same trait
// The default IOBinders evaluate only in the concrete "ModuleImp" phase of elaboration
// The "Lazy" IOBinders evaluate in the LazyModule phase, but can also generate hardware through InModuleBody

class OverrideIOBinder[T](fn: T => IOBinderTuple)(implicit tag: ClassTag[T]) extends ConcreteIOBinder[T](false, fn)
class ComposeIOBinder[T](fn: T => IOBinderTuple)(implicit tag: ClassTag[T]) extends ConcreteIOBinder[T](true, fn)

class OverrideLazyIOBinder[T](fn: T => ModuleValue[IOBinderTuple])(implicit tag: ClassTag[T]) extends LazyIOBinder[T](false, fn)
class ComposeLazyIOBinder[T](fn: T => ModuleValue[IOBinderTuple])(implicit tag: ClassTag[T]) extends LazyIOBinder[T](true, fn)


case object IOCellKey extends Field[IOCellTypeParams](GenericIOCellParams())


class WithGPIOCells extends OverrideIOBinder({
  (system: HasPeripheryGPIOModuleImp) => {
    val (ports2d, cells2d) = system.gpio.zipWithIndex.map({ case (gpio, i) =>
      gpio.pins.zipWithIndex.map({ case (pin, j) =>
        val g = IO(Analog(1.W)).suggestName(s"gpio_${i}_${j}")
        val iocell = system.p(IOCellKey).gpio().suggestName(s"iocell_gpio_${i}_${j}")
        iocell.io.o := pin.o.oval
        iocell.io.oe := pin.o.oe
        iocell.io.ie := pin.o.ie
        pin.i.ival := iocell.io.i
        iocell.io.pad <> g
        (GPIOPort(() => g, i, j), iocell)
      }).unzip
    }).unzip
    (ports2d.flatten, cells2d.flatten)
  }
})

class WithGPIOPunchthrough extends OverrideIOBinder({
  (system: HasPeripheryGPIOModuleImp) => {
    val ports = system.gpio.zipWithIndex.map { case (gpio, i) =>
      val io_gpio = IO(gpio.cloneType).suggestName(s"gpio_$i")
      io_gpio <> gpio
      GPIOPinsPort(() => io_gpio, i)
    }
    (ports, Nil)
  }
})

class WithI2CPunchthrough extends OverrideIOBinder({
  (system: HasPeripheryI2CModuleImp) => {
    val ports = system.i2c.zipWithIndex.map { case (i2c, i) =>
      val io_i2c = IO(i2c.cloneType).suggestName(s"i2c_$i")
      io_i2c <> i2c
      I2CPort(() => i2c)
    }
    (ports, Nil)
  }
})

// DOC include start: WithUARTIOCells
class WithUARTIOCells extends OverrideIOBinder({
  (system: HasPeripheryUARTModuleImp) => {
    val (ports: Seq[UARTPort], cells2d) = system.uart.zipWithIndex.map({ case (u, i) =>
      val (port, ios) = IOCell.generateIOFromSignal(u, s"uart_${i}", system.p(IOCellKey), abstractResetAsAsync = true)
      val where = PBUS // TODO fix
      val bus = system.outer.asInstanceOf[HasTileLinkLocations].locateTLBusWrapper(where)
      val freqMHz = bus.dtsFrequency.get / 1000000
      (UARTPort(() => port, i, freqMHz.toInt), ios)
    }).unzip
    (ports, cells2d.flatten)
  }
})
// DOC include end: WithUARTIOCells

class WithSPIIOPunchthrough extends OverrideLazyIOBinder({
  (system: HasPeripherySPI) => {
    // attach resource to 1st SPI
    if (system.tlSpiNodes.size > 0) ResourceBinding {
      Resource(new MMCDevice(system.tlSpiNodes.head.device, 1), "reg").bind(ResourceAddress(0))
    }
    InModuleBody {
      val spi = system.asInstanceOf[BaseSubsystem].module.asInstanceOf[HasPeripherySPIBundle].spi
      val ports = spi.zipWithIndex.map({ case (s, i) =>
        val io_spi = IO(s.cloneType).suggestName(s"spi_$i")
        io_spi <> s
        SPIPort(() => io_spi)
      })
      (ports, Nil)
    }
  }
})

class WithSPIFlashIOCells extends OverrideIOBinder({
  (system: HasPeripherySPIFlashModuleImp) => {
    val (ports: Seq[SPIFlashPort], cells2d) = system.qspi.zipWithIndex.map({ case (s, i) =>

      val name = s"spi_${i}"
      val port = IO(new SPIChipIO(s.c.csWidth)).suggestName(name)
      val iocellBase = s"iocell_${name}"

      // SCK and CS are unidirectional outputs
      val sckIOs = IOCell.generateFromSignal(s.sck, port.sck, Some(s"${iocellBase}_sck"), system.p(IOCellKey), IOCell.toAsyncReset)
      val csIOs = IOCell.generateFromSignal(s.cs, port.cs, Some(s"${iocellBase}_cs"), system.p(IOCellKey), IOCell.toAsyncReset)

      // DQ are bidirectional, so then need special treatment
      val dqIOs = s.dq.zip(port.dq).zipWithIndex.map { case ((pin, ana), j) =>
        val iocell = system.p(IOCellKey).gpio().suggestName(s"${iocellBase}_dq_${j}")
        iocell.io.o := pin.o
        iocell.io.oe := pin.oe
        iocell.io.ie := true.B
        pin.i := iocell.io.i
        iocell.io.pad <> ana
        iocell
      }

      (SPIFlashPort(() => port, system.p(PeripherySPIFlashKey)(i), i), dqIOs ++ csIOs ++ sckIOs)
    }).unzip
    (ports, cells2d.flatten)
  }
})

class WithExtInterruptIOCells extends OverrideIOBinder({
  (system: HasExtInterruptsModuleImp) => {
    if (system.outer.nExtInterrupts > 0) {
      val (port: UInt, cells) = IOCell.generateIOFromSignal(system.interrupts, "ext_interrupts", system.p(IOCellKey), abstractResetAsAsync = true)
      (Seq(ExtIntPort(() => port)), cells)
    } else {
      system.interrupts := DontCare // why do I have to drive this 0-wide wire???
      (Nil, Nil)
    }
  }
})

// Rocketchip's JTAGIO exposes the oe signal, which doesn't go off-chip
class JTAGChipIO extends Bundle {
  val TCK = Input(Clock())
  val TMS = Input(Bool())
  val TDI = Input(Bool())
  val TDO = Output(Bool())
}

// WARNING: Don't disable syncReset unless you are trying to
// get around bugs in RTL simulators
class WithDebugIOCells(syncReset: Boolean = true) extends OverrideLazyIOBinder({
  (system: HasPeripheryDebug) => {
    implicit val p = GetSystemParameters(system)
    val tlbus = system.asInstanceOf[BaseSubsystem].locateTLBusWrapper(p(ExportDebug).slaveWhere)
    val clockSinkNode = system.debugOpt.map(_ => ClockSinkNode(Seq(ClockSinkParameters())))
    clockSinkNode.map(_ := tlbus.fixedClockNode)
    def clockBundle = clockSinkNode.get.in.head._1


    InModuleBody { system.asInstanceOf[BaseSubsystem] match { case system: HasPeripheryDebug => {
      system.debug.map({ debug =>
        // We never use the PSDIO, so tie it off on-chip
        system.psd.psd.foreach { _ <> 0.U.asTypeOf(new PSDTestMode) }
        system.resetctrl.map { rcio => rcio.hartIsInReset.map { _ := clockBundle.reset.asBool } }
        system.debug.map { d =>
          // Tie off extTrigger
          d.extTrigger.foreach { t =>
            t.in.req := false.B
            t.out.ack := t.out.req
          }
          // Tie off disableDebug
          d.disableDebug.foreach { d => d := false.B }
          // Drive JTAG on-chip IOs
          d.systemjtag.map { j =>
            j.reset := (if (syncReset) ResetCatchAndSync(j.jtag.TCK, clockBundle.reset.asBool) else clockBundle.reset.asBool)
            j.mfr_id := p(JtagDTMKey).idcodeManufId.U(11.W)
            j.part_number := p(JtagDTMKey).idcodePartNum.U(16.W)
            j.version := p(JtagDTMKey).idcodeVersion.U(4.W)
          }
        }
        Debug.connectDebugClockAndReset(Some(debug), clockBundle.clock)

        // Add IOCells for the DMI/JTAG/APB ports
        val dmiTuple = debug.clockeddmi.map { d =>
          val (port, cells) = IOCell.generateIOFromSignal(d, "dmi", p(IOCellKey), abstractResetAsAsync = true)
          (DMIPort(() => port), cells)
        }

        val jtagTuple = debug.systemjtag.map { j =>
          val jtag_wire = Wire(new JTAGChipIO)
          j.jtag.TCK := jtag_wire.TCK
          j.jtag.TMS := jtag_wire.TMS
          j.jtag.TDI := jtag_wire.TDI
          jtag_wire.TDO := j.jtag.TDO.data
          val (port, cells) = IOCell.generateIOFromSignal(jtag_wire, "jtag", p(IOCellKey), abstractResetAsAsync = true)
          (JTAGPort(() => port), cells)
        }

        require(!debug.apb.isDefined)

        val allTuples = (dmiTuple ++ jtagTuple).toSeq
        (allTuples.map(_._1).toSeq, allTuples.flatMap(_._2).toSeq)
      }).getOrElse((Nil, Nil))
    }}}
  }
})

class WithSerialTLIOCells extends OverrideIOBinder({
  (system: CanHavePeripheryTLSerial) => {
    val (ports, cells) = system.serial_tls.zipWithIndex.map({ case (s, id) =>
      val sys = system.asInstanceOf[BaseSubsystem]
      val (port, cells) = IOCell.generateIOFromSignal(s.getWrappedValue, s"serial_tl_$id", sys.p(IOCellKey), abstractResetAsAsync = true)
      (SerialTLPort(() => port, sys.p(SerialTLKey)(id), system.serdessers(id), id), cells)
    }).unzip
    (ports.toSeq, cells.flatten.toSeq)
  }
})

class WithChipIdIOCells extends OverrideIOBinder({
  (system: CanHavePeripheryChipIdPin) => system.chip_id_pin.map({ p =>
    val sys = system.asInstanceOf[BaseSubsystem]
    val (port, cells) = IOCell.generateIOFromSignal(p.getWrappedValue, s"chip_id", sys.p(IOCellKey), abstractResetAsAsync = true)
    (Seq(ChipIdPort(() => port)), cells)
  }).getOrElse(Nil, Nil)
})

class WithSerialTLPunchthrough extends OverrideIOBinder({
  (system: CanHavePeripheryTLSerial) => {
    val (ports, cells) = system.serial_tls.zipWithIndex.map({ case (s, id) =>
      val sys = system.asInstanceOf[BaseSubsystem]
      val port = IO(chiselTypeOf(s.getWrappedValue))
      port <> s.getWrappedValue
      (SerialTLPort(() => port, sys.p(SerialTLKey)(id), system.serdessers(id), id), Nil)
    }).unzip
    (ports.toSeq, cells.flatten.toSeq)
  }
})

class WithAXI4MemPunchthrough extends OverrideLazyIOBinder({
  (system: CanHaveMasterAXI4MemPort) => {
    implicit val p: Parameters = GetSystemParameters(system)
    val clockSinkNode = p(ExtMem).map(_ => ClockSinkNode(Seq(ClockSinkParameters())))
    clockSinkNode.map(_ := system.asInstanceOf[HasTileLinkLocations].locateTLBusWrapper(MBUS).fixedClockNode)
    def clockBundle = clockSinkNode.get.in.head._1

    InModuleBody {
      val ports: Seq[AXI4MemPort] = system.mem_axi4.zipWithIndex.map({ case (m, i) =>
        val port = IO(new ClockedIO(DataMirror.internal.chiselTypeClone[AXI4Bundle](m))).suggestName(s"axi4_mem_${i}")
        port.bits <> m
        port.clock := clockBundle.clock
        AXI4MemPort(() => port, p(ExtMem).get, system.memAXI4Node.edges.in(i), p(MemoryBusKey).dtsFrequency.get.toInt)
      }).toSeq
      (ports, Nil)
    }
  }
})

class WithAXI4MMIOPunchthrough extends OverrideLazyIOBinder({
  (system: CanHaveMasterAXI4MMIOPort) => {
    implicit val p: Parameters = GetSystemParameters(system)
    val clockSinkNode = p(ExtBus).map(_ => ClockSinkNode(Seq(ClockSinkParameters())))
    clockSinkNode.map(_ := system.asInstanceOf[HasTileLinkLocations].locateTLBusWrapper(SBUS).fixedClockNode)
    def clockBundle = clockSinkNode.get.in.head._1

    InModuleBody {
      val ports: Seq[AXI4MMIOPort] = system.mmio_axi4.zipWithIndex.map({ case (m, i) =>
        val port = IO(new ClockedIO(DataMirror.internal.chiselTypeClone[AXI4Bundle](m))).suggestName(s"axi4_mmio_${i}")
        port.bits <> m
        port.clock := clockBundle.clock
        AXI4MMIOPort(() => port, p(ExtBus).get, system.mmioAXI4Node.edges.in(i))
      }).toSeq
      (ports, Nil)
    }
  }
})

class WithL2FBusAXI4Punchthrough extends OverrideLazyIOBinder({
  (system: CanHaveSlaveAXI4Port) => {
    implicit val p: Parameters = GetSystemParameters(system)
    val clockSinkNode = p(ExtIn).map(_ => ClockSinkNode(Seq(ClockSinkParameters())))
    clockSinkNode.map(_ := system.asInstanceOf[BaseSubsystem].fbus.fixedClockNode)
    def clockBundle = clockSinkNode.get.in.head._1

    InModuleBody {
      val ports: Seq[AXI4InPort] = system.l2_frontend_bus_axi4.zipWithIndex.map({ case (m, i) =>
        val port = IO(new ClockedIO(Flipped(DataMirror.internal.chiselTypeClone[AXI4Bundle](m)))).suggestName(s"axi4_fbus_${i}")
        m <> port.bits
        port.clock := clockBundle.clock
        AXI4InPort(() => port, p(ExtIn).get)
      }).toSeq
      (ports, Nil)
    }
  }
})

class WithBlockDeviceIOPunchthrough extends OverrideIOBinder({
  (system: CanHavePeripheryBlockDevice) => {
    val ports: Seq[BlockDevicePort] = system.bdev.map({ bdev =>
      val p = GetSystemParameters(system)
      val bdParams = p(BlockDeviceKey).get
      val port = IO(new ClockedIO(new BlockDeviceIO(bdParams))).suggestName("blockdev")
      port <> bdev
      BlockDevicePort(() => port, bdParams)
    }).toSeq
    (ports, Nil)
  }
})

class WithNICIOPunchthrough extends OverrideIOBinder({
  (system: CanHavePeripheryIceNIC) => {
    val ports: Seq[NICPort] = system.icenicOpt.map({ n =>
      val p = GetSystemParameters(system)
      val port = IO(new ClockedIO(new NICIOvonly)).suggestName("nic")
      port <> n
      NICPort(() => port, p(NICKey).get)
    }).toSeq
    (ports, Nil)
  }
})

class WithCeasePunchThrough extends OverrideIOBinder({
  (system: HasCeaseSuccessIO) => {
    val success: Bool = IO(Output(Bool())).suggestName("success")
    success := system.success.getWrappedValue
    (Seq(SuccessPort(() => success)), Nil)
  }
})

class WithTraceGenSuccessPunchthrough extends OverrideIOBinder({
  (system: TraceGenSystemModuleImp) => {
    val success: Bool = IO(Output(Bool())).suggestName("success")
    success := system.success
    (Seq(SuccessPort(() => success)), Nil)
  }
})

class WithTraceIOPunchthrough extends OverrideLazyIOBinder({
  (system: CanHaveTraceIO) => InModuleBody {
    val ports: Option[TracePort] = system.traceIO.map { t =>
      val trace = IO(DataMirror.internal.chiselTypeClone[TraceOutputTop](t)).suggestName("trace")
      trace <> t
      val p = GetSystemParameters(system)
      val chipyardSystem = system.asInstanceOf[ChipyardSystem]
      val tiles = chipyardSystem.totalTiles.values
      val cfg = SpikeCosimConfig(
        isa = tiles.headOption.map(_.isaDTS).getOrElse(""),
        vlen = tiles.headOption.map(_.tileParams.core.vLen).getOrElse(0),
        priv = tiles.headOption.map(t => if (t.usingUser) "MSU" else if (t.usingSupervisor) "MS" else "M").getOrElse(""),
        mem0_base = p(ExtMem).map(_.master.base).getOrElse(BigInt(0)),
        mem0_size = p(ExtMem).map(_.master.size).getOrElse(BigInt(0)),
        pmpregions = tiles.headOption.map(_.tileParams.core.nPMPs).getOrElse(0),
        nharts = tiles.size,
        bootrom = chipyardSystem.bootROM.map(_.module.contents.toArray.mkString(" ")).getOrElse(""),
        has_dtm = p(ExportDebug).protocols.contains(DMI) // assume that exposing clockeddmi means we will connect SimDTM
      )
      TracePort(() => trace, cfg)
    }
    (ports.toSeq, Nil)
  }
})

class WithCustomBootPin extends OverrideIOBinder({
  (system: CanHavePeripheryCustomBootPin) => system.custom_boot_pin.map({ p =>
    val sys = system.asInstanceOf[BaseSubsystem]
    val (port, cells) = IOCell.generateIOFromSignal(p.getWrappedValue, "custom_boot", sys.p(IOCellKey), abstractResetAsAsync = true)
    (Seq(CustomBootPort(() => port)), cells)
  }).getOrElse((Nil, Nil))
})

class WithUARTTSIPunchthrough extends OverrideIOBinder({
  (system: CanHavePeripheryUARTTSI) => system.uart_tsi.map({ p =>
    val sys = system.asInstanceOf[BaseSubsystem]
    val uart_tsi = IO(new UARTTSIIO(p.uartParams))
    uart_tsi <> p
    (Seq(UARTTSIPort(() => uart_tsi)), Nil)
  }).getOrElse((Nil, Nil))
})

class WithTLMemPunchthrough extends OverrideIOBinder({
  (system: CanHaveMasterTLMemPort) => {
    val io_tl_mem_pins_temp = IO(DataMirror.internal.chiselTypeClone[HeterogeneousBag[TLBundle]](system.mem_tl)).suggestName("tl_slave")
    io_tl_mem_pins_temp <> system.mem_tl
    (Seq(TLMemPort(() => io_tl_mem_pins_temp)), Nil)
  }
})


class WithDontTouchPorts extends OverrideIOBinder({
  (system: DontTouch) => system.dontTouchPorts(); (Nil, Nil)
})

class WithNMITiedOff extends ComposeIOBinder({
  (system: HasHierarchicalElementsRootContextModuleImp) => {
    system.nmi.foreach { nmi =>
      nmi.rnmi := false.B
      nmi.rnmi_interrupt_vector := 0.U
      nmi.rnmi_exception_vector := 0.U
    }
    (Nil, Nil)
  }
})
