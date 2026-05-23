package rars.venus.visualization;

import rars.simulator.CycleState;
import rars.simulator.MultiCoreSimulator;
import rars.simulator.MemoryAccessRecord;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.util.List;

/**
 * Split view showing all cores simultaneously along with a shared memory panel.
 */
public class MultiCorePanel extends JPanel {

    private JPanel coresPanel;
    private SharedMemoryPanel sharedMemoryPanel;
    private RaceConditionPanel raceConditionPanel;
    private int numCores = 0;
    
    private JLabel[] coreStatusLabels;
    private JTextArea[] coreRegAreas;

    public MultiCorePanel() {
        super(new BorderLayout());
        
        coresPanel = new JPanel();
        
        sharedMemoryPanel = new SharedMemoryPanel();
        raceConditionPanel = new RaceConditionPanel();
        
        JSplitPane bottomSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sharedMemoryPanel, raceConditionPanel);
        bottomSplit.setResizeWeight(0.6);
        
        JSplitPane mainSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(coresPanel), bottomSplit);
        mainSplit.setResizeWeight(0.5);
        
        this.add(mainSplit, BorderLayout.CENTER);
    }

    private void initCores(int count) {
        this.numCores = count;
        coresPanel.removeAll();
        coresPanel.setLayout(new GridLayout(1, count, 10, 0));
        
        coreStatusLabels = new JLabel[count];
        coreRegAreas = new JTextArea[count];
        
        for (int i = 0; i < count; i++) {
            JPanel corePanel = new JPanel(new BorderLayout());
            corePanel.setBorder(BorderFactory.createTitledBorder(
                    BorderFactory.createLineBorder(Color.GRAY), 
                    "Core " + i, TitledBorder.CENTER, TitledBorder.TOP, 
                    new Font("SansSerif", Font.BOLD, 14), Color.BLUE));
            
            coreStatusLabels[i] = new JLabel("Status: READY", SwingConstants.CENTER);
            corePanel.add(coreStatusLabels[i], BorderLayout.NORTH);
            
            coreRegAreas[i] = new JTextArea(10, 15);
            coreRegAreas[i].setEditable(false);
            coreRegAreas[i].setFont(new Font("Monospaced", Font.PLAIN, 12));
            corePanel.add(new JScrollPane(coreRegAreas[i]), BorderLayout.CENTER);
            
            coresPanel.add(corePanel);
        }
        coresPanel.revalidate();
    }

    public void update(MultiCoreSimulator.MultiCoreCycleSnapshot snapshot) {
        if (snapshot == null) return;
        
        if (snapshot.coreStates.length != numCores) {
            initCores(snapshot.coreStates.length);
        }
        
        for (int i = 0; i < numCores; i++) {
            CycleState state = snapshot.coreStates[i];
            String status = snapshot.coreStatuses[i];
            coreStatusLabels[i].setText(String.format("Status: %s | PC: 0x%08x", status, state != null ? state.getPc() : 0));
            
            if (state != null) {
                StringBuilder regs = new StringBuilder();
                regs.append("Inst: ").append(state.getInstructionText()).append("\n");
                regs.append("x").append(state.getRs1()).append(" = ").append(state.getRs1Val()).append("\n");
                regs.append("x").append(state.getRs2()).append(" = ").append(state.getRs2Val()).append("\n");
                regs.append("ALU Out = ").append(state.getAluResult()).append("\n");
                coreRegAreas[i].setText(regs.toString());
            }
        }
        
        sharedMemoryPanel.update(snapshot.memoryAccesses);
        raceConditionPanel.update(snapshot.newRaces);
    }

    public void reset() {
        for (int i = 0; i < numCores; i++) {
            coreStatusLabels[i].setText("Status: READY");
            coreRegAreas[i].setText("");
        }
        sharedMemoryPanel.reset();
        raceConditionPanel.reset();
    }
}
