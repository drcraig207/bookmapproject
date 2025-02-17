package my.indicators;

// Additional Java and Swing imports (similar to C#'s using)
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;

// Bookmap API and related imports
import velox.api.layer1.annotations.Layer1ApiVersion;
import velox.api.layer1.annotations.Layer1ApiVersionValue;
import velox.api.layer1.annotations.Layer1SimpleAttachable;
import velox.api.layer1.annotations.Layer1StrategyName;
import velox.api.layer1.annotations.Layer1Parameter;
import velox.api.layer1.data.InstrumentInfo;
import velox.api.layer1.layers.utils.OrderBook;
import velox.api.layer1.layers.utils.mbo.Order;
import velox.api.layer1.layers.utils.mbo.OrderBookMbo;
import velox.api.layer1.simplified.Api;
import velox.api.layer1.simplified.CustomModule;
import velox.api.layer1.simplified.CustomSettingsPanelProvider;
import velox.api.layer1.simplified.InitialState;
import velox.api.layer1.simplified.MarketByOrderDepthDataListener;
import velox.api.layer1.simplified.TimeListener;
import velox.api.layer1.simplified.TradeDataListener;
import velox.api.layer1.simplified.IndicatorModifiable;
import velox.gui.StrategyPanel;

/**
 * VolumeRangeIndicator demonstrates an indicator that:
 * - Uses user-configurable thresholds (via annotations).
 * - Registers a modifiable indicator with Bookmap.
 * - Processes trade events and time updates.
 * - Draws rectangles on the chart using built-in API functions.
 */
@Layer1SimpleAttachable
@Layer1StrategyName("Volume Range Indicator")
@Layer1ApiVersion(Layer1ApiVersionValue.VERSION2)
public class VolumeRangeIndicator implements CustomModule, TradeDataListener, TimeListener {

    // === User-configurable parameters (with default values) ===
    @Layer1Parameter(name = "Volume Threshold", step = 1000, minimum = 1000, maximum = 100000)
    private double volumeThreshold = 10000;   // e.g., 10000 units

    @Layer1Parameter(name = "Range Threshold", step = 0.1, minimum = 0.1, maximum = 5)
    private double rangeThreshold = 0.50;       // e.g., 0.50 in price terms for volume period

    @Layer1Parameter(name = "Activation Range", step = 0.1, minimum = 0.1, maximum = 5)
    private double activationRange = 0.20;      // additional move required for activation

    // === Internal state for current volume period ===
    private double cumulativeVolume = 0;
    private double periodHigh = Double.MIN_VALUE;
    private double periodLow = Double.MAX_VALUE;
    private long periodStartTime = 0;
    private boolean countingVolume = false;

    // === Registered modifiable indicator for visual feedback ===
    private IndicatorModifiable volumeIndicator;

    // === A map to track pending/active rectangles ===
    private Map<Integer, RectangleInfo> rectangles = new HashMap<>();
    private int nextRectangleId = 0;

    // === A class to store rectangle data ===
    private class RectangleInfo {
        int id;
        long startTime;
        double low;
        double high;
        boolean isActive;
        Color color; // determined when activating

        RectangleInfo(int id, long startTime, double low, double high) {
            this.id = id;
            this.startTime = startTime;
            this.low = low;
            this.high = high;
            this.isActive = false;
        }
    }

    // === Called when the indicator is first loaded ===
    @Override
    public void initialize(String alias, InstrumentInfo info, Api api, InitialState initialState) {
        // Register the indicator with Bookmap.
        volumeIndicator = api.registerIndicatorModifiable("Volume Range Indicator", 
                                                            IndicatorModifiable.GraphType.PRIMARY);
        // Set an initial color for the indicator (used for drawing volume levels).
        volumeIndicator.setColor(Color.BLUE);

        // Subscribe to trade and time updates.
        api.addTradeDataListeners(this);
        api.addTimeListeners(this);

        // Log initialization details.
        System.out.println("VolumeRangeIndicator initialized with thresholds: volume=" + volumeThreshold +
                 ", range=" + rangeThreshold + ", activation=" + activationRange);
    }

    @Override
    public void stop() {
        // Cleanup: remove any active rectangles from the chart.
        for (RectangleInfo rect : rectangles.values()) {
            if (rect.isActive) {
                Chart.removeRectangle(rect.id);
            }
        }
        rectangles.clear();
        System.out.println("VolumeRangeIndicator stopped.");
    }

    // === Trade event handler ===
    @Override
    public void onTrade(double price, int size, Object tradeInfo) {
        // Cast tradeInfo to your Trade object (ensure your Trade class exists).
        Trade trade = (Trade) tradeInfo;

        // Begin a new volume period if not already started.
        if (!countingVolume) {
            startNewVolumePeriod(trade);
        }

        // Update volume and price boundaries.
        cumulativeVolume += trade.getVolume();
        periodHigh = Math.max(periodHigh, price);
        periodLow = Math.min(periodLow, price);

        // Update the modifiable indicator to reflect volume progress.
        volumeIndicator.addPoint(price); // Using price as a Y coordinate (adjust as needed).

        // Check if volume threshold has been reached and no pending rectangle exists.
        if (cumulativeVolume >= volumeThreshold && !hasPendingRectangleForCurrentPeriod()) {
            // Create a pending rectangle based on the current volume period.
            RectangleInfo rect = new RectangleInfo(nextRectangleId++, periodStartTime, periodLow, periodHigh);
            // Determine the rectangle's color based on the trade's open and close prices.
            if (trade.getClosePrice() < trade.getOpenPrice()) {
                rect.color = Color.RED;
            } else {
                rect.color = Color.GREEN;
            }
            rectangles.put(rect.id, rect);
            System.out.println("Created pending rectangle id=" + rect.id + " at time=" + periodStartTime);
        }

        // Check pending rectangles for activation criteria.
        checkPendingRectangles(trade, price);

        // Update active rectangles.
        updateActiveRectangles(trade, price);
    }

    // === Time event handler ===
    @Override
    public void onTimestamp(long t) {
        // (Optional) Process time updates if needed.
    }

    // === Helper Methods ===

    private void startNewVolumePeriod(Trade trade) {
        countingVolume = true;
        cumulativeVolume = 0;
        periodHigh = trade.getPrice();
        periodLow = trade.getPrice();
        periodStartTime = trade.getTime();
        System.out.println("Started new volume period at time=" + periodStartTime);
    }

    private boolean hasPendingRectangleForCurrentPeriod() {
        for (RectangleInfo rect : rectangles.values()) {
            if (rect.startTime == periodStartTime && !rect.isActive) {
                return true;
            }
        }
        return false;
    }

    private void checkPendingRectangles(Trade trade, double price) {
        for (RectangleInfo rect : rectangles.values()) {
            if (!rect.isActive) {
                // For a green rectangle: check if price exceeds rect.high plus activationRange.
                if (rect.color == Color.GREEN && price >= rect.high + activationRange) {
                    activateRectangle(rect, trade);
                }
                // For a red rectangle: check if price falls below rect.low minus activationRange.
                else if (rect.color == Color.RED && price <= rect.low - activationRange) {
                    activateRectangle(rect, trade);
                }
            }
        }
    }

    private void activateRectangle(RectangleInfo rect, Trade trade) {
        rect.isActive = true;
        // Draw the rectangle on the chart using Bookmap's Chart API.
        Chart.addRectangle(rect.id, rect.startTime, trade.getTime(), rect.low, rect.high, rect.color);
        System.out.println("Activated rectangle id=" + rect.id + " at time=" + trade.getTime());
    }

    private void updateActiveRectangles(Trade trade, double price) {
        // Extend active rectangles and check for termination.
        for (Iterator<Map.Entry<Integer, RectangleInfo>> it = rectangles.entrySet().iterator(); it.hasNext();) {
            Map.Entry<Integer, RectangleInfo> entry = it.next();
            RectangleInfo rect = entry.getValue();
            if (rect.isActive) {
                Chart.extendRectangle(rect.id, trade.getTime());
                // If price breaks the rectangle's boundaries, remove the rectangle.
                if (price > rect.high || price < rect.low) {
                    Chart.removeRectangle(rect.id);
                    it.remove();
                    System.out.println("Rectangle id=" + rect.id + " removed due to price break at " + trade.getTime());
                    resetVolumePeriod();
                }
            }
        }
    }

    private void resetVolumePeriod() {
        countingVolume = false;
        cumulativeVolume = 0;
        periodHigh = Double.MIN_VALUE;
        periodLow = Double.MAX_VALUE;
        System.out.println("Volume period reset.");
    }
}

public class MboVisualizerNoHistory
        implements CustomModule, CustomSettingsPanelProvider, MarketByOrderDepthDataListener {

    private OrderBookMbo orderBookMbo = new OrderBookMbo();
    private OrderBook orderBook = new OrderBook();

    private JLabel displayLabel;

    private AtomicBoolean updateIsScheduled = new AtomicBoolean();
    
    public MboVisualizerNoHistory() {
        SwingUtilities.invokeLater(() -> {
            displayLabel = new JLabel();
        });
    }

    @Override
    public void initialize(String alias, InstrumentInfo info, Api api, InitialState initialState) {
    }

    @Override
    public void stop() {
    }

    @Override
    public void send(String orderId, boolean isBid, int price, int size) {
        orderBookMbo.send(orderId, isBid, price, size);

        synchronized (orderBook) {
            long levelSize = orderBook.getSizeFor(isBid, price, 0);
            levelSize += size;
            orderBook.onUpdate(isBid, price, levelSize);
        }

        scheduleUpdateIfNecessary();
    }

    @Override
    public void replace(String orderId, int price, int size) {
        Order oldOrder = orderBookMbo.getOrder(orderId);
        boolean isBid = oldOrder.isBid();
        int oldPrice = oldOrder.getPrice();
        int oldSize = oldOrder.getSize();

        orderBookMbo.replace(orderId, price, size);

        synchronized (orderBook) {
            long oldLevelSize = orderBook.getSizeFor(isBid, oldPrice, 0);
            oldLevelSize -= oldSize;

            orderBook.onUpdate(isBid, oldPrice, oldLevelSize);

            long newLevelSize = orderBook.getSizeFor(isBid, price, 0);
            newLevelSize += size;
            orderBook.onUpdate(isBid, price, newLevelSize);
        }
        scheduleUpdateIfNecessary();
    }

    @Override
    public void cancel(String orderId) {
        Order oldOrder = orderBookMbo.getOrder(orderId);
        boolean isBid = oldOrder.isBid();
        int price = oldOrder.getPrice();
        int size = oldOrder.getSize();

        orderBookMbo.cancel(orderId);

        synchronized (orderBook) {
            long levelSize = orderBook.getSizeFor(isBid, price, 0);
            levelSize -= size;
            orderBook.onUpdate(isBid, price, levelSize);
        }
        scheduleUpdateIfNecessary();
    }

    private void scheduleUpdateIfNecessary() {
        boolean shouldSchedule = !updateIsScheduled.getAndSet(true);

        if (shouldSchedule) {
            SwingUtilities.invokeLater(() -> {
                updateIsScheduled.set(false);

                StringBuilder builder = new StringBuilder();
                builder.append("<html>");

                synchronized (orderBook) {
                    Iterator<Entry<Integer, Long>> askItterator = orderBook.getAskMap().entrySet().iterator();
                    Iterator<Entry<Integer, Long>> bidItterator = orderBook.getBidMap().entrySet().iterator();
                    
                    List<String> askRows = new ArrayList<>();
                    for (int i = 0; i < 10 && askItterator.hasNext(); ++i) {
                        Entry<Integer, Long> nextAskEntry = askItterator.next();
                        askRows.add("ASK Distance: " + i + " Price(int): " + nextAskEntry.getKey() + " Size: "
                                + nextAskEntry.getValue() + "<br/>");
                    }
                    Collections.reverse(askRows);
                    askRows.forEach(builder::append);
                    
                    for (int i = 0; i < 10 && bidItterator.hasNext(); ++i) {
                        Entry<Integer, Long> nextBidEntry = bidItterator.next();
                        builder.append("BID Distance: " + i + " Price(int): " + nextBidEntry.getKey() + " Size: "
                                + nextBidEntry.getValue() + "<br/>");
                    }
                }

                builder.append("</html>");
                displayLabel.setText(builder.toString());
            });
        }
    }

    @Override
    public StrategyPanel[] getCustomSettingsPanels() {

        displayLabel = new JLabel();
        scheduleUpdateIfNecessary();

        StrategyPanel ordersPanel = new StrategyPanel("Order book");
        ordersPanel.add(displayLabel);
        return new StrategyPanel[] { ordersPanel };
    }
}
