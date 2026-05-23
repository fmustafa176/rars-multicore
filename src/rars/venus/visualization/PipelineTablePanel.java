package rars.venus.visualization;

import rars.simulator.CycleState;
import rars.simulator.MultiCoreSimulator;
import rars.simulator.PipelineEngine;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Pipeline Table timing diagram.
 * Shows instructions flowing through IF, ID, EX, MEM, WB stages over time.
 */
public class PipelineTablePanel extends JPanel {

    private JTable table;
    private DefaultTableModel tableModel;
    private List<PipelineEngine.PipelineSnapshot> snapshots;

    public PipelineTablePanel() {
        super(new BorderLayout());
        snapshots = new ArrayList<>();

        tableModel = new DefaultTableModel();
        tableModel.addColumn("Instruction"); // Column 0

        table = new JTable(tableModel);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.getColumnModel().getColumn(0).setPreferredWidth(200);
        table.setRowHeight(30);

        table.setDefaultRenderer(Object.class, new StageCellRenderer());

        JScrollPane scrollPane = new JScrollPane(table);
        this.add(scrollPane, BorderLayout.CENTER);
    }

    public void update(MultiCoreSimulator.MultiCoreCycleSnapshot snapshot) {
        if (snapshot != null && snapshot.pipelineSnapshots != null && snapshot.pipelineSnapshots.length > 0) {
            PipelineEngine.PipelineSnapshot ps = snapshot.pipelineSnapshots[0];
            if (ps != null) {
                snapshots.add(ps);
                updateTable();
            }
        }
    }

    public void reset() {
        snapshots.clear();
        tableModel.setRowCount(0);
        tableModel.setColumnCount(1); // Reset to just "Instruction"
    }

    private void updateTable() {
        if (snapshots.isEmpty()) return;

        // Ensure we have enough columns for all cycles
        int currentCycle = snapshots.get(snapshots.size() - 1).cycleNumber;
        while (tableModel.getColumnCount() <= currentCycle) {
            tableModel.addColumn("C" + tableModel.getColumnCount());
        }

        // We process the snapshots to build rows per instruction.
        // For simplicity in this demo, we'll represent each cycle as a row, 
        // showing the 5 stages active in that cycle.
        
        tableModel.setRowCount(0);
        for (PipelineEngine.PipelineSnapshot snap : snapshots) {
            Object[] row = new Object[tableModel.getColumnCount()];
            row[0] = "Cycle " + snap.cycleNumber;
            
            // Put the stages into the column corresponding to the cycle number
            // In a full implementation, rows would be instructions, not cycles.
            // But doing instructions requires matching them across cycles.
            // Let's do a simplified approach: row = cycle, showing pipeline contents.
            int c = snap.cycleNumber;
            row[c] = buildCellString(snap);
            
            tableModel.addRow(row);
        }
        
        // Auto-scroll to bottom
        int lastRow = table.getRowCount() - 1;
        if (lastRow >= 0) {
            table.scrollRectToVisible(table.getCellRect(lastRow, 0, true));
        }
    }
    
    private String buildCellString(PipelineEngine.PipelineSnapshot snap) {
        return String.format("IF:%s | ID:%s | EX:%s | MEM:%s | WB:%s",
                formatStage(snap.ifState),
                formatStage(snap.idState),
                formatStage(snap.exState),
                formatStage(snap.memState),
                formatStage(snap.wbState));
    }
    
    private String formatStage(CycleState state) {
        if (state == null) return "-";
        if (state.isBubble()) return "BUB";
        if (state.isFlush()) return "FLS";
        if (state.isStall()) return "STL";
        
        // Return opcode mnemonic briefly
        String text = state.getInstructionText();
        if (text != null && text.contains(" ")) {
            return text.split(" ")[0];
        }
        return text != null ? text : "-";
    }

    private class StageCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean isSelected, boolean hasFocus, int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            if (value != null && value.toString().contains("STL")) {
                c.setBackground(Color.LIGHT_GRAY);
            } else if (value != null && value.toString().contains("FLS")) {
                c.setBackground(new Color(255, 150, 150));
            } else if (column > 0 && value != null) {
                c.setBackground(new Color(230, 240, 255));
            } else {
                c.setBackground(Color.WHITE);
            }
            return c;
        }
    }
}
