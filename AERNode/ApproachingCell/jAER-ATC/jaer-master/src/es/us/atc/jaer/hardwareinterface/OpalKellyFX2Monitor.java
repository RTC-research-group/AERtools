/*
 * Created in ATC, University of Seville. BIOSENSE project 2015
 */
package es.us.atc.jaer.hardwareinterface;

import com.opalkelly.frontpanel.okFrontPanel;
import com.opalkelly.frontpanel.okTDeviceInfo;
import com.opalkelly.frontpanel.okTDeviceMatchInfo;
import de.thesycon.usbio.PnPNotifyInterface;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeSupport;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Date;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.prefs.Preferences;
import javax.swing.JFileChooser;
import net.sf.jaer.aemonitor.AEListener;
import net.sf.jaer.aemonitor.AEMonitorInterface;
import net.sf.jaer.aemonitor.AEPacketRaw;
import net.sf.jaer.aemonitor.AEPacketRawPool;
import net.sf.jaer.aemonitor.EventRaw;
import net.sf.jaer.chip.AEChip;
import net.sf.jaer.eventio.AEDataFile;
import net.sf.jaer.eventio.AEFileInputStream;
import net.sf.jaer.eventio.AEFileOutputStream;
import net.sf.jaer.eventprocessing.FilterChain;
import net.sf.jaer.graphics.ChipDataFilePreview;
import net.sf.jaer.hardwareinterface.HardwareInterfaceException;
import net.sf.jaer.hardwareinterface.usb.ReaderBufferControl;
import net.sf.jaer.hardwareinterface.usb.USBInterface;
import net.sf.jaer.util.DATFileFilter;

/**
 *
 * @author arios (Antonio Rios-Navarro arios)
 * @description This hardware interface is used for manage Opal Kelly platform which can monitor, logger and sequence events.
 * Monitor is used for get events from AER bus and send them directly through USB port to computer.
 * Logger function stores the events from AER bus on DRR2 on board memory. Then this events can be sequenced or downloaded to the computer.
 * Sequence function is used to play the events stored on DRR2 on board memory through the AER output port.
 * TODO --> describe the protocol!!!!
 */
public class OpalKellyFX2Monitor implements AEMonitorInterface, PnPNotifyInterface, ReaderBufferControl{

    private static final Logger log = Logger.getLogger("Opal Kelly");
    
    private okFrontPanel opalkelly; //static
    
    /** The pool of raw AE packets, used for data transfer */
    private AEPacketRawPool aePacketRawPool;
    private AEPacketRaw lastEventsAcquired;
    private int eventCounter;
    
    public PropertyChangeSupport support;
    public final PropertyChangeEvent NEW_EVENTS_PROPERTY_CHANGE;
    
    private boolean eventAcquisitionEnabled;
    protected AEOpalKellyReader AEReader;
    
    private int estimatedEventRate;
    private long absolute_timestamp;
    
    private boolean overrunOccuredFlag;
    
    private final int TICK_US = 1;
    public final double TICK_US_CLK_FPGA = 0.01;
    
    private final int MAX_CAPACITY = 1024*1024;
    
    private final int commadOKWire = 0x00;
    private final int spiOKWire = 0x01;
    private final int nssOKWire = 0x02;
    
    private AEChip chip;
    
    private static Preferences prefs = Preferences.userNodeForPackage(OpalKellyFX2Monitor.class);
    private int aeBufferSize;
    private int usbBlockSize;
    //private int okBufferSize;
    public static final int AE_BUFFER_SIZE = 1048576; // should handle 5Meps at 30FPS
    
    private boolean isOpenedDevice;
    
    public final int epAddress = 0xA0; //This is the VHDL endpoint address
    
    File lastFile = null;
    AEPacketRaw seqPkt = null;
    protected AEOpalKellyLoad_Data AELoad_Data;
    //private boolean loadDataEventEnabled;
    
    AEFileOutputStream loggingOutputStream;
    protected AEOpalKellySave_Data AESave_Data;
    
    public OpalKellyFX2Monitor()
    {
        System.loadLibrary("okjFrontPanel");
        
        this.opalkelly = new okFrontPanel();
        this.NEW_EVENTS_PROPERTY_CHANGE = new PropertyChangeEvent(this, "NewEvents", null, null);
        this.eventAcquisitionEnabled = false;
        this.AEReader = null;
        this.estimatedEventRate = 0;
        this.absolute_timestamp = 0;
        this.aeBufferSize = prefs.getInt("OpalKelly.aeBufferSize", AE_BUFFER_SIZE);
        this.usbBlockSize = 1024;
        this.lastEventsAcquired = new AEPacketRaw();
        this.support = new PropertyChangeSupport(this);
        this.isOpenedDevice =false;
    }
        
    @Override
    public AEPacketRaw acquireAvailableEventsFromDriver() throws HardwareInterfaceException
    {
        /*if (!isEventAcquisitionEnabled()) 
        {
            setEventAcquisitionEnabled(true);
        }*/
        
        if (isEventAcquisitionEnabled()) 
        {
            this.overrunOccuredFlag = false;
            // get the 'active' buffer for events (the one that has just been written by the hardware thread)
            synchronized (aePacketRawPool) 
            { // synchronize on aeReader so that we don't try to access the events at the same time
                aePacketRawPool.swap();
                this.lastEventsAcquired = aePacketRawPool.readBuffer();
            }
            int nEvents = lastEventsAcquired.getNumEvents();
            this.eventCounter = 0;
            computeEstimatedEventRate(lastEventsAcquired);

            if (nEvents != 0) 
            {
                support.firePropertyChange(NEW_EVENTS_PROPERTY_CHANGE); // call listeners  
            }
        }
        return this.lastEventsAcquired;
    }

    void computeEstimatedEventRate(AEPacketRaw events) 
    {
        if (events == null || events.getNumEvents() < 2) 
        {
            this.estimatedEventRate = 0;
        } else {
            int[] ts = events.getTimestamps();
            int n = events.getNumEvents();
            int dt = ts[n - 1] - ts[0];
            this.estimatedEventRate = (int) (1e6f * (float) n / (float) dt);
        }
    }
    
    @Override
    public int getNumEventsAcquired() 
    {
        return this.lastEventsAcquired.getNumEvents();
    }

    @Override
    public AEPacketRaw getEvents() 
    {
        return this.lastEventsAcquired;
    }

    @Override
    public void resetTimestamps() 
    {
        synchronized (opalkelly)
        {
            //Activate trigger to reset hardware timestamp
            //Reset timestamp is implemented with a trigger vhdl module
            okFrontPanel.ErrorCode status = opalkelly.ActivateTriggerIn(0x40, 0);
            if(status != okFrontPanel.ErrorCode.NoError)
            {
                log.warning(status.toString());
            }
            else
            {
                this.absolute_timestamp = 0;
            }
        }
    }

    @Override
    public boolean overrunOccurred() 
    {
        return this.overrunOccuredFlag;
    }

    @Override
    public int getAEBufferSize() 
    {
        return this.aeBufferSize;
    }

    @Override
    public void setAEBufferSize(int AEBufferSize) 
    {
        if (AEBufferSize < 1024 || AEBufferSize > AE_BUFFER_SIZE) {
            log.warning("ignoring unreasonable aeBufferSize of " + AEBufferSize + ", choose a more reasonable size between 1024 and " + Integer.toString(AE_BUFFER_SIZE));
            return;
        }
        else if(AEBufferSize < this.usbBlockSize)
        {
            log.warning("ignoring unreasonable aeBufferSize of " + AEBufferSize + ", aeBufferSize cannot be smaller than usbBlokSize = " + this.usbBlockSize);
            return;
        }
        this.aeBufferSize = AEBufferSize;
        prefs.putInt("OpalKelly.aeBufferSize", this.aeBufferSize);
        allocateAEBuffers();
    }

    /** Allocates internal memory for transferring data from reader to consumer, e.g. rendering. */
    protected void allocateAEBuffers() 
    {
        synchronized (aePacketRawPool) 
        {
            aePacketRawPool.allocateMemory();
        }
    }
    
    @Override
    public void setEventAcquisitionEnabled(boolean enable) throws HardwareInterfaceException 
    {
        if (enable) 
        {
            startAEReader();
        } else 
        {
            stopAEReader();
        }
    }

    public void startAEReader() 
    {
        //setAeReader(new AEOpalKellyReader());
        this.AEReader = new AEOpalKellyReader();
        log.info("Start AE reader...");
        //getAeReader().start();
        this.AEReader.start();
        this.eventAcquisitionEnabled = true;
    }
    
    public void stopAEReader() 
    {
        //if (getAeReader() != null) 
        if (this.AEReader != null) 
        {
            // close device
            //getAeReader().finish();
            this.AEReader.finish();
        }
    }
    
    @Override
    public boolean isEventAcquisitionEnabled() 
    {
        return this.eventAcquisitionEnabled;
    }

    @Override
    public void addAEListener(AEListener listener) 
    {
        support.addPropertyChangeListener(listener);
    }

    @Override
    public void removeAEListener(AEListener listener) 
    {
        support.removePropertyChangeListener(listener);
    }

    @Override
    public int getMaxCapacity() 
    {
        return MAX_CAPACITY;
    }

    @Override
    public int getEstimatedEventRate() 
    {
        return this.estimatedEventRate;
    }

    @Override
    public int getTimestampTickUs() 
    {
        return TICK_US;
    }

    @Override
    public void setChip(AEChip chip) 
    {
        this.chip = chip;
    }

    @Override
    public AEChip getChip() 
    {
        return this.chip;
    }

    @Override
    public String getTypeName() 
    { //TODO: Hay que comprobarlo
        okTDeviceMatchInfo okInfoMatcher = new okTDeviceMatchInfo();
        okTDeviceInfo okInfo = new okTDeviceInfo();
        if (okFrontPanel.ErrorCode.NoError == opalkelly.GetDeviceInfo(okInfo))
        {
            return okInfo.getProductName();
        }
        
        return okFrontPanel.GetBoardModelString(opalkelly.GetBoardModel());
    }

    @Override
    public void close() {
        try 
        {
            setEventAcquisitionEnabled(false);
        } catch (HardwareInterfaceException ex) 
        {
            log.warning(ex.toString());
        }
        //opalkelly.delete();
        opalkelly = null;
        //opalkelly = new okFrontPanel();
        //okFrontPanel.ErrorCode error = opalkelly.OpenBySerial("0000000000");
        this.isOpenedDevice = false;
    }

    @Override
    public void open() throws HardwareInterfaceException 
    {
        if(opalkelly.OpenBySerial("") != okFrontPanel.ErrorCode.NoError)
        {
            //while(opalkelly.OpenBySerial("0000000000") != okFrontPanel.ErrorCode.NoError);
            okFrontPanel.ErrorCode error = opalkelly.OpenBySerial("0000000000");
            //log.warning(error.toString());
        }
        //while(opalkelly.OpenBySerial("0000000000") != okFrontPanel.ErrorCode.NoError);
        
        /*if(!opalkelly.IsOpen())
        {
            okFrontPanel.ErrorCode error = opalkelly.OpenBySerial("");
            log.warning(error.toString());
        }
        else
        {
            okFrontPanel.ErrorCode error = opalkelly.OpenBySerial("0000000000");
        }*/
        
        /*//Select_input_CMD
        opalkelly.SetWireInValue(0x00, 0x0001, 0x0003);
        opalkelly.UpdateWireIns();
        //Select_output_CMD
        opalkelly.SetWireInValue(0x00, 0x0000, 0x000C);
        opalkelly.UpdateWireIns();
        //Monitor_CMD
        opalkelly.SetWireInValue(0x00, 0x0410, 0x0610);
        opalkelly.UpdateWireIns();
        
        try 
        {
            setEventAcquisitionEnabled(true);
        } catch (HardwareInterfaceException ex) 
        {
            log.warning(ex.toString());
        }*/
        this.isOpenedDevice = true;
        //addControlPanel();
    }

    /*private void addControlPanel() 
    {
        FilterChain filters = chip.getFilterChain();
        chip.getAeViewer().getFilterFrame().rebuildContents();
    }*/
    
    @Override
    public boolean isOpen() 
    {
        return this.isOpenedDevice;//opalkelly.IsOpen();
    }

    @Override
    public void onAdd() 
    {
        log.info("Opal Kelly device added");
    }

    @Override
    public void onRemove() 
    {
        log.info("Opal Kelly device removed");
    }

    @Override
    public int getFifoSize() 
    {
        return this.usbBlockSize;
    }

    @Override
    public void setFifoSize(int fifoSize) 
    {
        if(fifoSize < 0 || fifoSize > 1024) //1024??
        {
            log.warning("ignoring unreasonable usbBlockSize of " + fifoSize + ", choose a more reasonable size between 0 and 1024");
            return;
        }
        
        this.usbBlockSize = fifoSize;
        prefs.putInt("OpalKelly.usbBlockSize", this.usbBlockSize);
    }

    @Override
    public int getNumBuffers() 
    {
        return 1;//TODO
    }

    @Override
    public void setNumBuffers(int numBuffers) 
    {
        //TODO
        //throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    @Override
    public PropertyChangeSupport getReaderSupport() 
    {
        return this.support;
    }
    
    @Override
    public String toString()
    {
        return "OKAERTool";
    }
    
    public void sendOKCommand(int command, int mask)
    {
        synchronized (opalkelly)
        {
            okFrontPanel.ErrorCode status = opalkelly.SetWireInValue(commadOKWire, command, mask);
            opalkelly.UpdateWireIns();
            if(status != okFrontPanel.ErrorCode.NoError)
            {
                log.warning(status.toString());
            }
        }
    }
    
    public void sendOKSPIData(int data, int mask)
    {
        synchronized (opalkelly)
        {
            okFrontPanel.ErrorCode status = opalkelly.SetWireInValue(spiOKWire, data, mask);
            opalkelly.UpdateWireIns();
            if(status != okFrontPanel.ErrorCode.NoError)
            {
                log.warning(status.toString());
            }
            else
            {
                status = opalkelly.ActivateTriggerIn(0x41, 0);
                //opalkelly.UpdateTriggerOuts();
                if(status != okFrontPanel.ErrorCode.NoError)
                {
                    log.warning(status.toString());
                }
               // log.log(Level.INFO, "Sending command - add: {0}, dat: {1}", new Object[]{String.format("%02X", (data & 0xFF00) >> 8), String.format("%02X",(data & 0x00FF))});
            }
        }
    }
    
    public void setNSSsignal(boolean state)
    {
        synchronized (opalkelly)
        {
            if (state)
            {
                okFrontPanel.ErrorCode status = opalkelly.SetWireInValue(nssOKWire, 1, 0x0001);
                opalkelly.UpdateWireIns();
                if(status != okFrontPanel.ErrorCode.NoError)
                {
                    log.warning(status.toString());
                }
            }
            else
            {
                okFrontPanel.ErrorCode status = opalkelly.SetWireInValue(nssOKWire, 0, 0x0001);
                opalkelly.UpdateWireIns();
                if(status != okFrontPanel.ErrorCode.NoError)
                {
                    log.warning(status.toString());
                }
            }
        }
    }
    
    public void resetOKTOOL()
    {
        /*okFrontPanel.ErrorCode status = opalkelly.ActivateTriggerIn(0x40, 0);
        opalkelly.UpdateTriggerOuts();
        if(status != okFrontPanel.ErrorCode.NoError)
        {
            log.warning(status.toString());
        }*/
        this.resetTimestamps();
    }
    
    public void setSaveDataEventEnabled(boolean enable) throws HardwareInterfaceException 
    {
        if (enable) {
            startAESave_Data();
        } else {
            stopAESave_Data();
        }
    }
    
    public void startAESave_Data() 
    {
        setAeSave_Data(new AEOpalKellySave_Data());
        log.info("Start AE save_data...");
        getAeSave_Data().start();
    }
    
    public void stopAESave_Data() 
    {
        if (getAeSave_Data() != null) 
        {
            // close device
            getAeSave_Data().finish();
        }
    }
    
    public AEOpalKellySave_Data getAeSave_Data() 
    {
        return AESave_Data;
    }

    public void setAeSave_Data(AEOpalKellySave_Data aeSave_Data) 
    {
        AESave_Data = aeSave_Data;
    }
    
    public void openFiletoSave_Data() throws HardwareInterfaceException
    {
        String dateString = AEDataFile.DATE_FORMAT.format(new Date());
        String className = chip.getClass().getSimpleName();
        int suffixNumber = 0;
        // TODO replace with real serial number code in devices!
        String serialNumber = "";
        if ((chip.getHardwareInterface() != null) && (chip.getHardwareInterface() instanceof USBInterface)) 
        {
                USBInterface usb = (USBInterface) chip.getHardwareInterface();
                if ((usb.getStringDescriptors() != null) && (usb.getStringDescriptors().length == 3) && (usb.getStringDescriptors()[2] != null)) 
                {
                        serialNumber = "-" + usb.getStringDescriptors()[2];
                }
        }
        
        String defaultLoggingFolderName = System.getProperty("user.home");
        // log files to tmp folder initially, later user will move or delete file on end of logging
        String filename = defaultLoggingFolderName + File.separator + className + "-" + dateString + serialNumber + "-" + suffixNumber + AEDataFile.DATA_FILE_EXTENSION;
        File loggingFile = new File(filename);
        
        try
        {
            //loggingOutputStream = new AEFileOutputStream(new FileOutputStream(loggingFile), this.getChip());
            //loggingOutputStream = new AEFileOutputStream(new BufferedOutputStream(new FileOutputStream(loggingFile)), this.getChip()); // tobi changed to 8k buffer (from 400k) because this has measurablly better performance that super large buffer
            //loggingOutputStream = new AEFileOutputStream(new BufferedOutputStream(new FileOutputStream(loggingFile), 100000));
        } 
        catch (Exception e) //FileNotFoundException
        {
            log.warning(e.getMessage());
        }
    }
    
    public void setLoadDataEventEnabled(boolean enable) throws HardwareInterfaceException {
        if (enable) {
            startAELoad_Data();
        } else {
            stopAELoad_Data();
        }
    }
    
    public void startAELoad_Data() {
        setAeLoad_Data(new AEOpalKellyLoad_Data());
        log.info("Start AE load_data...");
        getAeLoad_Data().start();
        //loadDataEventEnabled = true;
    }
    
    public void stopAELoad_Data() {
        if (getAeLoad_Data() != null) {
            // close device
            getAeLoad_Data().finish();
            //loadDataEventEnabled = false;
        }
    }
    
    public AEOpalKellyLoad_Data getAeLoad_Data() {
        return AELoad_Data;
    }

    public void setAeLoad_Data(AEOpalKellyLoad_Data aeLoad_Data) {
        AELoad_Data = aeLoad_Data;
    }
    
    public void openFileToLoad_Data() throws HardwareInterfaceException
    {
        JFileChooser fileChooser = new JFileChooser();
        ChipDataFilePreview preview = new ChipDataFilePreview(fileChooser, chip); // from book swing hacks
        fileChooser.addPropertyChangeListener(preview);
        fileChooser.setAccessory(preview);

        String lastFilePath = prefs.get("AEViewer.lastFile", ""); // getString the last folder

        lastFile = new File(lastFilePath);

        DATFileFilter datFileFilter = new DATFileFilter();
        fileChooser.addChoosableFileFilter(datFileFilter);
        fileChooser.setCurrentDirectory(lastFile); // sets the working directory of the chooser
        //            boolean wasPaused=isPaused();
        //        setPaused(true);
        int retValue = fileChooser.showOpenDialog(preview);
        if (retValue == JFileChooser.APPROVE_OPTION) {
            lastFile = fileChooser.getSelectedFile();
                
        }
        fileChooser = null;
        
        getAEPacketRawFromFile(lastFile);
    }
    
    public void getAEPacketRawFromFile(File file) throws HardwareInterfaceException
    {
        try
        {
            //setCurrentFile(file); Viene de la clase AEVieweer. Puede ser muy interesante utilizarlo
            AEFileInputStream fileAEInputStream = new AEFileInputStream(file, this.getChip());
            fileAEInputStream.setFile(file);
            fileAEInputStream.setNonMonotonicTimeExceptionsChecked(false); // the code below has to take care about non-monotonic time anyway

            int numberOfEvents = (int) fileAEInputStream.size();

            seqPkt = fileAEInputStream.readPacketByNumber(numberOfEvents);
            
            if (seqPkt.getNumEvents() < numberOfEvents) {
                    int[] ad = new int[numberOfEvents];
                    int[] ts = new int[numberOfEvents];
                    int remainingevents = numberOfEvents;
                    int ind = 0;
                    do {
                            remainingevents = remainingevents - AEFileInputStream.MAX_BUFFER_SIZE_EVENTS;
                            System.arraycopy(seqPkt.getTimestamps(), 0, ts, ind * AEFileInputStream.MAX_BUFFER_SIZE_EVENTS, seqPkt.getNumEvents());
                            System.arraycopy(seqPkt.getAddresses(), 0, ad, ind * AEFileInputStream.MAX_BUFFER_SIZE_EVENTS, seqPkt.getNumEvents());
                            seqPkt = fileAEInputStream.readPacketByNumber(remainingevents);
                            ind++;

                    } while (remainingevents > AEFileInputStream.MAX_BUFFER_SIZE_EVENTS);

                    seqPkt = new AEPacketRaw(ad, ts);
            }
            // calculate interspike intervals
            int[] ts = seqPkt.getTimestamps();
            int[] isi = new int[seqPkt.getNumEvents()];

            isi[0] = ts[0];

            for (int i = 1; i < seqPkt.getNumEvents(); i++) {
                    isi[i] = ts[i] - ts[i - 1];
                    if (isi[i] < 0) {
                            //  if (!(ts[i-1]>0 && ts[i]<0)) //if it is not an overflow, it is non-monotonic time, so set isi to zero
                            //{
                            log.info("non-monotonic time at event " + i + ", set interspike interval to zero");
                            isi[i] = 0;
                            //}
                    }
            }
            seqPkt.setTimestamps(isi);
        }
        catch (Exception e) 
        {
            e.printStackTrace();
        }
    }
    
    ////////////////////////////////////////////////////////////////////
    public class AEOpalKellyReader extends Thread implements Runnable
    {
        //OpalKellyHardwareInterface monitor;
        
        protected boolean running;
        
        public AEOpalKellyReader()
        {
            setName("OpalKelly_AEReader");
            aePacketRawPool = new AEPacketRawPool(OpalKellyFX2Monitor.this);
            this.running = true;
        }
        
        @Override
        public void run()
        {
            while(running)
            {
                try
                {
                    synchronized (opalkelly)
                    {
                        byte[] buffer = new byte[aeBufferSize]; //new byte[8 * 1024 * 1024];
                        int readBytes = opalkelly.ReadFromBlockPipeOut(epAddress, usbBlockSize, buffer.length, buffer);
                        translateEvents(buffer, readBytes);
                    }
                }
                catch(Exception ex)
                {
                    log.warning(ex.getMessage());
                }
            }
            //log.info("reader thread ending");
        }
        
        synchronized public void finish() {
            this.running = false;
            log.info("reader thread ending");
            interrupt();
        }
        
        protected void translateEvents(byte[] buff, int numBytes)
        {
            synchronized (aePacketRawPool) 
            {
                AEPacketRaw buffer = aePacketRawPool.writeBuffer();
                
                int[] addresses = buffer.getAddresses();
                int[] timestamps = buffer.getTimestamps();
                
                buffer.lastCaptureIndex = eventCounter;
                //gotEvent=false;
                if((numBytes % 4) != 0)
                {
                    numBytes = (numBytes / 4) * 4;// truncate off any extra part-event
                }
                
                for(int i = 0; i < numBytes; i=i+4)
                {
                    byte[] timestamp = new byte[2];
                    byte[] address = new byte[2];
                    try
                    {
                        //while(((buff[i+1] & 0x80) >> 7) == 0 && i<numBytes) 
                        //    i=i+2;  // Descarta o salta las direccilones que se repiten cuando toca leer timestamps
                        timestamp[0] = buff[i];
                        //timestamp[1] = (byte) (0x7F & buff[i+1]);
                        timestamp[1] = buff[i+1];
                        
//                        while(((buff[i+3] & 0x80) >> 7) == 1 && i<numBytes) 
//                            i=i+2; // Descarta o salta los timestamps que se repiten cuando toca leer direcciones
                        address[0] = buff[i+2];
                        address[1] = buff[i+3];
                        
                        while(i+5<numBytes && buff[i+2] == buff[i+4] && buff[i+3] == buff[i+5]) 
                            i=i+2; // Descarta o salta los datos que se repiten cuando el buffer de salida tiene la señal de empty a uno.
                        
                        //while(i+4<numBytes && buff[i+2] == buff[i+4] && buff[i+3]==buff[i+5]) 
                        //    i=i+4; // Descarta o salta los timestamps que se repiten cuando toca leer direcciones
                        
                        //while(((buff[i+1] & 0x80) >> 7) == 1 && i<numBytes) 
                        //    i=i+2;  // Descarta o salta los timestamps que se repiten cuando toca leer direcciones
                        //address[0] = buff[i];
                        //address[1] = (byte) (0x7F & buff[i+1]);
                        
                        //while(((buff[i+3] & 0x80) >> 7) == 0 && i<numBytes)
                        //    i=i+2; // Descarta o salta las direcciones que se repiten cuando toca leer timestamps
                        //timestamp[0] = buff[i+2];
                        //timestamp[1] = buff[i+3];
                    }
                    catch(Exception ex)
                    {
                        //log.warning(ex.getMessage());
                        //continue;
                        break;
                    }
                    
                    if (eventCounter >= lastEventsAcquired.getCapacity()) 
                    {
                        overrunOccuredFlag = true;
                        continue;
                    }
                    
                    int addr =  ((address[1] & 0xFF) << 8) | (address[0] & 0xFF);
                    //int time = ((timestamp[1] & 0x7F) << 8) | (timestamp[0] & 0xFF);
                    int time = ((timestamp[1] & 0xFF) << 8) | (timestamp[0] & 0xFF);

                    //Discart null data
                    /*if(time == 0 && addr == 0)
                    {
                        continue;
                    }*/

                    if(time == 0xFFFF)
                    {
                        absolute_timestamp += 0x10000; //0x8000;
                        //wrapAdd += 0x10000;	// if we wrapped then increment wrap value by 2^16
                        /*if(!gotEvent) wrapsSinceLastEvent++;
                        if(wrapsSinceLastEvent>=WRAPS_TO_PRINT_NO_EVENT){
                            log.warning("got "+wrapsSinceLastEvent+" timestamp wraps without any events");
                            wrapsSinceLastEvent=0;
                        }*/
                        continue;
                    }
                    else
                    {
                        absolute_timestamp += time;
                    }

                    addresses[eventCounter] = addr;
                    //int test = TICK_US_CLK_FPGA * absolute_timestamp;
                    timestamps[eventCounter] = (int)(TICK_US_CLK_FPGA * absolute_timestamp);
                    eventCounter++;
                    buffer.setNumEvents(eventCounter);
                    //gotEvent=true;
                    //wrapsSinceLastEvent=0;

                    buffer.lastCaptureLength = eventCounter - buffer.lastCaptureIndex;
                }
                    
            }
        }
    }
    
    
    ////////////////////////////////////////////////////////////////////
    public class AEOpalKellySave_Data extends Thread implements Runnable
    {
        //OpalKellyHardwareInterface monitor;
        
        protected boolean running;
        private long abs_timestamp;
        BufferedWriter writer = null;
        String defaultLoggingFolderName = System.getProperty("user.home");
        String filename = defaultLoggingFolderName + File.separator + "test_addr_timestamp.csv";
        
        public AEOpalKellySave_Data()
        {
            setName("AEOpalKellySave_Data");
            seqPkt = new AEPacketRaw();
            abs_timestamp = 0;
            running = true;
            try
            {
                writer = new BufferedWriter(new FileWriter(filename));
                writer.write("Address;Timestamp\n");
            }
            catch(IOException ex)
            {
                log.info(ex.getMessage());
            }
        }
        
        @Override
        public void run()
        {
            while(running)
            {
                synchronized (opalkelly)
                {
                    byte[] buff = new byte[aeBufferSize];
                    int numBytes = opalkelly.ReadFromBlockPipeOut(epAddress, usbBlockSize, buff.length, buff);

                    for(int i = 0; i < numBytes; i=i+4)
                    {
                        byte[] timestamp = new byte[2];
                        byte[] address = new byte[2];
                        int addr, time;
                        try
                        {
                            timestamp[0] = buff[i];
                            timestamp[1] = buff[i+1];

                            address[0] = buff[i+2];
                            address[1] = buff[i+3];

                            addr =  ((address[1] & 0xFF) << 8) | (address[0] & 0xFF);
                            time = ((timestamp[1] & 0xFF) << 8) | (timestamp[0] & 0xFF);
                            if(time == 0xFFFF && addr == 0xFFFF)
                            {
                                running = false;
                                break;
                            }

                            while(i+5<numBytes && buff[i+2] == buff[i+4] && buff[i+3] == buff[i+5]) 
                                i=i+2; // Descarta o salta los datos que se repiten cuando el buffer de salida tiene la señal de empty a uno.

                        }
                        catch(Exception ex)
                        {
                            log.info(ex.getMessage());
                            break;
                        }

                        if(time == 0xFFFF)
                        {
                            //Timestamp overflow (special event)
                            abs_timestamp += 0x10000;
                            continue;
                        }
                        else
                        {
                            abs_timestamp += time;
                        }

                        try
                        {
                            writer.write(Integer.toString(addr) + ";" + Integer.toString((int)(abs_timestamp/100)) + "\n");
                        }
                        catch(IOException ex)
                        {
                            log.warning(ex.getMessage());
                        }
                        seqPkt.addEvent(new EventRaw(addr, (int)(TICK_US_CLK_FPGA * abs_timestamp)));
                    }
                }
            }
            
            try 
            {
                synchronized (loggingOutputStream) 
                {
                    loggingOutputStream.writePacket(seqPkt);
                    loggingOutputStream.close();
                }
            } 
            //catch (IOException ex) 
            catch (Exception ex) 
            {
                log.warning(ex.getMessage());
            }
            
            //log.info("Load_data thread ending");
        }
        
        synchronized public void finish() 
        {
            running = false;
            log.info("Load_data thread ending");
            try {
                // Close the writer regardless of what happens...
                writer.close();
            } catch (Exception ex) {
            }
            interrupt();
        }
    }
    
    ////////////////////////////////////////////////////////////////////
    public class AEOpalKellyLoad_Data extends Thread implements Runnable
    {
        //OpalKellyHardwareInterface monitor;
        
        protected boolean running;
        private int ev_index;
        private int[] allTs;
        private int[] allAddr;
        private int numTotalEvents;
        //private int lastTimestamp;
        private byte[] data;
        private int data_index;
        
        public AEOpalKellyLoad_Data()
        {
            setName("OpalKelly_AELoad_Data");
            ev_index = 0;
            allTs = seqPkt.getTimestamps();
            allAddr = seqPkt.getAddresses();
            numTotalEvents = seqPkt.getNumEvents();
            //lastTimestamp = 0;
            data = new byte[aeBufferSize];
            data_index = 0;
            
            running = true;
        }
        
        @Override
        public void run()
        {
            synchronized (opalkelly)
            {
                while(running)
                {
                    int timestamp = allTs[ev_index];
                    int address = allAddr[ev_index];

                    int dtTimestamp = timestamp;// - lastTimestamp;
                    if(dtTimestamp > 0xFFFF)
                    {
                        int overflowEvents = (int)Math.floor(dtTimestamp / 0xFFFF);
                        for (int i=0; i<overflowEvents; i++)
                        {
                            data[data_index++] = (byte)0xFF;
                            data[data_index++] = (byte)0xFF;
                            data[data_index++] = (byte)0x00;
                            data[data_index++] = (byte)0x00;

                            if(data_index >= data.length)
                            {
                                try
                                {
                                    int writeBytes = opalkelly.WriteToBlockPipeIn(0x80, usbBlockSize, data.length, data);
                                    data = new byte[aeBufferSize];
                                    data_index = 0;
                                }
                                catch(Exception ex)
                                {
                                    log.warning(ex.getMessage());
                                }
                            }
                        }

                        dtTimestamp = dtTimestamp - overflowEvents * 0xFFFF;
                    }

                    dtTimestamp = dtTimestamp * 100; //us to ns (clock 100Mz -> T 10 ns)
                    data[data_index++] = (byte)(dtTimestamp & 0x000000FF);
                    data[data_index++] = (byte)((dtTimestamp & 0x0000FF00) >> 8);
                    data[data_index++] = (byte)(address & 0x00FF);
                    data[data_index++] = (byte)((address & 0xFF00) >> 8);

                    //lastTimestamp = allTs[ev_index];

                    if(data_index >= data.length)
                    {
                        try
                        {
                            int writeBytes = opalkelly.WriteToBlockPipeIn(0x80, 1024, data.length, data);
                            data = new byte[aeBufferSize];
                            data_index = 0;
                        }
                        catch(Exception ex)
                        {
                            log.warning(ex.getMessage());
                        }
                    }

                    if(++ev_index >= numTotalEvents)
                    {
                        running = false;
                    }
                }

                data[data_index++] = (byte)0xFF;
                data[data_index++] = (byte)0xFF;
                data[data_index++] = (byte)0xFF;
                data[data_index++] = (byte)0xFF;

                try
                {
                    int writeBytes = opalkelly.WriteToBlockPipeIn(0x80, 1024, data.length, data);
                    data = new byte[1024];
                    data_index = 0;
                }
                catch(Exception ex)
                {
                    log.warning(ex.getMessage());
                }

                //log.info("Load_data thread ending");
            }
        }
        
        synchronized public void finish() {
            running = false;
            log.info("Load_data thread ending");
            interrupt();
        }
    }
}
