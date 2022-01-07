package naxriscv.misc

import naxriscv.{Frontend, Global}
import naxriscv.Global._
import naxriscv.execute.{CsrAccessPlugin, EnvCallPlugin}
import naxriscv.fetch.{FetchPlugin, PcPlugin}
import naxriscv.frontend.FrontendPlugin
import naxriscv.interfaces.JumpService.Priorities
import naxriscv.interfaces.{CommitService, CsrRamFilter, DecoderService, PrivilegedService}
import naxriscv.riscv.CSR
import spinal.core._
import spinal.lib._
import naxriscv.utilities.Plugin
import spinal.lib.fsm._
import spinal.lib.pipeline.StageableOffset

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

object PrivilegedConfig{
  def full = PrivilegedConfig(
    withSupervisor = false,
    withUser       = false,
    withUserTrap   = false
  )
}

case class PrivilegedConfig(withSupervisor : Boolean,
                            withUser : Boolean,
                            withUserTrap : Boolean){

}



class PrivilegedPlugin(p : PrivilegedConfig) extends Plugin with PrivilegedService{
  override def hasMachinePriv = setup.machinePrivilege
  override def hasSupervisorPriv = setup.supervisorPrivilege

  override def implementSupervisor = p.withSupervisor
  override def implementUserTrap = p.withUserTrap

  val io = create early new Area{
    val int = new Area{
      val machine = new Area{
        val timer = in Bool()
        val software = in Bool()
        val external = in Bool()
      }
      val supervisor = p.withSupervisor generate new Area{
        val external = in Bool()
      }
      val user = p.withUserTrap generate new Area{
        val external = in Bool()
      }
    }
  }

  val setup = create early new Area{
    val csr = getService[CsrAccessPlugin]
    val ram = getService[CsrRamPlugin]
    val fetch = getService[FetchPlugin]
    val frontend = getService[FrontendPlugin]
    val rob = getService[RobPlugin]
    csr.retain()
    ram.retain()
    fetch.retain()
    rob.retain()
    frontend.retain()

    val jump = getService[PcPlugin].createJumpInterface(Priorities.COMMIT_TRAP)
    val ramRead  = ram.ramReadPort()
    val ramWrite = ram.ramWritePort()

    val machinePrivilege    = RegInit(True)
    val supervisorPrivilege = implementSupervisor generate RegInit(True)
  }

  val logic = create late new Area{
    val csr = setup.csr
    val ram = setup.ram
    val fetch = setup.fetch
    val rob = setup.rob
    val frontend = setup.frontend
    val decoder = getService[DecoderService]
    val commit = getService[CommitService]


    case class Delegator(var enable : Bool, privilege : Int)
    case class InterruptSpec(var cond : Bool, id : Int, privilege : Int, delegators : List[Delegator])
    case class ExceptionSpec(id : Int, delegators : List[Delegator])
    var interruptSpecs = ArrayBuffer[InterruptSpec]()
    var exceptionSpecs = ArrayBuffer[ExceptionSpec]()

    def addInterrupt(cond : Bool, id : Int, privilege : Int, delegators : List[Delegator]): Unit = {
      interruptSpecs += InterruptSpec(cond, id, privilege, delegators)
    }


    val machine = new Area {
      val tvec    = csr.readWriteRam(CSR.MTVEC)
      val tval    = csr.readWriteRam(CSR.MTVAL)
      val epc     = csr.readWriteRam(CSR.MEPC)
      val scratch = csr.readWriteRam(CSR.MSCRATCH)
      val cause = new Area{
        val interrupt = RegInit(False)
        val code = Reg(UInt(commit.rescheduleCauseWidth bits)) init(0)
      }
      val mstatus = new Area{
        val mie, mpie = RegInit(False)
        val mpp = RegInit(U"11")
      }
      val mip = new Area{
        val meip = RegNext(io.int.machine.external) init(False)
        val mtip = RegNext(io.int.machine.timer)    init(False)
        val msip = RegNext(io.int.machine.software) init(False)
      }
      val mie = new Area{
        val meie, mtie, msie = RegInit(False)
      }

      csr.readWrite(CSR.MCAUSE, XLEN-1 -> cause.interrupt, 0 -> cause.code)
      csr.readWrite(CSR.MSTATUS, 11 -> mstatus.mpp, 7 -> mstatus.mpie, 3 -> mstatus.mie)
      csr.read     (CSR.MIP, 11 -> mip.meip, 7 -> mip.mtip)
      csr.readWrite(CSR.MIP, 3 -> mip.msip)
      csr.readWrite(CSR.MIE, 11 -> mie.meie, 7 -> mie.mtie, 3 -> mie.msie)

      addInterrupt(mip.mtip && mie.mtie, id = 7,  privilege = 3,  delegators = Nil)
      addInterrupt(mip.msip && mie.msie, id = 3,  privilege = 3,  delegators = Nil)
      addInterrupt(mip.meip && mie.meie, id = 11, privilege = 3, delegators = Nil)
    }

    val supervisor = p.withSupervisor generate new Area {
      val tvec    = csr.readWriteRam(CSR.STVEC)
      val tval    = csr.readWriteRam(CSR.STVAL)
      val epc     = csr.readWriteRam(CSR.SEPC)
      val scratch = csr.readWriteRam(CSR.SSCRATCH)
    }

    val userTrap = p.withUserTrap generate new Area {
      val tvec    = csr.readWriteRam(CSR.UTVEC)
      val tval    = csr.readWriteRam(CSR.UTVAL)
      val epc     = csr.readWriteRam(CSR.UEPC)
      val scratch = csr.readWriteRam(CSR.USCRATCH)
    }

    val privilege = UInt(2 bits)
    privilege(1) := setup.machinePrivilege
    privilege(0) := (if(p.withSupervisor) setup.supervisorPrivilege else setup.machinePrivilege)
    privilege.freeze()

    csr.release()
    ram.release()



    //Process interrupt request, code and privilege
    val interrupt = new Area {
      val valid = False
      val code = UInt(commit.rescheduleCauseWidth bits).assignDontCare()
      val targetPrivilege = UInt(2 bits).assignDontCare()

      val privilegeAllowInterrupts = mutable.LinkedHashMap[Int, Bool]()
      var privilegs: List[Int] = Nil

      privilegs = List(3)
      privilegeAllowInterrupts += 3 -> (machine.mstatus.mie || !setup.machinePrivilege)

      if (p.withSupervisor) {
        privilegs = 1 :: privilegs
        ??? // privilegeAllowInterrupts += 1 -> ((sstatus.SIE && !setup.machinePrivilege) || !setup.supervisorPrivilege)
      }

      if (p.withUserTrap) {
        privilegs = 0 :: privilegs
        ??? // privilegeAllowInterrupts += 1 -> ((ustatus.UIE && !setup.supervisorPrivilege))
      }

      while (privilegs.nonEmpty) {
        val p = privilegs.head
        when(privilegeAllowInterrupts(p)) {
          for (i <- interruptSpecs
               if i.privilege <= p //EX : Machine timer interrupt can't go into supervisor mode
               if privilegs.tail.forall(e => i.delegators.exists(_.privilege == e))) { // EX : Supervisor timer need to have machine mode delegator
            val delegUpOn = i.delegators.filter(_.privilege > p).map(_.enable).fold(True)(_ && _)
            val delegDownOff = !i.delegators.filter(_.privilege <= p).map(_.enable).orR
            when(i.cond && delegUpOn && delegDownOff) {
              valid := True
              code := i.id
              targetPrivilege := p
            }
          }
        }
        privilegs = privilegs.tail
      }
    }






    val rescheduleUnbuffered = Stream(new Bundle{
      val cause      = UInt(commit.rescheduleCauseWidth bits)
      val epc        = PC()
      val tval       = Bits(Global.XLEN bits)
    })
    val reschedule = rescheduleUnbuffered.stage()

    val cr = commit.reschedulingPort()
    rescheduleUnbuffered.valid := cr.valid && cr.trap
    rescheduleUnbuffered.cause := cr.cause
    rescheduleUnbuffered.epc   := rob.readAsyncSingle(Global.PC, cr.robId)
    rescheduleUnbuffered.tval  := cr.tval

    val dt = decoder.getTrap()
    when(dt.valid) {
      rescheduleUnbuffered.valid := True
      rescheduleUnbuffered.cause := dt.cause
      rescheduleUnbuffered.epc   := dt.epc
      rescheduleUnbuffered.tval  := dt.tval
    }

    assert(!rescheduleUnbuffered.isStall)


    val targetMachine = True

    val readed = Reg(Bits(Global.XLEN bits))

    reschedule.ready := False
    setup.ramWrite.valid := False
    setup.ramWrite.address.assignDontCare()
    setup.ramWrite.data.assignDontCare()
    setup.ramRead.valid := False
    setup.ramRead.address.assignDontCare()
    setup.jump.valid := False
    setup.jump.pc.assignDontCare()

    val decoderTrap = new Area{
      val raised = RegInit(False)
      val pendingInterrupt = RegNext(interrupt.valid) init(False)
      val counter = Reg(UInt(3 bits)) init(0) //Implement a little delay to ensure propagation of everthing in the calculation of the interrupt
      val doIt = counter.msb

      when(pendingInterrupt){
        decoder.trapHalt()
        counter := counter + 1
      }
      when(!pendingInterrupt || !decoder.trapReady() || raised){
        counter := 0
      }

      when(doIt && !raised){
        decoder.trapRaise()
        raised := True
      }

      val buffer = new Area{
        val sample          = interrupt.valid && !raised
        val code            = RegNextWhen(interrupt.code, sample)
        val targetPrivilege = RegNextWhen(interrupt.targetPrivilege, sample)
      }
    }


    val trapContext = new Area{
      val fire      = False
      val code      = decoderTrap.raised ? decoderTrap.buffer.code | reschedule.cause
      val interrupt = decoderTrap.raised
    }

    val fsm = new StateMachine{
      val IDLE, SETUP, EPC_WRITE, TVAL_WRITE, EPC_READ, TVEC_READ, XRET = new State()
      setEntry(IDLE)

      val cause = Reg(UInt(commit.rescheduleCauseWidth bits))
      val interrupt = Reg(Bool())

      IDLE.onEntry{
        decoderTrap.raised := False
      }
      IDLE.whenIsActive{
        reschedule.ready := True
        when(rescheduleUnbuffered.valid){
          goto(SETUP)
        }
      }
      SETUP.whenIsActive{
        when(decoderTrap.raised){
          goto(TVEC_READ)
        } otherwise {
          when(reschedule.cause === EnvCallPlugin.CAUSE_XRET) {
            goto(EPC_READ)
          } otherwise {
            goto(TVEC_READ)
          }
        }
      }
      EPC_READ.whenIsActive{
        setup.ramRead.valid   := True
        setup.ramRead.address := machine.epc.getAddress()
        readed := setup.ramRead.data
        when(setup.ramRead.ready){
          goto(XRET)
        }
      }
      TVEC_READ.whenIsActive{
        setup.ramRead.valid   := True
        setup.ramRead.address := machine.tvec.getAddress()
        readed := setup.ramRead.data
        when(setup.ramRead.ready){
          goto(TVAL_WRITE)
        }
      }
      TVAL_WRITE.whenIsActive{
        setup.ramWrite.valid   := True
        setup.ramWrite.address := machine.tval.getAddress()
        setup.ramWrite.data    := reschedule.tval
        when(decoderTrap.raised){ setup.ramWrite.data    := 0 }
        when(setup.ramWrite.ready){
          goto(EPC_WRITE)
        }
      }
      EPC_WRITE.whenIsActive{
        setup.ramWrite.valid   := True
        setup.ramWrite.address := machine.epc.getAddress()
        setup.ramWrite.data    := B(reschedule.epc)
        setup.jump.pc := U(readed) //TODO mask
        when(setup.ramWrite.ready){
          setup.jump.valid := True
          trapContext.fire := True

          machine.mstatus.mie  := False
          machine.mstatus.mpie := machine.mstatus.mie
          machine.mstatus.mpp  := privilege

//TODO
//          if(privilegeGen) privilegeReg := targetPrivilege
//
//          switch(targetPrivilege){
//            if(supervisorGen) is(1) {
//              sstatus.SIE := False
//              sstatus.SPIE := sstatus.SIE
//              sstatus.SPP := privilege(0 downto 0)
//              scause.interrupt := !hadexception
//              scause.exceptionCode := trapCause
//              sepc := mepcCaptureStage.input(PC)
//              if (exceptionPortCtrl != null) when(hadException){
//                stval := exceptionPortCtrl.exceptionContext.badAddr
//              }
//            }
//
//            is(3){
//              mstatus.MIE  := False
//              mstatus.MPIE := mstatus.MIE
//              mstatus.MPP  := privilege
//              mcause.interrupt := !hadException
//              mcause.exceptionCode := trapCause
//              mepc := mepcCaptureStage.input(PC)
//              if(exceptionportctrl != null) when(hadexception){
//                mtval := exceptionPortCtrl.exceptionContext.badAddr
//              }
//            }

          machine.cause.interrupt := trapContext.interrupt
          machine.cause.code      := trapContext.code

          goto(IDLE)
        }
      }
      XRET.whenIsActive{
        setup.jump.valid := True
        setup.jump.pc    := U(readed)
//TODO
//        mstatus.MPP := U"00"
//        mstatus.MIE := mstatus.MPIE
//        mstatus.MPIE := True
//        jumpInterface.payload := mepc
//        if(privilegeGen) privilegeReg := mstatus.MPP
//TODO
//        sstatus.SPP := U"0"
//        sstatus.SIE := sstatus.SPIE
//        sstatus.SPIE := True
//        jumpInterface.payload := sepc
//        if(privilegeGen) privilegeReg := U"0" @@ sstatus.SPP
        goto(IDLE)
      }
      fetch.getStage(0).haltIt(rescheduleUnbuffered.valid || !isActive(IDLE))
    }


    val whitebox = new AreaRoot{
      val trap = new Area{
        val fire      = Verilator.public(CombInit(trapContext.fire     ))
        val code      = Verilator.public(CombInit(trapContext.code     ))
        val interrupt = Verilator.public(CombInit(trapContext.interrupt))
      }
    }

    frontend.release()
    fetch.release()
    rob.release()
  }
}
//TODO access privilege checks