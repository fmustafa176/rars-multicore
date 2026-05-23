package rars.venus.visualization;

import rars.simulator.RaceCondition;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.List;

/**
 * Log/table showing detected race conditions.
 */
public class RaceConditionPanel extends JPanel {

    private JTable table;
    private DefaultTableModel tableModel;

    public RaceConditionPanel() {
        super(new BorderLayout());
        setBorder(BorderFactory.createTitledBorder("Race Conditions"));
        
        tableModel = new DefaultTableModel(new String[]{"Cycle", "Address", "Type", "Resolved", "Access 1", "Access 2"}, 0);
        table = new JTable(tableModel);
        
        // Adjust column widths
        table.getColumnModel().getColumn(0).setPreferredWidth(50);
        table.getColumnModel().getColumn(1).setPreferredWidth(80);
        table.getColumnModel().getColumn(2).setPreferredWidth(50);
        table.getColumnModel().getColumn(3).setPreferredWidth(60);
        table.getColumnModel().getColumn(4).setPreferredWidth(200);
        table.getColumnModel().getColumn(5).setPreferredWidth(200);
        
        add(new JScrollPane(table), BorderLayout.CENTER);
    }

    public void update(List<RaceCondition> newRaces) {
        if (newRaces == null || newRaces.isEmpty()) return;
        
        for (RaceCondition rc : newRaces) {
            String c1Color = getCoreColorHex(rc.getAccess1().getCoreId());
            String c2Color = getCoreColorHex(rc.getAccess2().getCoreId());
            String typeColor = rc.getType().name().contains("WRITE_WRITE") ? "#d32f2f" : "#f57c00"; // Red for WW, Orange for others

            Object[] row = new Object[]{
                rc.getDetectionCycle(),
                String.format("0x%08x", rc.getAddress()),
                String.format("<html><font color='%s'><b>%s</b></font></html>", typeColor, rc.getType().getLabel()),
                rc.isResolved() ? "<html><font color='green'>✅</font></html>" : "<html><font color='red'>❌</font></html>",
                String.format("<html><font color='%s'><b>C%d</b></font>: %s</html>", c1Color, rc.getAccess1().getCoreId(), rc.getAccess1().getInstructionName()),
                String.format("<html><font color='%s'><b>C%d</b></font>: %s</html>", c2Color, rc.getAccess2().getCoreId(), rc.getAccess2().getInstructionName())
            };
            tableModel.addRow(row);
        }
        
        int lastRow = table.getRowCount() - 1;
        if (lastRow >= 0) {
            table.scrollRectToVisible(table.getCellRect(lastRow, 0, true));
        }
    }

    private String getCoreColorHex(int coreId) {
        switch (coreId % 4) {
            case 0: return "#1976d2"; // Blue
            case 1: return "#388e3c"; // Green
            case 2: return "#7b1fa2"; // Purple
            case 3: return "#c2185b"; // Pink
            default: return "#000000";
        }
    }

    public void reset() {
        tableModel.setRowCount(0);
    }
}
