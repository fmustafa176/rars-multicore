package rars.venus;

import rars.simulator.ExecutionMode;
import rars.simulator.Simulator;
import rars.simulator.MultiCoreSimulator;
import rars.venus.visualization.*;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

/**
 * Top-level tab for computer architecture visualization.
 * Contains execution controls (mode, cores) and dynamically swaps
 * the center panel based on the selected execution mode.
 */
public class VisualizationPane extends JPanel {

    private JComboBox<ExecutionMode> modeSelector;
    private JComboBox<Integer> coreSelector;
    
    private JPanel centerPanel;
    private DatapathPanel datapathPanel;
    private PipelineTablePanel pipelineTablePanel;
    private MultiCorePanel multiCorePanel;
    
    private JLabel statusLabel;

    public VisualizationPane() {
        super(new BorderLayout());
        
        // Toolbar
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        
        toolbar.add(new JLabel("Mode: "));
        modeSelector = new JComboBox<>(ExecutionMode.values());
        modeSelector.addActionListener(e -> updateVisualizationView());
        toolbar.add(modeSelector);
        
        toolbar.add(Box.createHorizontalStrut(20));
        
        toolbar.add(new JLabel("Cores: "));
        coreSelector = new JComboBox<>(new Integer[]{1, 2, 4});
        coreSelector.addActionListener(e -> updateVisualizationView());
        toolbar.add(coreSelector);

        toolbar.add(Box.createHorizontalStrut(20));

        JButton resetBtn = new JButton("Reset Visualization");
        resetBtn.addActionListener(e -> resetVisualization());
        toolbar.add(resetBtn);

        this.add(toolbar, BorderLayout.NORTH);

        // Center Panels
        centerPanel = new JPanel(new CardLayout());
        
        datapathPanel = new DatapathPanel();
        pipelineTablePanel = new PipelineTablePanel();
        multiCorePanel = new MultiCorePanel();
        
        centerPanel.add(datapathPanel, "DATAPATH");
        centerPanel.add(pipelineTablePanel, "PIPELINE");
        centerPanel.add(multiCorePanel, "MULTICORE");
        
        this.add(centerPanel, BorderLayout.CENTER);

        // Status bar
        JPanel statusBar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        statusLabel = new JLabel("Cycle: 0 | IPC: 0.00 | Ready");
        statusBar.add(statusLabel);
        this.add(statusBar, BorderLayout.SOUTH);

        // Initial setup
        updateVisualizationView();
    }

    private void updateVisualizationView() {
        ExecutionMode mode = (ExecutionMode) modeSelector.getSelectedItem();
        int cores = (Integer) coreSelector.getSelectedItem();
        
        CardLayout cl = (CardLayout) centerPanel.getLayout();
        
        if (cores > 1) {
            cl.show(centerPanel, "MULTICORE");
        } else if (mode == ExecutionMode.PIPELINED) {
            cl.show(centerPanel, "PIPELINE");
        } else {
            cl.show(centerPanel, "DATAPATH");
        }
        
        // Update Simulator configuration
        Simulator.getInstance().setupVisualization(cores, mode);
    }

    public void updateVisualization(MultiCoreSimulator.MultiCoreCycleSnapshot snapshot) {
        ExecutionMode mode = (ExecutionMode) modeSelector.getSelectedItem();
        int cores = (Integer) coreSelector.getSelectedItem();

        if (cores > 1) {
            multiCorePanel.update(snapshot);
        } else if (mode == ExecutionMode.PIPELINED) {
            pipelineTablePanel.update(snapshot);
        } else {
            datapathPanel.update(snapshot);
        }

        // Update status bar
        MultiCoreSimulator mcs = Simulator.getInstance().getMultiCoreSimulator();
        if (mcs != null) {
            statusLabel.setText(String.format("Cycle: %d | Overall IPC: %.2f | %s",
                    snapshot.cycleNumber,
                    mcs.getTotalIPC(),
                    mcs.isAllHalted() ? "Halted" : "Running"));
        }
    }

    private void resetVisualization() {
        datapathPanel.reset();
        pipelineTablePanel.reset();
        multiCorePanel.reset();
        statusLabel.setText("Cycle: 0 | IPC: 0.00 | Ready");
        Simulator.getInstance().resetVisualization();
    }

    public void enableControls(boolean enable) {
        modeSelector.setEnabled(enable);
        coreSelector.setEnabled(enable);
    }
}
