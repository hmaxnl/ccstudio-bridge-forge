package dev.kivts.cide.client;

import dev.kivts.cide.bridge.BridgeLog;
import dev.kivts.cide.bridge.WebSocketBridge;
import dev.kivts.cide.config.CideClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.Mth;

import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * ccLink bridge control bar + collapsible log drawer (spec sections 4.2 and 4.3,
 * phase 3). Rendered on top of CideScreen's bottom edge; per-screen state, with a
 * static session badge that phase 4 updates from the ccLink ACTIVE state.
 */
public final class BridgePanel {
    private static final int BAR_H = 18;
    private static final int LINE_H = 9;
    private static final int MIN_DRAWER_H = 80;
    private static final int MAX_DRAWER_H = 150;

    private static final int C_BAR_BG = 0xFF0B0F14;
    private static final int C_DRAWER_BG = 0xFF0D1117;
    private static final int C_DRAWER_HEAD = 0xFF161C26;
    private static int C_DIVIDER;
    private static int C_BTN;
    private static int C_BTN_HOVER;
    private static int C_BTN_BORDER;

    // Session badge (spec 4.2): phase 4 sets this from the ccLink ACTIVE state.
    private static volatile boolean sessionActive = false;
    private static volatile String sessionText = "No Active Computer";

    public static void setSession(boolean active, String computerDescription) {
        sessionActive = active;
        sessionText = (computerDescription == null || computerDescription.isBlank())
            ? "No Active Computer" : computerDescription;
    }

    private record Badge(int dotColor, String text, int textColor) {}

    private record LogLine(int color, String text) {}

    // Per-screen interaction state
    private boolean drawerOpen;
    private boolean wasDrawerOpen;
    private int drawerScroll; // lines above the bottom edge
    private boolean portFocused;
    private String portDraft = "";
    private long portErrorUntil;
    private long copiedUntil;

    // Cached rects (recomputed by layout(), shared by render and hit testing)
    private int lx, ly, lw, lh, barTop;
    private int startX, startY, startW, startH;
    private int portX, portY, portW, portH;
    private int logsX, logsY, logsW, logsH;
    private int clearX, clearY, clearW, clearH;
    private int copyX, copyY, copyW, copyH;
    private int drawerX, drawerY, drawerW, drawerH;

    public BridgePanel() {
        C_DIVIDER = 0x77697386;
        C_BTN = 0xFF182030;
        C_BTN_HOVER = 0xFF1E4E78;
        C_BTN_BORDER = 0x77697386;
    }

    private void layout(int panelX, int panelY, int panelW, int panelH) {
        lx = panelX; ly = panelY; lw = panelW; lh = panelH;
        barTop = panelY + panelH - BAR_H;

        int x = panelX + 6;

        Badge badge = badgeFor();
        badgeW = Math.min(fontSafeWidth(badge.text()), 190);
        x += 10 + badgeW + 10;

        startW = Math.max(48, fontSafeWidth("Disconnect") + 10); startH = 12;
        startX = x; startY = barTop + 3;
        x += startW + 8;

        portW = 46; portH = 12;
        portX = x; portY = barTop + 3;
        x += portW + 8;

        int sessionW = Math.min(fontSafeWidth(sessionText), 120);
        this.sessionW = sessionW;
        x += 10 + sessionW + 8;

        logsW = 44; logsH = 12;
        logsX = panelX + panelW - 6 - logsW;
        logsY = barTop + 3;

        if (drawerOpen) {
            drawerH = Mth.clamp(lh / 3, MIN_DRAWER_H, MAX_DRAWER_H);
            drawerX = panelX;
            drawerY = barTop - drawerH;
            drawerW = panelW;
        } else {
            drawerH = 0;
        }
        copyH = 12; clearH = 12;
        copyW = fontSafeWidth("Copy Stacktrace") + 10;
        clearW = fontSafeWidth("Clear Logs") + 10;
        copyX = panelX + panelW - 6 - copyW;
        clearX = copyX - clearW - 6;
        copyY = clearY = drawerY + 3;
    }

    private Font lastFont;
    private int badgeW, sessionW;

    private int fontSafeWidth(String text) {
        Font f = lastFont != null ? lastFont : Minecraft.getInstance().font;
        return f.width(text);
    }

    private static Badge badgeFor() {
        if (WebSocketBridge.connected()) {
            return new Badge(0xFF67D184, "Connected (ws://localhost:" + WebSocketBridge.port() + ")", 0xFF9FD0A2);
        }
        if (WebSocketBridge.active()) {
            return new Badge(0xFFFFC857, "Waiting for VS Code (retrying...)", 0xFFE8D9A8);
        }
        return new Badge(0xFF8A93A3, "Disconnected / Client Stopped", 0xFF9AA7B8);
    }

    private String truncate(String text, int maxWidth) {
        Font f = lastFont != null ? lastFont : Minecraft.getInstance().font;
        if (f.width(text) <= maxWidth) return text;
        String t = text;
        while (t.length() > 1 && f.width(t + "..") > maxWidth) t = t.substring(0, t.length() - 1);
        return t + "..";
    }

    // ------------------------------------------------------------------ render

    public void render(GuiGraphics g, int mouseX, int mouseY, Font font, int panelX, int panelY, int panelW, int panelH) {
        lastFont = font;
        layout(panelX, panelY, panelW, panelH);
        int cy = barTop + BAR_H / 2;

        g.fill(lx, barTop, lx + lw, ly + lh, C_BAR_BG);
        g.fill(lx, barTop, lx + lw, barTop + 1, C_DIVIDER);

        int x = lx + 6;

        // Status badge
        Badge badge = badgeFor();
        g.fill(x, cy - 3, x + 6, cy + 3, badge.dotColor());
        x += 10;
        g.drawString(font, truncate(badge.text(), 190), x, cy - 3, badge.textColor(), false);
        x += badgeW + 10;

        // Connect/Disconnect toggle
        boolean startHover = inRect(mouseX, mouseY, startX, startY, startW, startH);
        boolean running = WebSocketBridge.active();
        g.fill(startX, startY, startX + startW, startY + startH, startHover ? C_BTN_HOVER : C_BTN);
        g.renderOutline(startX, startY, startW, startH, startHover ? 0xFF4FC1FF : C_BTN_BORDER);
        String label = running ? "Disconnect" : "Connect";
        int lw2 = font.width(label);
        g.drawString(font, label, startX + (startW - lw2) / 2, startY + 2, running ? 0xFFFF9F9F : 0xFF9FD0A2, false);
        if (startHover) {
            String tip = running
                ? "Disconnect from VS Code and stop retrying."
                : "Connect to the ccLink extension on the port shown next to it. Pairing is automatic - no UUID setup needed. After 3 failed attempts the bridge stops; press again to retry.";
            tooltip(g, mouseX, mouseY, tip);
        }

        // Port input (locked while the server is active, spec 4.2)
        boolean portHover = inRect(mouseX, mouseY, portX, portY, portW, portH);
        boolean locked = running;
        g.fill(portX, portY, portX + portW, portY + portH, 0xFF1A2233);
        g.renderOutline(portX, portY, portW, portH, portFocused ? 0xFF4FC1FF : C_BTN_BORDER);
        String shown;
        int portColor;
        if (portFocused) {
            shown = portDraft;
            portColor = 0xFFE2E8F4;
        } else if (locked) {
            shown = String.valueOf(WebSocketBridge.port());
            portColor = 0xFF7A8494;
        } else if (System.currentTimeMillis() < portErrorUntil) {
            shown = "invalid";
            portColor = 0xFFFF6B6B;
        } else {
            shown = String.valueOf(CideClientConfig.DEFAULT_PORT.get());
            portColor = 0xFF9AA7B8;
        }
        if (portFocused && shown.isEmpty()) shown = "port";
        int shownW = font.width(shown);
        if (shownW > portW - 6) shown = truncate(shown, portW - 12);
        g.drawString(font, shown, portX + 4, portY + 2, portColor, false);
        if (portFocused && (System.currentTimeMillis() / 400) % 2 == 0) {
            int caretX = portX + 4 + font.width(shown) + 1;
            if (caretX < portX + portW - 2) g.fill(caretX, portY + 2, caretX + 1, portY + portH - 2, 0xFF4FC1FF);
        }
        if (portHover && locked) tooltip(g, mouseX, mouseY, "Disconnect before editing the port.");

        // Session badge (phase 4 updates the text)
        int sessionX = portX + portW + 8;
        g.fill(sessionX, cy - 3, sessionX + 6, cy + 3, sessionActive ? 0xFF67D184 : 0xFFFF6B6B);
        g.drawString(font, truncate(sessionText, 120), sessionX + 10, cy - 3, sessionActive ? 0xFF9FD0A2 : 0xFFB8C0CC, false);

        // Logs toggle
        boolean logsHover = inRect(mouseX, mouseY, logsX, logsY, logsW, logsH);
        g.fill(logsX, logsY, logsX + logsW, logsY + logsH, logsHover ? C_BTN_HOVER : C_BTN);
        g.renderOutline(logsX, logsY, logsW, logsH, logsHover ? 0xFF4FC1FF : C_BTN_BORDER);
        String logsLabel = drawerOpen ? "Logs -" : "Logs +";
        int logsLabelW = font.width(logsLabel);
        g.drawString(font, logsLabel, logsX + (logsW - logsLabelW) / 2, logsY + 2, 0xFFC7D0DE, false);

        renderDrawer(g, font, mouseX, mouseY);
    }

    private void renderDrawer(GuiGraphics g, Font font, int mouseX, int mouseY) {
        if (!drawerOpen) return;

        g.fill(drawerX, drawerY, drawerX + drawerW, barTop, C_DRAWER_BG);
        g.fill(drawerX, drawerY, drawerX + drawerW, drawerY + 1, C_DIVIDER);
        g.fill(drawerX, drawerY + 1, drawerX + drawerW, drawerY + 15, C_DRAWER_HEAD);
        g.drawString(font, "ccLink log", drawerX + 6, drawerY + 4, 0xFF7A8EA8, false);

        boolean clearHover = inRect(mouseX, mouseY, clearX, clearY, clearW, clearH);
        g.fill(clearX, clearY, clearX + clearW, clearY + clearH, clearHover ? C_BTN_HOVER : C_BTN);
        g.renderOutline(clearX, clearY, clearW, clearH, clearHover ? 0xFF4FC1FF : C_BTN_BORDER);
        String clearLabel = "Clear Logs";
        g.drawString(font, clearLabel, clearX + (clearW - font.width(clearLabel)) / 2, clearY + 2, 0xFFC7D0DE, false);

        boolean copyHover = inRect(mouseX, mouseY, copyX, copyY, copyW, copyH);
        g.fill(copyX, copyY, copyX + copyW, copyY + copyH, copyHover ? C_BTN_HOVER : C_BTN);
        g.renderOutline(copyX, copyY, copyW, copyH, copyHover ? 0xFF4FC1FF : C_BTN_BORDER);
        boolean justCopied = System.currentTimeMillis() < copiedUntil;
        String copyLabel = justCopied ? "Copied" : "Copy Stacktrace";
        g.drawString(font, copyLabel, copyX + (copyW - font.width(copyLabel)) / 2, copyY + 2, justCopied ? 0xFF9FD0A2 : 0xFFC7D0DE, false);

        // Scrollable body
        int bodyY = drawerY + 17;
        int bodyH = drawerH - 17 - 2;
        int visible = Math.max(1, bodyH / LINE_H);

        List<LogLine> lines = new ArrayList<>();
        for (BridgeLog.Entry e : BridgeLog.snapshot()) {
            String[] parts = e.message().split("\n", -1);
            for (int i = 0; i < parts.length; i++) {
                String text = (i == 0 ? "[" + e.timestamp() + "] [" + e.level() + "] " : "    ") + parts[i];
                int color = i == 0 ? levelColor(e.level()) : 0xFF7A8EA8;
                lines.add(new LogLine(color, text));
            }
        }

        int maxScroll = Math.max(0, lines.size() - visible);
        drawerScroll = Mth.clamp(drawerScroll, 0, maxScroll);
        // drawerScroll counts lines above the bottom edge, so scrolling up moves the window
        // backwards through the list (was: + drawerScroll, which inverted the scroll direction)
        int start = Math.max(0, lines.size() - visible - drawerScroll);
        for (int i = start; i < lines.size(); i++) {
            LogLine line = lines.get(i);
            int y = bodyY + (i - start) * LINE_H;
            if (y > barTop - LINE_H) break;
            g.drawString(font, truncate(line.text(), drawerW - 10), drawerX + 5, y, line.color(), false);
        }
    }

    private static int levelColor(BridgeLog.Level level) {
        switch (level) {
            case SYNC: return 0xFF67D184;
            case PERIPHERAL: return 0xFF4FC1FF;
            case WARN: return 0xFFFFC857;
            case ERROR: return 0xFFFF6B6B;
            default: return 0xFF9AA7B8;
        }
    }

    private static void tooltip(GuiGraphics g, int mouseX, int mouseY, String text) {
        List<Component> lines = List.of(Component.literal(text).withStyle(Style.EMPTY.withColor(0xFFFFFF)));
        g.renderComponentTooltip(Minecraft.getInstance().font, lines, mouseX, mouseY);
    }

    private static boolean inRect(int x, int y, int rx, int ry, int rw, int rh) {
        return x >= rx && x < rx + rw && y >= ry && y < ry + rh;
    }

    // ------------------------------------------------------------------ input

    public boolean mouseClicked(double mx, double my, int btn, int panelX, int panelY, int panelW, int panelH) {
        if (btn != 0) return false;
        layout(panelX, panelY, panelW, panelH);
        boolean inDrawer = drawerOpen && mx >= drawerX && mx < drawerX + drawerW && my >= drawerY && my < barTop;
        if (inDrawer) {
            if (inRect((int) mx, (int) my, clearX, clearY, clearW, clearH)) {
                BridgeLog.clear();
                return true;
            }
            if (inRect((int) mx, (int) my, copyX, copyY, copyW, copyH)) {
                copyStacktraces();
                return true;
            }
            return true; // body clicks are consumed, not forwarded to the editor
        }
        boolean inBar = mx >= lx && mx < lx + lw && my >= barTop && my < ly + lh;
        if (inBar) {
            if (inRect((int) mx, (int) my, logsX, logsY, logsW, logsH)) {
                drawerOpen = !drawerOpen;
                if (drawerOpen) drawerScroll = 0;
                return true;
            }
            if (inRect((int) mx, (int) my, startX, startY, startW, startH)) {
                portFocused = false;
                if (WebSocketBridge.active()) {
                    WebSocketBridge.stop();
                } else {
                    startFromDraft();
                }
                return true;
            }
            if (inRect((int) mx, (int) my, portX, portY, portW, portH)) {
                if (!WebSocketBridge.active()) {
                    portFocused = true;
                    portDraft = String.valueOf(effectivePort());
                }
                return true;
            }
            portFocused = false; // click on the rest of the bar blurs the port field
            return true;
        }
        return false;
    }

    /** Call when a click landed outside the bridge UI; blurs the port field. */
    public void handleClickOutside() {
        portFocused = false;
    }

    public boolean mouseScrolled(double mx, double my, double amount, int panelX, int panelY, int panelW, int panelH) {
        if (!drawerOpen) return false;
        layout(panelX, panelY, panelW, panelH);
        if (mx >= drawerX && mx < drawerX + drawerW && my >= drawerY && my < barTop) {
            drawerScroll += amount > 0 ? 4 : -4;
            return true;
        }
        return false;
    }

    public boolean keyPressed(int key) {
        if (portFocused) {
            if (key >= GLFW.GLFW_KEY_0 && key <= GLFW.GLFW_KEY_9) {
                appendDigit((char) ('0' + key - GLFW.GLFW_KEY_0));
                return true;
            }
            if (key == GLFW.GLFW_KEY_BACKSPACE) {
                if (!portDraft.isEmpty()) portDraft = portDraft.substring(0, portDraft.length() - 1);
                return true;
            }
            if (key == GLFW.GLFW_KEY_DELETE) {
                portDraft = "";
                return true;
            }
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                startFromDraft();
                return true;
            }
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                portFocused = false;
                return true;
            }
            return true; // swallow the rest while the port field has focus
        }
        if (drawerOpen && key == GLFW.GLFW_KEY_ESCAPE) {
            drawerOpen = false;
            return true;
        }
        return false;
    }

    public boolean charTyped(char c) {
        if (portFocused && Character.isDigit(c)) {
            appendDigit(c);
            return true;
        }
        return false;
    }

    private void appendDigit(char c) {
        if (portDraft.length() < 5) portDraft += c;
    }

    private static int effectivePort() {
        int p = WebSocketBridge.port();
        return p > 0 ? p : CideClientConfig.DEFAULT_PORT.get();
    }

    private void startFromDraft() {
        int p;
        try {
            p = Integer.parseInt(portDraft.trim());
        } catch (NumberFormatException e) {
            p = -1;
        }
        portFocused = false;
        if (p < 1 || p > 65535) {
            portErrorUntil = System.currentTimeMillis() + 1500;
            return;
        }
        if (!WebSocketBridge.start(p)) {
            portErrorUntil = System.currentTimeMillis() + 1500;
        }
    }

    private void copyStacktraces() {
        StringBuilder sb = new StringBuilder();
        for (BridgeLog.Entry e : BridgeLog.snapshot()) {
            if (e.level() == BridgeLog.Level.ERROR) {
                sb.append('[').append(e.timestamp()).append("] ").append(e.message()).append('\n');
            }
        }
        if (sb.length() == 0) sb.append("(no errors logged)");
        Minecraft.getInstance().keyboardHandler.setClipboard(sb.toString());
        copiedUntil = System.currentTimeMillis() + 1500;
    }

    public void close() {
        portFocused = false;
        drawerOpen = false;
    }
}
