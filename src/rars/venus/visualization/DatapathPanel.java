package rars.venus.visualization;

import rars.simulator.CycleState;
import rars.simulator.MultiCoreSimulator;

import javax.swing.*;
import java.awt.*;
import java.util.Map;

/**
 * Animated RISC-V datapath diagram using Java2D.
 * Highlights active paths and shows data values flowing.
 */
public class DatapathPanel extends JPanel {

    private CycleState currentState;
    
    // Colors
    private final Color COLOR_ACTIVE = new Color(46, 204, 113);   // Green
    private final Color COLOR_INACTIVE = new Color(189, 195, 199); // Light Gray
    private final Color COLOR_TEXT = new Color(44, 62, 80);        // Dark Blue/Gray
    private final Color COLOR_BOX = new Color(236, 240, 241);      // Off-white

    public DatapathPanel() {
        setBackground(Color.WHITE);
        setPreferredSize(new Dimension(800, 600));
    }

    public void update(MultiCoreSimulator.MultiCoreCycleSnapshot snapshot) {
        if (snapshot != null && snapshot.coreStates.length > 0) {
            this.currentState = snapshot.coreStates[0]; // Single-core uses core 0
            repaint();
        }
    }

    public void reset() {
        this.currentState = null;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int w = getWidth();
        int h = getHeight();

        // Title/Status
        g2.setColor(COLOR_TEXT);
        g2.setFont(new Font("SansSerif", Font.BOLD, 18));
        if (currentState != null) {
            String stageStr = currentState.getCurrentStage() != null ? 
                              " - " + currentState.getCurrentStage().getLabel() : "";
            g2.drawString("Cycle " + currentState.getCycleNumber() + stageStr, 20, 30);
            
            g2.setFont(new Font("Monospaced", Font.BOLD, 16));
            g2.drawString(currentState.getInstructionText(), 20, 55);
            g2.setFont(new Font("Monospaced", Font.PLAIN, 12));
            g2.drawString("PC: 0x" + Integer.toHexString(currentState.getPc()), 20, 75);
        } else {
            g2.drawString("Ready", 20, 30);
        }

        // Draw basic blocks
        // 1. PC
        drawBox(g2, 50, 300, 60, 100, "PC", "0x" + (currentState != null ? Integer.toHexString(currentState.getPc()) : "0"));
        
        // 2. Instruction Memory
        drawBox(g2, 160, 250, 100, 200, "Instruction", "Memory");
        
        // 3. Registers
        drawBox(g2, 350, 250, 100, 200, "Registers", "File");
        
        // 4. ALU
        drawALU(g2, 520, 200, 80, 180, currentState != null ? currentState.getAluOperation() : "ALU");
        
        // 5. Data Memory
        drawBox(g2, 650, 250, 100, 200, "Data", "Memory");

        // Draw connections (simplified for brevity)
        if (currentState != null) {
            Map<String, Integer> sigs = currentState.getControlSignals();
            
            // PC -> IMEM
            drawPath(g2, 110, 350, 160, 350, true, String.valueOf(currentState.getPc()));
            
            // IMEM -> Reg
            drawPath(g2, 260, 300, 350, 300, true, "rs1=" + currentState.getRs1());
            drawPath(g2, 260, 400, 350, 400, true, "rs2=" + currentState.getRs2());
            
            // Reg -> ALU
            drawPath(g2, 450, 260, 520, 260, true, String.valueOf(currentState.getRs1Val()));
            
            boolean aluSrc = sigs.getOrDefault("ALUSrc", 0) == 1;
            drawPath(g2, 450, 340, 520, 340, true, aluSrc ? String.valueOf(currentState.getImmediate()) : String.valueOf(currentState.getRs2Val()));
            
            // ALU -> DMEM
            drawPath(g2, 600, 290, 650, 290, true, String.valueOf(currentState.getAluResult()));
            
            // DMEM -> WB
            boolean memRead = sigs.getOrDefault("MemRead", 0) == 1;
            if (memRead) {
                drawPath(g2, 750, 350, 800, 350, true, String.valueOf(currentState.getMemReadData()));
            }
        }
    }

    private void drawBox(Graphics2D g2, int x, int y, int w, int h, String line1, String line2) {
        g2.setColor(COLOR_BOX);
        g2.fillRect(x, y, w, h);
        g2.setColor(COLOR_TEXT);
        g2.setStroke(new BasicStroke(2));
        g2.drawRect(x, y, w, h);
        
        g2.setFont(new Font("SansSerif", Font.PLAIN, 14));
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(line1, x + (w - fm.stringWidth(line1)) / 2, y + h/2 - 10);
        g2.drawString(line2, x + (w - fm.stringWidth(line2)) / 2, y + h/2 + 10);
    }
    
    private void drawALU(Graphics2D g2, int x, int y, int w, int h, String label) {
        int[] xPoints = {x, x+w, x+w, x, x, x+w/3, x};
        int[] yPoints = {y, y+h/3, y+(2*h)/3, y+h, y+(2*h)/3, y+h/2, y+h/3};
        
        g2.setColor(COLOR_BOX);
        g2.fillPolygon(xPoints, yPoints, 7);
        g2.setColor(COLOR_TEXT);
        g2.setStroke(new BasicStroke(2));
        g2.drawPolygon(xPoints, yPoints, 7);
        
        g2.setFont(new Font("SansSerif", Font.BOLD, 16));
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(label, x + (w - fm.stringWidth(label)) / 2 + 10, y + h/2 + 5);
    }

    private void drawPath(Graphics2D g2, int x1, int y1, int x2, int y2, boolean active, String label) {
        g2.setColor(active ? COLOR_ACTIVE : COLOR_INACTIVE);
        g2.setStroke(new BasicStroke(3));
        g2.drawLine(x1, y1, x2, y2);
        
        // Arrow head
        if (x1 != x2) {
            int dir = x2 > x1 ? -1 : 1;
            g2.drawLine(x2, y2, x2 + dir * 10, y2 - 5);
            g2.drawLine(x2, y2, x2 + dir * 10, y2 + 5);
        }
        
        if (label != null && active) {
            g2.setColor(COLOR_TEXT);
            g2.setFont(new Font("Monospaced", Font.PLAIN, 12));
            g2.drawString(label, (x1+x2)/2 - 10, y1 - 5);
        }
    }
}
