package rars.venus.visualization;

import rars.simulator.MemoryAccessRecord;

import javax.swing.*;
import javax.swing.text.Style;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.*;
import java.util.List;

/**
 * Visualizes accesses to shared memory.
 */
public class SharedMemoryPanel extends JPanel {

    private JTextPane logArea;

    public SharedMemoryPanel() {
        super(new BorderLayout());
        setBorder(BorderFactory.createTitledBorder("Shared Memory Accesses"));
        
        logArea = new JTextPane();
        logArea.setEditable(false);
        
        add(new JScrollPane(logArea), BorderLayout.CENTER);
    }

    public void update(List<MemoryAccessRecord> accesses) {
        if (accesses == null || accesses.isEmpty()) return;
        
        StyledDocument doc = logArea.getStyledDocument();
        for (MemoryAccessRecord rec : accesses) {
            String line = String.format("Cyc %d | Core %d | %s | %s | Addr: 0x%08x | Val: %d\n",
                    rec.getCycle(), rec.getCoreId(), rec.getInstructionName(), 
                    rec.getType(), rec.getAddress(), rec.getValue());
                    
            Style style = logArea.addStyle("Style", null);
            StyleConstants.setForeground(style, getCoreColor(rec.getCoreId()));
            StyleConstants.setFontFamily(style, "Monospaced");
            StyleConstants.setFontSize(style, 12);
            
            if (rec.getType() == MemoryAccessRecord.AccessType.WRITE) {
                StyleConstants.setBold(style, true);
            }
            
            try {
                doc.insertString(doc.getLength(), line, style);
            } catch (Exception e) {}
        }
        logArea.setCaretPosition(doc.getLength());
    }

    private Color getCoreColor(int coreId) {
        switch (coreId % 4) {
            case 0: return new Color(0x1976d2); // Blue
            case 1: return new Color(0x388e3c); // Green
            case 2: return new Color(0x7b1fa2); // Purple
            case 3: return new Color(0xc2185b); // Pink
            default: return Color.BLACK;
        }
    }

    public void reset() {
        logArea.setText("");
    }
}
