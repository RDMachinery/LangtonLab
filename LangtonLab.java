/*
 * LangtonLab.java - an interactive laboratory for Chris Langton's lambda
 * parameter and the "edge of chaos" in cellular automata.
 *
 * Run:   java LangtonLab.java        (Java 11+, no dependencies)
 *
 * THE SYSTEM
 *   A one-dimensional cellular automaton: a row of cells, each in one of
 *   K = 4 states (0 is the "quiescent" or resting state). At every tick each
 *   cell looks at itself and two neighbours on either side (5 cells) and
 *   looks up its next state in a rule table with 4^5 = 1024 entries.
 *   Time runs down the screen: each row is one generation.
 *
 * LAMBDA (λ)
 *   λ is the fraction of rule-table entries that lead to a non-quiescent
 *   state. λ = 0: everything dies. λ = 0.75 = 1 − 1/K: every state is equally
 *   likely, the most random rule possible.
 *
 *   The slider uses Langton's "table walk-through" method: each rule family
 *   has a fixed random order in which entries are switched on, so moving the
 *   slider changes the rule a few entries at a time and you can watch one
 *   rule melt continuously from frozen order into chaos.
 *   Rules are reflection-symmetric (left and right treated alike) and the
 *   all-zero neighbourhood always stays zero.
 *
 * MEASURES (as in Langton 1990, "Computation at the Edge of Chaos")
 *   H  - average single-cell entropy, normalised to 0..1. How varied
 *        the cells' states are.
 *   MI - mutual information, in bits, between a cell's state and its own
 *        state one tick later. How much each moment "remembers" the last.
 *   Ordered rules have low H and low MI; chaotic rules have high H but low
 *   MI. Complex rules sit in between, where MI peaks. "Sweep λ" reproduces
 *   Langton's two famous plots: H against λ, and MI against H.
 *
 * THINGS TO TRY
 *   Drag λ slowly upward from 0.10. Patterns die out, then freeze into
 *   stripes, then form periodic structures, then (somewhere between about
 *   0.2 and 0.55, depending on the rule family) long-lived interacting
 *   structures appear, and finally everything boils into chaos.
 *   "New table" deals a different rule family; each has its own critical λ.
 *   Some favourites (family, λ): #54 0.465, #22 0.266, #32 0.415, #34 0.185.
 */

import javax.swing.*;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

public class LangtonLab extends JFrame {

    static final int K = 4;                          // states per cell
    static final int R = 2;                          // neighbourhood radius
    static final int TABLE = 1024;                   // K^(2R+1) neighbourhoods
    static final double LAMBDA_MAX = 1 - 1.0 / K;    // 0.75

    static final Color BG     = new Color(0x12, 0x14, 0x1a);
    static final Color PANEL  = new Color(0x1b, 0x1e, 0x26);
    static final Color GRID   = new Color(0x34, 0x39, 0x46);
    static final Color TEXT   = new Color(0xc9, 0xcf, 0xdb);
    static final Color ACCENT = new Color(0xff, 0xa6, 0x3d);
    static final int[] PALETTE = {0x12141a, 0xffa63d, 0x3dd6c6, 0xb48cff};

    // =====================================================================
    //  Model
    // =====================================================================

    /** A family of rule tables, parameterised by λ via Langton's table walk-through. */
    static final class RuleFamily {
        final long seed;
        final List<int[]> classes = new ArrayList<>();  // mirror-image neighbourhood pairs
        final int[] values;
        final int[] table = new int[TABLE];
        double lambda;

        RuleFamily(long seed) {
            this.seed = seed;
            boolean[] seen = new boolean[TABLE];
            seen[0] = true;                       // 00000 -> 0, always (quiescence)
            for (int i = 1; i < TABLE; i++) {
                if (seen[i]) continue;
                int j = reverse(i);
                seen[i] = seen[j] = true;
                classes.add(i == j ? new int[]{i} : new int[]{i, j});
            }
            Random rng = new Random(seed);
            Collections.shuffle(classes, rng);
            values = new int[classes.size()];
            for (int c = 0; c < values.length; c++) values[c] = 1 + rng.nextInt(K - 1);
        }

        static int reverse(int idx) {
            int r = 0;
            for (int k = 0; k < 2 * R + 1; k++) { r = r * K + idx % K; idx /= K; }
            return r;
        }

        /** Switch on entries, in this family's fixed order, until λ reaches the target. */
        void setLambda(double target) {
            Arrays.fill(table, 0);
            int need = (int) Math.round(target * TABLE), have = 0;
            for (int c = 0; c < classes.size() && have < need; c++) {
                for (int idx : classes.get(c)) table[idx] = values[c];
                have += classes.get(c).length;
            }
            lambda = have / (double) TABLE;
        }
    }

    /** A ring of cells, updated synchronously. */
    static final class Automaton {
        final int w;
        byte[] cur, nxt;
        final Random rng;

        Automaton(int w, long seed) {
            this.w = w;
            cur = new byte[w];
            nxt = new byte[w];
            rng = new Random(seed);
        }

        void randomize(boolean blockOnly) {
            Arrays.fill(cur, (byte) 0);
            int a = blockOnly ? w * 3 / 8 : 0, b = blockOnly ? w * 5 / 8 : w;
            for (int x = a; x < b; x++) cur[x] = (byte) rng.nextInt(K);
        }

        void step(int[] table) {
            for (int x = 0; x < w; x++) {
                int idx = 0;
                for (int d = -R; d <= R; d++) idx = idx * K + cur[(x + d + w) % w];
                nxt[x] = (byte) table[idx];
            }
            byte[] t = cur; cur = nxt; nxt = t;
        }
    }

    /** Entropy and one-step mutual information, accumulated over cell pairs (now, next). */
    static final class Stats {
        final long[][] joint = new long[K][K];
        long n;

        void add(byte[] now, byte[] next) {
            for (int x = 0; x < now.length; x++) joint[now[x]][next[x]]++;
            n += now.length;
        }

        static double h(double p) { return p > 0 ? -p * Math.log(p) / Math.log(2) : 0; }

        /** Single-cell entropy, normalised so 1 = all K states equally common. */
        double entropy() {
            if (n == 0) return 0;
            double H = 0;
            for (int b = 0; b < K; b++) {
                long c = 0;
                for (int a = 0; a < K; a++) c += joint[a][b];
                H += h(c / (double) n);
            }
            return H / (Math.log(K) / Math.log(2));
        }

        /** I(now; next) in bits. */
        double mutualInfo() {
            if (n == 0) return 0;
            double ha = 0, hb = 0, hab = 0;
            for (int a = 0; a < K; a++) {
                long ca = 0, cb = 0;
                for (int b = 0; b < K; b++) {
                    ca += joint[a][b];
                    cb += joint[b][a];
                    hab += h(joint[a][b] / (double) n);
                }
                ha += h(ca / (double) n);
                hb += h(cb / (double) n);
            }
            return Math.max(0, ha + hb - hab);
        }
    }

    /** Ring buffer of recent generations, for drawing and measuring. */
    static final class History {
        final byte[][] rows;
        int head, count;

        History(int h, int w) { rows = new byte[h][w]; }

        void push(byte[] r) {
            System.arraycopy(r, 0, rows[head], 0, r.length);
            head = (head + 1) % rows.length;
            count = Math.min(count + 1, rows.length);
        }

        /** k = 0 is the oldest stored row, count − 1 the newest. */
        byte[] get(int k) { return rows[(head - count + k + rows.length) % rows.length]; }

        void clear() { head = 0; count = 0; }

        Stats stats(int window) {
            Stats s = new Stats();
            int start = Math.max(0, count - window - 1);
            for (int k = start + 1; k < count; k++) s.add(get(k - 1), get(k));
            return s;
        }
    }

    // =====================================================================
    //  Views
    // =====================================================================

    static void smooth(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    }

    /** The space-time diagram: each row a generation, time running downward. */
    static final class SpaceTimeView extends JPanel {
        final History hist;
        final int w, h;
        final BufferedImage img;
        final int[] px;

        SpaceTimeView(History hist, int w) {
            this.hist = hist;
            this.w = w;
            this.h = hist.rows.length;
            setBackground(BG);
            setPreferredSize(new Dimension(700, 680));
            img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            px = new int[w * h];
        }

        @Override protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0;
            Arrays.fill(px, PALETTE[0]);
            for (int k = 0; k < hist.count; k++) {
                byte[] row = hist.get(k);
                int off = k * w;
                for (int x = 0; x < w; x++) px[off + x] = PALETTE[row[x]];
            }
            img.setRGB(0, 0, w, h, px, 0, w);

            int W = getWidth(), H = getHeight();
            double scale = Math.min((W - 24) / (double) w, (H - 44) / (double) h);
            int dw = (int) (w * scale), dh = (int) (h * scale);
            int x0 = (W - dw) / 2, y0 = (H - 32 - dh) / 2 + 4;
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(img, x0, y0, dw, dh, null);
            g.setColor(GRID);
            g.drawRect(x0 - 1, y0 - 1, dw + 1, dh + 1);

            smooth(g);
            g.setColor(TEXT);
            g.setFont(getFont().deriveFont(12f));
            g.drawString("each row is one generation; time runs downward    black = resting state (0)",
                    12, H - 12);
        }
    }

    /** Scatter plot used for both of Langton's diagrams. */
    static final class ScatterPlot extends JPanel {
        final String title, xLabel;
        final double xMax;
        final boolean autoY;
        double yMax;
        final List<double[]> pts = new ArrayList<>();
        Supplier<double[]> live;      // the running automaton's (x, y), drawn as an orange ring
        DoubleSupplier marker;        // optional vertical line
        String emptyHint;
        int xTicks = 4;

        ScatterPlot(String title, String xLabel, double xMax, double yMax, boolean autoY) {
            this.title = title; this.xLabel = xLabel;
            this.xMax = xMax; this.yMax = yMax; this.autoY = autoY;
            setBackground(PANEL);
            setPreferredSize(new Dimension(380, 210));
        }

        void clear() { pts.clear(); repaint(); }

        void add(double x, double y) {
            pts.add(new double[]{x, y});
            if (autoY && y * 1.15 > yMax) yMax = Math.ceil(y * 1.15 * 10) / 10.0;
            repaint();
        }

        @Override protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0;
            smooth(g);
            int w = getWidth(), h = getHeight();
            int L = 38, T = 24, Rm = 12, B = 30;
            int pw = w - L - Rm, ph = h - T - B;

            g.setFont(g.getFont().deriveFont(11f));
            for (int k = 0; k <= 4; k++) {
                int y = T + ph * k / 4;
                g.setColor(GRID);
                g.drawLine(L, y, L + pw, y);
                g.setColor(TEXT);
                g.drawString(String.format("%.2f", yMax * (1 - k / 4.0)), 4, y + 4);
            }
            for (int k = 0; k <= xTicks; k++) {
                int x = L + pw * k / xTicks;
                g.drawString(String.format("%.2f", xMax * k / xTicks), x - 12, T + ph + 14);
            }
            g.setColor(GRID);
            g.drawRect(L, T, pw, ph);
            g.setColor(TEXT);
            g.drawString(xLabel, L + pw - g.getFontMetrics().stringWidth(xLabel), T + ph + 27);
            g.setFont(g.getFont().deriveFont(Font.BOLD, 12f));
            g.drawString(title, L, T - 8);
            g.setFont(g.getFont().deriveFont(Font.PLAIN, 11f));

            if (marker != null) {
                double x = L + pw * Math.min(1, marker.getAsDouble() / xMax);
                g.setColor(new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(), 120));
                g.setStroke(new BasicStroke(1.2f));
                g.draw(new Line2D.Double(x, T, x, T + ph));
            }

            g.setColor(new Color(255, 255, 255, 150));
            for (double[] q : pts) {
                double x = L + pw * q[0] / xMax, y = T + ph * (1 - q[1] / yMax);
                g.fill(new Ellipse2D.Double(x - 2, y - 2, 4, 4));
            }

            if (pts.isEmpty() && emptyHint != null) {
                g.setColor(TEXT);
                g.drawString(emptyHint, L + 16, T + ph / 2);
            }

            if (live != null) {
                double[] p = live.get();
                if (p != null) {
                    double x = L + pw * Math.min(1, p[0] / xMax);
                    double y = T + ph * (1 - Math.min(1, p[1] / yMax));
                    g.setColor(ACCENT);
                    g.setStroke(new BasicStroke(2f));
                    g.draw(new Ellipse2D.Double(x - 5, y - 5, 10, 10));
                }
            }
        }
    }

    /** A slider with a live numeric readout. */
    static final class Knob extends JPanel {
        final JSlider s;
        final double scale;

        Knob(String name, int min, int max, int val, double scale, String fmt) {
            super(new BorderLayout(6, 0));
            this.scale = scale;
            setOpaque(false);
            JLabel title = new JLabel(name);
            title.setForeground(TEXT);
            title.setPreferredSize(new Dimension(110, 20));
            JLabel readout = new JLabel();
            readout.setForeground(ACCENT);
            readout.setPreferredSize(new Dimension(48, 20));
            s = new JSlider(min, max, val);
            s.setOpaque(false);
            Runnable upd = () -> readout.setText(String.format(fmt, value()));
            s.addChangeListener(e -> upd.run());
            upd.run();
            add(title, BorderLayout.WEST);
            add(s, BorderLayout.CENTER);
            add(readout, BorderLayout.EAST);
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        }

        double value() { return s.getValue() / scale; }
    }

    // =====================================================================
    //  Application
    // =====================================================================

    static final int WIDTH = 340, ROWS = 320, WINDOW = 200;

    RuleFamily family = new RuleFamily(54);
    final Automaton ca = new Automaton(WIDTH, 42);
    final History history = new History(ROWS, WIDTH);
    final SpaceTimeView view = new SpaceTimeView(history, WIDTH);

    final Knob lambdaKnob = new Knob("Lambda λ", 0, 750, 465, 1000, "%.3f");
    final Knob speedKnob  = new Knob("Steps / frame", 1, 10, 2, 1, "%.0f");
    final JSpinner familyBox = new JSpinner(new SpinnerNumberModel(54, 1, 99999, 1));
    final JComboBox<String> start = new JComboBox<>(new String[]{
            "Start: random row", "Start: random block in the middle"});

    final ScatterPlot hPlot = new ScatterPlot("entropy H vs λ", "λ", LAMBDA_MAX, 1.0, false);
    final ScatterPlot miPlot = new ScatterPlot("mutual information vs H", "H", 1.0, 0.3, true);
    final JLabel status1 = new JLabel(" "), status2 = new JLabel(" ");
    final JButton sweepBtn = new JButton("Sweep λ");
    final JButton pauseBtn = new JButton("Pause");

    boolean paused;
    double liveH, liveMI;

    LangtonLab() {
        super("Langton Lab - the edge of chaos");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        getContentPane().setBackground(BG);
        setLayout(new BorderLayout());
        add(view, BorderLayout.CENTER);

        hPlot.xTicks = 3;
        hPlot.marker = () -> family.lambda;
        hPlot.live = () -> history.count > 20 ? new double[]{family.lambda, liveH} : null;
        hPlot.emptyHint = "Press \"Sweep λ\" to map the transition";
        miPlot.live = () -> history.count > 20 ? new double[]{liveH, liveMI} : null;

        JPanel side = new JPanel();
        side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
        side.setBackground(BG);
        side.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        side.setPreferredSize(new Dimension(400, 680));

        JPanel buttons = new JPanel(new GridLayout(2, 2, 6, 6));
        buttons.setOpaque(false);
        buttons.setMaximumSize(new Dimension(Integer.MAX_VALUE, 62));
        JButton restart = new JButton("Restart");
        JButton newTable = new JButton("New table");
        restart.addActionListener(e -> restart());
        newTable.addActionListener(e -> familyBox.setValue((Integer) familyBox.getValue() + 1));
        familyBox.addChangeListener(e -> {
            family = new RuleFamily((Integer) familyBox.getValue());
            family.setLambda(lambdaKnob.value());
            restart();
        });
        JPanel famRow = new JPanel(new BorderLayout(6, 0));
        famRow.setOpaque(false);
        famRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        JLabel famLabel = new JLabel("Rule family #");
        famLabel.setForeground(TEXT);
        famLabel.setPreferredSize(new Dimension(110, 20));
        famRow.add(famLabel, BorderLayout.WEST);
        famRow.add(familyBox, BorderLayout.CENTER);
        pauseBtn.addActionListener(e -> { paused = !paused; pauseBtn.setText(paused ? "Run" : "Pause"); });
        sweepBtn.addActionListener(e -> runSweep());
        buttons.add(restart);
        buttons.add(newTable);
        buttons.add(pauseBtn);
        buttons.add(sweepBtn);

        start.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        start.addActionListener(e -> restart());
        lambdaKnob.s.addChangeListener(e -> family.setLambda(lambdaKnob.value()));
        family.setLambda(lambdaKnob.value());

        for (JLabel l : new JLabel[]{status1, status2}) {
            l.setForeground(TEXT);
            l.setFont(l.getFont().deriveFont(13f));
        }

        JComponent[] items = {buttons, start, famRow, lambdaKnob, speedKnob, status1, status2, hPlot, miPlot};
        for (JComponent c : items) c.setAlignmentX(LEFT_ALIGNMENT);
        side.add(buttons);
        side.add(Box.createVerticalStrut(8));
        side.add(start);
        side.add(Box.createVerticalStrut(6));
        side.add(famRow);
        side.add(Box.createVerticalStrut(8));
        side.add(lambdaKnob);
        side.add(speedKnob);
        side.add(Box.createVerticalStrut(8));
        side.add(status1);
        side.add(Box.createVerticalStrut(2));
        side.add(status2);
        side.add(Box.createVerticalStrut(8));
        side.add(hPlot);
        side.add(Box.createVerticalStrut(8));
        side.add(miPlot);
        add(side, BorderLayout.EAST);

        restart();
        new Timer(30, e -> tick()).start();
        pack();
        setLocationRelativeTo(null);
    }

    void restart() {
        ca.randomize(start.getSelectedIndex() == 1);
        history.clear();
        history.push(ca.cur);
    }

    void tick() {
        if (!paused) {
            int steps = (int) speedKnob.value();
            for (int s = 0; s < steps; s++) {
                ca.step(family.table);
                history.push(ca.cur);
            }
        }
        Stats st = history.stats(WINDOW);
        liveH = st.entropy();
        liveMI = st.mutualInfo();
        status1.setText(String.format("λ = %.3f    rule family #%d", family.lambda, family.seed));
        status2.setText(String.format("H = %.3f    MI = %.3f bits", liveH, liveMI));
        view.repaint();
        hPlot.repaint();
        miPlot.repaint();
    }

    /** Langton's experiment: many rule families, each walked from λ = 0 to λ = 0.75. */
    void runSweep() {
        sweepBtn.setEnabled(false);
        hPlot.clear();
        miPlot.clear();
        new SwingWorker<Void, double[]>() {
            @Override protected Void doInBackground() {
                int families = 8, steps = 60, w = 200;
                for (int f = 0; f < families; f++) {
                    RuleFamily fam = new RuleFamily(1000 + f);
                    Automaton a = new Automaton(w, 500 + f);
                    byte[] prev = new byte[w];
                    for (int j = 0; j <= steps; j++) {
                        fam.setLambda(LAMBDA_MAX * j / steps);
                        a.randomize(false);
                        for (int s = 0; s < 300; s++) a.step(fam.table);
                        Stats st = new Stats();
                        for (int s = 0; s < 200; s++) {
                            System.arraycopy(a.cur, 0, prev, 0, w);
                            a.step(fam.table);
                            st.add(prev, a.cur);
                        }
                        publish(new double[]{fam.lambda, st.entropy(), st.mutualInfo()});
                    }
                }
                return null;
            }
            @Override protected void process(List<double[]> chunk) {
                for (double[] q : chunk) {
                    hPlot.add(q[0], q[1]);
                    miPlot.add(q[1], q[2]);
                }
            }
            @Override protected void done() { sweepBtn.setEnabled(true); }
        }.execute();
    }

    public static void main(String[] args) {
        System.setProperty("apple.awt.application.name", "Langton Lab");
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName()); }
            catch (Exception ignored) { }
            new LangtonLab().setVisible(true);
        });
    }
}
