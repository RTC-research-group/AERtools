/*
 * Created in ATC, University of Seville. BIOSENSE project 2015
 */
package es.us.atc.jaer.hardwareinterface;

import com.opalkelly.frontpanel.okFrontPanel;
import de.thesycon.usbio.PnPNotifyInterface;
import java.util.logging.Logger;
import net.sf.jaer.hardwareinterface.HardwareInterface;
import net.sf.jaer.hardwareinterface.HardwareInterfaceException;
import net.sf.jaer.hardwareinterface.HardwareInterfaceFactoryInterface;

/**
 *
 * @author arios (Antonio Rios-Navarro arios)
 * @description This hardware interface is used for manage Opal Kelly platform which can monitor, logger and sequence events.
 * Monitor is used for get events from AER bus and send them directly through USB port to computer.
 * Logger function stores the events from AER bus on DRR2 on board memory. Then this events can be sequenced or downloaded to the computer.
 * Sequence function is used to play the events stored on DRR2 on board memory through the AER output port.
 * TODO --> describe the protocol!!!!
 */
public class OpalKellyFX2MonitorFactory implements HardwareInterfaceFactoryInterface, PnPNotifyInterface{

    public OpalKellyFX2Monitor OKHardwareInterface = new OpalKellyFX2Monitor();
    
    final static Logger log = Logger.getLogger("OpalKellyFX2MonitorFactory");
    private static final OpalKellyFX2MonitorFactory instance = new OpalKellyFX2MonitorFactory(); //final ??
    public static String GUID = "{c4caf39f-201c-46d2-813b-9f6542cc7686}";
    private int numInterfacesAvaible = 0;
    
    public OpalKellyFX2MonitorFactory()
    {
        //System.loadLibrary("okjFrontPanel");
    }
    
    public static OpalKellyFX2MonitorFactory instance()
    {
        return instance;
    }
    
    @Override
    public int getNumInterfacesAvailable() {
        okFrontPanel opalkelly = new okFrontPanel();
        numInterfacesAvaible = opalkelly.GetDeviceCount();
        opalkelly.delete();
        return numInterfacesAvaible;
    }

    @Override
    public HardwareInterface getFirstAvailableInterface() throws HardwareInterfaceException {
        return getInterface(0);
    }

    @Override
    public HardwareInterface getInterface(int n) throws HardwareInterfaceException {
        //OpalKellyFX2Monitor OKHardwareInterface = new OpalKellyFX2Monitor();
        return OKHardwareInterface;
    }

    @Override
    public String getGUID() {
        return OpalKellyFX2MonitorFactory.GUID;
    }

    @Override
    public void onAdd() {
        log.info("Opal Kelly device added");
    }

    @Override
    public void onRemove() {
        log.info("Opal Kelly device removed");
    }
    
}
