# Approaching Cell AER project

Once you obtain the AERnode, plus OKAERtool boards from COBER SpinOff company (www.t-cober.es) if they are burned with the AC firmware in flash, the you need these details to allow you to start woking.

What you need is the following hardware:
- A tower of 3 PCBs composed by one AER node (bottom), an OKAER tool and an OpalKelly 150LXT on the top.
- The OpalKelly is programmed with the firmware that enables you to use the OKAERtool filter from jAER.
- The AERnode is programmed with the firmware of our paper: your AC + our 4 trackers + a backgroud activity filter. These blocks can be parameterized with the jAER filter called ATCFpgaConfig_2 that you programmed during your visit to Seville in 2017.

In order to run the Entropy demo you need to download the AC folder of https://github.com/RTC-research-group/AERtools and follow the follwing steps:
1. Connect the power supply to the OpalKelly power connector. This power supply will supply the power to the AERnode as well.
2. Download and install the OpalKelly drivers on your laptop: FrontPanelUSB-Win-x64-4.5.0.exe (or win32 version if needed).
3. Connect the USB cable from OpalKelly to your laptop.
4. Open Netbeans and the jaer project we have prepared for this (https://github.com/RTC-research-group/AERtools/ApproachingCell/jAER/jaer-master.zip).
5. Select the DVS128 chip, then the okaer interface, and then open the filters viewer.
6. Search for the filter OKAERtool_Commands and open its controls.
7. Click on "Allmerged" checkbox at Input panel, and "sequencerOut" checkbox at Output panel.
8. Click the button "6_Load_Data" and select the aedat file to load to the DDR2 memory of the OpalKelly PCB (for example the AC30s.aedat). When finished click the button "7_Sequence".
9. Click the button "1_Monitor" and you will start to see in the screen input and the output of the AC+trackers in the screen.
10. In order to watch with a diferent color the output events, you have to activate the filter "TrackEventColored".
11. In order to play with the Background activity filter, the trackers and your AC circuit in jAER you have to open and modifly these parameters in the filter "ATCFpgaConfig_2". Remember that while the OKAERtool is monitoring it becomes to slow to update the parameters of the AERnode. So I recommend to update the parameters before step 9 of this list.
12. Enjoy it!! 