package com.knative.fluss.broker.tui;

import com.knative.fluss.broker.tui.data.PanelDataProvider;
import com.knative.fluss.broker.tui.model.*;
import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Layout;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.style.Overflow;
import dev.tamboui.style.Style;
import dev.tamboui.text.Text;
import dev.tamboui.terminal.Frame;
import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.*;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.BorderType;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.paragraph.Paragraph;
import dev.tamboui.widgets.table.*;
import java.time.Duration;
import java.util.*;
import static com.knative.fluss.broker.tui.FormatUtils.*;

public class BrokerDashboardApp {
    private static final String HELP_TEXT = """
        Usage: broker-dashboard [options]
          --coordinator HOST:PORT  Fluss coordinator endpoint (default: localhost:9123)
          --polaris URI           Polaris REST API URI (default: http://localhost:8181)
          --warehouse NAME         Iceberg warehouse name (default: fluss)
          --database DB            Database name (default: knative_default)
          --table TABLE            Table name (default: broker_default)
          --refresh MS             Refresh interval in ms (default: 2000)
          --help                   Show this help
        """;

    private String flussCoordinator = "fluss://localhost:9123";
    private String flussBootstrap = "localhost:9123";
    private String polarisUri = "http://localhost:8181";
    private String polarisWarehouse = "fluss";
    private String database = "knative_default";
    private String brokerTable = "broker_default";
    private int refreshMs = 2000;
    private PanelDataProvider dataProvider;
    private int selectedTab = 0;
    private int selectedRow = 0;

    public static void main(String[] args) throws Exception {
        new BrokerDashboardApp().parseArgs(args).run();
    }

    private BrokerDashboardApp parseArgs(String[] args) {
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--coordinator" -> flussCoordinator = "fluss://" + args[++i];
                case "--coordinator-host" -> {
                    flussCoordinator = "fluss://" + args[++i];
                    flussBootstrap = args[++i];
                }
                case "--polaris" -> polarisUri = args[++i];
                case "--warehouse" -> polarisWarehouse = args[++i];
                case "--database" -> database = args[++i];
                case "--table" -> brokerTable = args[++i];
                case "--refresh" -> refreshMs = Integer.parseInt(args[++i]);
                case "--help" -> {
                    System.out.println(HELP_TEXT);
                    System.exit(0);
                }
            }
        }
        return this;
    }

    private void run() throws Exception {
        dataProvider = new PanelDataProvider(
                flussCoordinator, polarisUri, polarisWarehouse,
                database, brokerTable, refreshMs);
        dataProvider.start();
        var config = TuiConfig.builder()
                .tickRate(Duration.ofMillis(refreshMs))
                .pollTimeout(Duration.ofMillis(100))
                .build();
        try (var tui = TuiRunner.create(config)) {
            tui.run(this::handleEvent, this::render);
        } finally { dataProvider.close(); }
    }

    private boolean handleEvent(Event event, TuiRunner runner) {
        if (event instanceof KeyEvent key) {
            if (key.isQuit()) { runner.quit(); return false; }
            if (key.isDown()) { selectedRow++; return true; }
            if (key.isUp()) { selectedRow = Math.max(0, selectedRow - 1); return true; }
            if (key.isFocusNext()) { selectedTab = (selectedTab + 1) % 5; selectedRow = 0; return true; }
            if (key.isChar('r')) { dataProvider.forceRefresh(); return true; }
            if (key.isChar('1')) { selectedTab = 0; selectedRow = 0; return true; }
            if (key.isChar('2')) { selectedTab = 1; selectedRow = 0; return true; }
            if (key.isChar('3')) { selectedTab = 2; selectedRow = 0; return true; }
            if (key.isChar('4')) { selectedTab = 3; selectedRow = 0; return true; }
            if (key.isChar('5')) { selectedTab = 4; selectedRow = 0; return true; }
        }
        return event instanceof TickEvent || event instanceof ResizeEvent;
    }

    private void render(Frame frame) {
        Rect area = frame.area();
        List<Rect> splits = Layout.vertical()
                .constraints(Constraint.length(3), Constraint.fill())
                .split(area);
        renderHeader(frame, splits.get(0));
        renderBody(frame, splits.get(1));
    }

    private void renderHeader(Frame frame, Rect area) {
        Color c = dataProvider.isFlussReachable() ? Color.GREEN : Color.RED;
        String[] tabs = {"Ingress", "Fluss", "Iceberg", "Dispatchers", "DLQ"};
        long ago = dataProvider.getLastRefreshTime() > 0
                ? (System.currentTimeMillis() - dataProvider.getLastRefreshTime()) / 1000 : -1;
        String err = dataProvider.getLastError();
        Block block = Block.builder()
                .title("Knative Fluss Broker \u2014 Live Dashboard")
                .borders(Borders.ALL)
                .borderType(BorderType.ROUNDED)
                .borderStyle(Style.EMPTY.fg(c))
                .build();
        block.render(area, frame.buffer());
        String line = String.format(" %s | %s.%s | Tab: %s | %ss ago%s",
                flussBootstrap, database, brokerTable, tabs[selectedTab],
                ago >= 0 ? ago : "\u2014",
                err != null ? " | ERR:" + truncate(err, 25) : "");
        Paragraph.builder()
                .text(Text.styled(line, Style.EMPTY.fg(Color.WHITE)))
                .build()
                .render(block.inner(area), frame.buffer());
    }

    private void renderBody(Frame frame, Rect area) {
        List<Rect> rows = Layout.vertical()
                .constraints(Constraint.percentage(50), Constraint.percentage(50))
                .split(area);
        List<Rect> top = Layout.horizontal()
                .constraints(Constraint.percentage(40), Constraint.percentage(30), Constraint.percentage(30))
                .split(rows.get(0));
        List<Rect> bot = Layout.horizontal()
                .constraints(Constraint.percentage(40), Constraint.percentage(60))
                .split(rows.get(1));
        renderIngress(frame, top.get(0));
        renderFluss(frame, top.get(1));
        renderIceberg(frame, top.get(2));
        renderDispatchers(frame, bot.get(0));
        renderDlq(frame, bot.get(1));
    }

    private void renderIngress(Frame frame, Rect area) {
        List<EventRecord> events = dataProvider.getRecentEvents();
        boolean foc = selectedTab == 0;
        Block blk = blk("Ingress \u2014 CloudEvents (" + events.size() + ")", foc ? Color.CYAN : Color.GRAY);
        blk.render(area, frame.buffer());
        Rect inner = blk.inner(area);
        if (events.isEmpty()) { empty(frame, inner, "No events yet"); return; }
        List<Rect> s = Layout.horizontal()
                .constraints(Constraint.percentage(55), Constraint.percentage(45))
                .split(inner);
        eventTable(frame, s.get(0), events, foc);
        eventDetail(frame, s.get(1), events);
    }

    private void renderFluss(Frame frame, Rect area) {
        TableStats stats = dataProvider.getTableStats();
        List<EventRecord> events = dataProvider.getRecentEvents();
        boolean foc = selectedTab == 1;
        Block blk = blk("Fluss Hot Storage", foc ? Color.CYAN : Color.GRAY);
        blk.render(area, frame.buffer());
        Rect inner = blk.inner(area);
        List<Rect> s = Layout.vertical()
                .constraints(Constraint.length(2), Constraint.fill())
                .split(inner);
        String statsTxt = String.format(" Rows: %d | Datalake: %s",
                stats.rowCount(),
                stats.datalakeEnabled() ? "ENABLED \u2713" : "disabled");
        Paragraph.builder()
                .text(Text.styled(statsTxt, Style.EMPTY.fg(Color.YELLOW)))
                .build()
                .render(s.get(0), frame.buffer());
        eventTable(frame, s.get(1), events, foc);
    }

    private void renderIceberg(Frame frame, Rect area) {
        TieringStatus ts = dataProvider.getTieringStatus();
        List<EventRecord> tiered = dataProvider.getTieredEvents();
        boolean foc = selectedTab == 2;
        Block blk = blk("Iceberg Tiering", foc ? Color.CYAN : Color.GRAY);
        blk.render(area, frame.buffer());
        Rect inner = blk.inner(area);
        List<Rect> s = Layout.vertical()
                .constraints(Constraint.length(1), Constraint.fill())
                .split(inner);
        Paragraph.builder()
                .text(Text.styled(String.format(" Rows in Fluss: %d", ts.tieredRowCount()),
                        Style.EMPTY.fg(Color.CYAN)))
                .build()
                .render(s.get(0), frame.buffer());
        if (tiered.isEmpty()) empty(frame, s.get(1), "No tiered data yet");
        else eventTable(frame, s.get(1), tiered, foc);
    }

    private void renderDispatchers(Frame frame, Rect area) {
        List<TriggerState> triggers = dataProvider.getTriggerStates();
        boolean foc = selectedTab == 3;
        Block blk = blk("Dispatchers (" + triggers.size() + " triggers)", foc ? Color.CYAN : Color.GRAY);
        blk.render(area, frame.buffer());
        Rect inner = blk.inner(area);
        if (triggers.isEmpty()) { empty(frame, inner, "No active triggers"); return; }
        List<Row> rows = new ArrayList<>();
        rows.add(Row.from("Trigger", "Subscriber", "Cursor", "Lanes", "Inflight", "Credits")
                .style(Style.EMPTY.bold().fg(Color.YELLOW)));
        for (TriggerState t : triggers)
            rows.add(Row.from(truncate(t.triggerKey(), 15), truncate(t.subscriberUri(), 25),
                    str(t.cursor()), str(t.laneCount()), str(t.inflightCount()), str(t.creditsAvailable())));
        TableState ts = new TableState();
        if (foc) ts.select(Math.min(selectedRow + 1, rows.size() - 1));
        Table.builder()
                .rows(rows)
                .widths(Constraint.percentage(18), Constraint.percentage(30),
                        Constraint.percentage(13), Constraint.percentage(13), Constraint.percentage(13), Constraint.fill())
                .build()
                .render(inner, frame.buffer(), ts);
    }

    private void renderDlq(Frame frame, Rect area) {
        List<DlqEntry> entries = dataProvider.getDlqEntries();
        boolean foc = selectedTab == 4;
        Color border = foc ? Color.CYAN : (entries.isEmpty() ? Color.GRAY : Color.RED);
        Block blk = blk("Dead Letter Queue (" + entries.size() + ")", border);
        blk.render(area, frame.buffer());
        Rect inner = blk.inner(area);
        if (entries.isEmpty()) { empty(frame, inner, "No dead-lettered events"); return; }
        List<Rect> s = Layout.horizontal()
                .constraints(Constraint.percentage(50), Constraint.percentage(50))
                .split(inner);
        List<Row> rows = new ArrayList<>();
        rows.add(Row.from("Event ID", "Type", "Reason", "Attempts", "When")
                .style(Style.EMPTY.bold().fg(Color.RED)));
        for (DlqEntry d : entries)
            rows.add(Row.from(truncate(d.eventId(), 15), truncate(d.eventType(), 15),
                    truncate(d.dlqReason(), 15), str(d.dlqAttempts()), fmtTime(d.dlqTimestamp())));
        TableState ts = new TableState();
        if (foc) ts.select(Math.min(selectedRow + 1, rows.size() - 1));
        Table.builder()
                .rows(rows)
                .widths(Constraint.percentage(22), Constraint.percentage(22),
                        Constraint.percentage(22), Constraint.percentage(12), Constraint.fill())
                .build()
                .render(s.get(0), frame.buffer(), ts);
        int di = foc ? Math.min(selectedRow, entries.size() - 1) : 0;
        if (di >= 0) {
            DlqEntry sel = entries.get(di);
            String detail = String.format("Event: %s%nType: %s%nSource: %s%nReason: %s (attempt %s)%nError: %s%nDLQ'd: %s%n%n-- PAYLOAD --%n%s",
                    sel.eventId(), sel.eventType(), sel.eventSource(), sel.dlqReason(), str(sel.dlqAttempts()),
                    sel.dlqLastError() != null ? sel.dlqLastError() : "\u2014",
                    fmtDateTime(sel.dlqTimestamp()), prettyPrint(sel.dataContent()));
            Paragraph.builder()
                    .text(detail)
                    .overflow(Overflow.WRAP_WORD)
                    .block(Block.builder().title("DLQ Detail").borders(Borders.ALL)
                            .borderStyle(Style.EMPTY.fg(Color.RED)).build())
                    .build()
                    .render(s.get(1), frame.buffer());
        }
    }

    private void eventTable(Frame frame, Rect area, List<EventRecord> events, boolean foc) {
        if (events.isEmpty()) { empty(frame, area, "No events"); return; }
        List<Row> rows = new ArrayList<>();
        rows.add(Row.from("Event ID", "Type", "Source", "Data", "Time")
                .style(Style.EMPTY.bold().fg(Color.YELLOW)));
        for (EventRecord e : events)
            rows.add(Row.from(truncate(e.eventId(), 14), truncate(e.eventType(), 14),
                    truncate(e.eventSource(), 10), truncate(e.dataContent(), 25), fmtTime(e.ingestionTime())));
        TableState ts = new TableState();
        if (foc) ts.select(Math.min(selectedRow + 1, rows.size() - 1));
        Table.builder()
                .rows(rows)
                .widths(Constraint.percentage(18), Constraint.percentage(18),
                        Constraint.percentage(14), Constraint.percentage(35), Constraint.fill())
                .build()
                .render(area, frame.buffer(), ts);
    }

    private void eventDetail(Frame frame, Rect area, List<EventRecord> events) {
        if (events.isEmpty()) return;
        int idx = Math.min(selectedRow, events.size() - 1);
        if (idx < 0) return;
        EventRecord sel = events.get(idx);
        String detail = String.format("ID: %s%nSource: %s%nType: %s%nTime: %s%nCT: %s%nSchema: %s%nAttrs: %s%nIngested: %s%n%n-- DATA --%n%s",
                sel.eventId(), sel.eventSource(), sel.eventType(), fmtDateTime(sel.eventTime()),
                sel.contentType(), str(sel.schemaId()), formatAttributes(sel.attributes()),
                fmtDateTime(sel.ingestionTime()), prettyPrint(sel.dataContent()));
        Paragraph.builder()
                .text(detail)
                .overflow(Overflow.WRAP_WORD)
                .block(Block.builder().title("Selected Event").borders(Borders.ALL)
                        .borderStyle(Style.EMPTY.fg(Color.DARK_GRAY)).build())
                .build()
                .render(area, frame.buffer());
    }

    private Block blk(String title, Color borderColor) {
        return Block.builder()
                .title(title)
                .borders(Borders.ALL)
                .borderType(BorderType.ROUNDED)
                .borderStyle(Style.EMPTY.fg(borderColor))
                .build();
    }

    private void empty(Frame frame, Rect area, String msg) {
        Paragraph.builder()
                .text(Text.styled(msg, Style.EMPTY.fg(Color.DARK_GRAY)))
                .build()
                .render(area, frame.buffer());
    }
}
