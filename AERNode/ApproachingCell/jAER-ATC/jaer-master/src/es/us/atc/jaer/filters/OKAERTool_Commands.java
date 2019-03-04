/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package es.us.atc.jaer.filters;

import es.us.atc.jaer.hardwareinterface.OpalKellyFX2Monitor;
import es.us.atc.jaer.hardwareinterface.OpalKellyFX2MonitorFactory;
import net.sf.jaer.Description;
import net.sf.jaer.chip.AEChip;
import net.sf.jaer.event.EventPacket;
import net.sf.jaer.eventprocessing.EventFilter2D;
import net.sf.jaer.hardwareinterface.HardwareInterfaceException;

/**
 *
 * @author Rios
 */
@Description("This filter sends command to OKAERTOOL when it has been selected the Opalkelly Interface.")
public class OKAERTool_Commands extends EventFilter2D
{
    private boolean mergerIn = getBoolean("mergerIn", false);
    private boolean nodeBoardOut = getBoolean("nodeBoardOut", false);
    
    private boolean mergerOut = getBoolean("mergerOut", false);
    private boolean sequencerOut = getBoolean("sequencerOut", false);
        
    private OpalKellyFX2Monitor OKHardwareInterface;
            
    public OKAERTool_Commands(AEChip chip) 
    {
        super(chip);
        // properties, tips and groups
        final String input = "Input", output = "Output";
        
        setPropertyTooltip(input,"mergerIn", "Select the Merger output as general system input");
        setPropertyTooltip(input,"nodeBoardOut", "Select the Node Board output as general system input");
        
        setPropertyTooltip(output,"mergerOut", "Select the Merger output as general system output");
        setPropertyTooltip(output,"sequencerOut", "Select the Sequencer output as general system output");
                
        try
        {
            OKHardwareInterface = (OpalKellyFX2Monitor)OpalKellyFX2MonitorFactory.instance().getFirstAvailableInterface();
        }
        catch (HardwareInterfaceException ex) 
        {
            log.warning(ex.toString());
        }
        
        //Uncheck inputs and outputs
        setMergerIn(false);
        setNodeBoardOut(false);
        setMergerOut(false);
        setSequencerOut(false);
        
        /*setPropertyTooltip(merger,"merger", "Merger command");
        
        setPropertyTooltip(monitor,"monitor", "Monitor command");
        setPropertyTooltip(monitor,"stopMonitor", "Stop_Monitor command");
        
        setPropertyTooltip(logger,"log", "Log command");
        setPropertyTooltip(logger,"stopLog", "Stop_Log command");
        setPropertyTooltip(logger,"download", "Download command");
        
        setPropertyTooltip(sequencer,"loadData", "Load Data command");
        setPropertyTooltip(sequencer,"sequence", "Sequence Data command");
        setPropertyTooltip(sequencer,"stopSequence", "Stop Sequence command");*/
        
    }

    @Override
    public EventPacket<?> filterPacket(EventPacket<?> in) 
    {
        //throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
        return in;
    }

    @Override
    public void resetFilter() 
    {
        /*this.setMergerIn(false);
        this.setNodeBoardOut(false);
        this.setMergerOut(false);
        his.setSequencerOut(false);*/
        
        OKHardwareInterface.resetOKTOOL();
    }

    @Override
    public void initFilter() 
    {
        //TODO        
    }
    
    public void do0_Merge() 
    {
        int command, mask;
        
        //Select the input
        this.setMergerIn(false);
        this.setNodeBoardOut(false);
        command = 0x0000;
        mask = 0x0003;
        OKHardwareInterface.sendOKCommand(command, mask);
        
        //Select the output
        this.setMergerOut(true);
        command = 0x0004;
        mask = 0x000C;
        OKHardwareInterface.sendOKCommand(command, mask);
        
        //Send command
        command = 0x0000;
        mask = 0xFFF0;
        OKHardwareInterface.sendOKCommand(command, mask);
    }
    
    public void do1_Monitor() 
    {
        //If any input have been selected, select Merger input.
        int command = 0x0410;
        int mask = 0x0610;
        
        if(!isMergerIn() && !isNodeBoardOut())
        {
            setMergerIn(true);
        }
        
        OKHardwareInterface.sendOKCommand(command, mask);
        try
        {
            OKHardwareInterface.setEventAcquisitionEnabled(true);
        }
        catch (HardwareInterfaceException ex) 
        {
            log.warning(ex.toString());
        }
    }
    
    public void do2_Stop_Monitor() 
    {
        int command = 0x0000;
        int mask = 0x0010;
        OKHardwareInterface.sendOKCommand(command, mask);
        
        try
        {
            OKHardwareInterface.setEventAcquisitionEnabled(false);
        }
        catch (HardwareInterfaceException ex) 
        {
            log.warning(ex.toString());
        }
    }
    
    public void do3_Log() 
    {
        this.setMergerOut(false);
        this.setSequencerOut(false);
        
        int command = 0x0220;
        int mask = 0x0660;
        OKHardwareInterface.sendOKCommand(command, mask);
    }
    
    public void do4_Stop_Log() 
    {
        int command = 0x0240;
        int mask = 0x0660;
        OKHardwareInterface.sendOKCommand(command, mask);
        
        try
        {
            OKHardwareInterface.setSaveDataEventEnabled(false);
        }
        catch(HardwareInterfaceException ex)
        {
            log.warning(ex.toString());
        }
    }
    
    public void do5_Download() throws HardwareInterfaceException 
    {
        OKHardwareInterface.openFiletoSave_Data();
        
        int command = 0x0260;
        int mask = 0x0660;
        OKHardwareInterface.sendOKCommand(command, mask);
        
        try
        {
            OKHardwareInterface.setSaveDataEventEnabled(true);
        }
        catch(HardwareInterfaceException ex)
        {
            log.warning(ex.toString());
        }
    }
    
    public void do6_Load_Data() throws HardwareInterfaceException 
    {
        OKHardwareInterface.openFileToLoad_Data();
        
        setMergerIn(false);
        setNodeBoardOut(false);
        
        setSequencerOut(false);
        setMergerOut(false);
        
        int command = 0x0680;
        int mask = 0x0780;
        OKHardwareInterface.sendOKCommand(command, mask);
        
        try
        {
            OKHardwareInterface.setLoadDataEventEnabled(true);
        }
        catch(HardwareInterfaceException ex)
        {
            log.warning(ex.toString());
        }
    }
    
    public void do7_Sequence() 
    {
        //setMergerIn(false);
        //setNodeBoardOut(false);
        
        setSequencerOut(true);
        
        int command = 0x0700;
        int mask = 0x0780;
        OKHardwareInterface.sendOKCommand(command, mask);
    }
    
    public void do8_Stop_Sequence() 
    {
        int command = 0x0780;
        int mask = 0x0780;
        OKHardwareInterface.sendOKCommand(command, mask);
        
        try
        {
            OKHardwareInterface.setLoadDataEventEnabled(false);
        }
        catch(HardwareInterfaceException ex)
        {
            log.warning(ex.toString());
        }
    }
    
    public void do9_STOP() 
    {
        setMergerIn(false);
        setNodeBoardOut(false);
        setMergerOut(false);
        setSequencerOut(false);
        
        int command = 0x0000;
        int mask = 0xFFF0;
        OKHardwareInterface.sendOKCommand(command, mask);
    }
        
    public boolean isMergerIn() 
    {
        return mergerIn;
    }

    public void setMergerIn(boolean mergerIn) 
    {
        putBoolean("mergerIn", mergerIn);
        boolean oldValue = this.mergerIn;
        this.mergerIn = mergerIn;
        support.firePropertyChange("mergerIn", oldValue, mergerIn);
        
        if(isNodeBoardOut() && isMergerIn())
        {
            setNodeBoardOut(false);
        }
        
        selectInput();
    }

    public boolean isNodeBoardOut() 
    {
        return nodeBoardOut;
    }

    public void setNodeBoardOut(boolean nodeBoardOut) 
    {
        putBoolean("nodeBoardOut", nodeBoardOut);
        boolean oldValue = this.nodeBoardOut;
        this.nodeBoardOut = nodeBoardOut;
        support.firePropertyChange("nodeBoardOut", oldValue, nodeBoardOut);
        
        if(this.isMergerIn() && isNodeBoardOut())
        {
            this.setMergerIn(false);
        }
        
        this.selectInput();
    }
    
    private void selectInput()
    {
        int command, mask;
        
        if(isMergerIn())
        {
            command = 0x0001;
        }
        else if(isNodeBoardOut())
        {
            command = 0x0002;
        }
        else
        {
            command = 0x0000;
        }
        
        mask = 0x0003;
        OKHardwareInterface.sendOKCommand(command, mask);
    }
    
    public boolean isMergerOut() 
    {
        return mergerOut;
    }

    public void setMergerOut(boolean mergerOut) 
    {
        putBoolean("mergerOut", mergerOut);
        boolean oldValue = this.mergerOut;
        this.mergerOut = mergerOut;
        support.firePropertyChange("mergerOut", oldValue, mergerOut);
        
        if(isSequencerOut() && isMergerOut())
        {
            setSequencerOut(false);
        }
        
        selectOutput();
    }

    public boolean isSequencerOut() 
    {
        return sequencerOut;
    }

    public void setSequencerOut(boolean sequencerOut) 
    {
        putBoolean("sequencerOut", sequencerOut);
        boolean oldValue = this.sequencerOut;
        this.sequencerOut = sequencerOut;
        support.firePropertyChange("sequencerOut", oldValue, sequencerOut);
        
        if(isMergerOut() && isSequencerOut())
        {
            setMergerOut(false);
        }
        
        selectOutput();
    }
    
    private void selectOutput()
    {
        int command, mask;
        
        if(isMergerOut())
        {
            command = 0x0004;
        }
        else if(isSequencerOut())
        {
            command = 0x0008;
        }
        else
        {
            command = 0x0000;
        }
        
        mask = 0x000C;
        OKHardwareInterface.sendOKCommand(command, mask);
    }

    /*public void sendSpi(byte add, byte dat) 
    {
        int word_spi = (add<<8) + dat;
        //if (this.spi) 
        //Send 16bits data word by SPI using a bitmask
        OKHardwareInterface.sendOKSPIData(word_spi, 0xFFFF);
    }*/
}
