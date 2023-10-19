package burp.ui.board;

import burp.IBurpExtenderCallbacks;
import burp.IExtensionHelpers;
import burp.IHttpRequestResponse;
import burp.IHttpRequestResponsePersisted;
import burp.IHttpService;
import burp.IMessageEditor;
import burp.IMessageEditorController;
import burp.config.ConfigEntry;
import burp.core.utils.StringHelper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableModel;
import javax.swing.table.TableRowSorter;

/**
 * @author EvilChen
 */

public class MessagePanel extends AbstractTableModel implements IMessageEditorController {
    private JSplitPane splitPane;
    private IMessageEditor requestViewer;
    private IMessageEditor responseViewer;
    private final IBurpExtenderCallbacks callbacks;
    private final List<LogEntry> log = new ArrayList<LogEntry>();
    private final List<LogEntry> filteredLog = new ArrayList<LogEntry>();
    private IHttpRequestResponse currentlyDisplayedItem;
    private final IExtensionHelpers helpers;
    private Table logTable;

    public MessagePanel(IBurpExtenderCallbacks callbacks, IExtensionHelpers helpers) {
        this.callbacks = callbacks;
        this.helpers = helpers;

        splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);

        logTable = new Table(MessagePanel.this);
        logTable.setDefaultRenderer(Object.class, new ColorRenderer(filteredLog, logTable));
        logTable.setAutoCreateRowSorter(true);

        // Length字段根据大小进行排序
        TableRowSorter<DefaultTableModel> sorter = (TableRowSorter<DefaultTableModel>) logTable.getRowSorter();
        sorter.setComparator(3, new Comparator<String>() {
            @Override
            public int compare(String s1, String s2) {
                Integer age1 = Integer.parseInt(s1);
                Integer age2 = Integer.parseInt(s2);
                return age1.compareTo(age2);
            }
        });
        // Color字段根据颜色顺序进行排序
        sorter.setComparator(4, new Comparator<String>() {
            @Override
            public int compare(String s1, String s2) {
                int index1 = getIndex(s1);
                int index2 = getIndex(s2);
                return Integer.compare(index1, index2);
            }
            private int getIndex(String color) {
                for (int i = 0; i < ConfigEntry.colorArray.length; i++) {
                    if (ConfigEntry.colorArray[i].equals(color)) {
                        return i;
                    }
                }
                return -1;
            }
        });

        logTable.setRowSorter(sorter);
        logTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);

        JScrollPane scrollPane = new JScrollPane(logTable);
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_ALWAYS);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        splitPane.setLeftComponent(scrollPane);

        JTabbedPane tabs = new JTabbedPane();
        requestViewer = callbacks.createMessageEditor(MessagePanel.this, false);

        responseViewer = callbacks.createMessageEditor(MessagePanel.this, false);
        tabs.addTab("Request", requestViewer.getComponent());
        tabs.addTab("Response", responseViewer.getComponent());
        splitPane.setRightComponent(tabs);
    }

    public JSplitPane getPanel() {
        return splitPane;
    }

    public Table getTable() {
        return logTable;
    }

    public List<LogEntry> getLogs() {
        return log;
    }

    @Override
    public int getRowCount()
    {
        return filteredLog.size();
    }

    @Override
    public int getColumnCount()
    {
        return 5;
    }

    @Override
    public String getColumnName(int columnIndex)
    {
        switch (columnIndex)
        {
            case 0:
                return "Method";
            case 1:
                return "URL";
            case 2:
                return "Comment";
            case 3:
                return "Length";
            case 4:
                return "Color";
            default:
                return "";
        }
    }

    @Override
    public Class<?> getColumnClass(int columnIndex)
    {
        return String.class;
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex)
    {
        LogEntry logEntry = filteredLog.get(rowIndex);
        switch (columnIndex)
        {
            case 0:
                return logEntry.getMethod();
            case 1:
                return logEntry.getUrl().toString();
            case 2:
                return logEntry.getComment();
            case 3:
                return logEntry.getLength();
            case 4:
                return logEntry.getColor();
            default:
                return "";
        }
    }

    public void applyHostFilter(String filterText) {
        filteredLog.clear();
        fireTableDataChanged();
        for (LogEntry entry : log) {
            String host = entry.getUrl().getHost();
            if (StringHelper.matchFromEnd(host, filterText) || filterText.contains("*")) {
                filteredLog.add(entry);
            }
        }
        fireTableDataChanged();
    }

    public void applyMessageFilter(String tableName, String filterText) {
        filteredLog.clear();
        for (LogEntry entry : log) {
            IHttpRequestResponsePersisted requestResponse = entry.getRequestResponse();
            byte[] requestByte = requestResponse.getRequest();
            byte[] responseByte = requestResponse.getResponse();

            String requestString = new String(requestResponse.getRequest(), StandardCharsets.UTF_8);
            String responseString = new String(requestResponse.getResponse(), StandardCharsets.UTF_8);

            List<String> requestTmpHeaders = helpers.analyzeRequest(requestByte).getHeaders();
            String requestHeaders = new String(String.join("\n", requestTmpHeaders).getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
            int requestBodyOffset = helpers.analyzeRequest(requestByte).getBodyOffset();
            String requestBody = new String(Arrays.copyOfRange(requestByte, requestBodyOffset, requestByte.length), StandardCharsets.UTF_8);

            List<String> responseTmpHeaders = helpers.analyzeResponse(responseByte).getHeaders();
            String responseHeaders = new String(String.join("\n", responseTmpHeaders).getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
            int responseBodyOffset = helpers.analyzeResponse(responseByte).getBodyOffset();
            String responseBody = new String(Arrays.copyOfRange(responseByte, responseBodyOffset, responseByte.length), StandardCharsets.UTF_8);

            final boolean[] isMatched = {false}; // 标志变量，表示是否满足过滤条件

            ConfigEntry.globalRules.keySet().forEach(i -> {
                for (Object[] objects : ConfigEntry.globalRules.get(i)) {
                    String name = objects[1].toString();
                    String scope = objects[4].toString();
                    if (name.contains(tableName)) {
                        boolean match = false; // 标志变量，表示当前规则是否匹配

                        switch (scope) {
                            case "any":
                                match = requestString.contains(filterText) || responseString.contains(filterText);
                                break;
                            case "request":
                                match = requestString.contains(filterText);
                                break;
                            case "response":
                                match = responseString.contains(filterText);
                                break;
                            case "any header":
                                match = requestHeaders.contains(filterText) || responseHeaders.contains(filterText);
                                break;
                            case "request header":
                                match = requestHeaders.contains(filterText);
                                break;
                            case "response header":
                                match = responseHeaders.contains(filterText);
                                break;
                            case "any body":
                                match = requestBody.contains(filterText) || responseBody.contains(filterText);
                                break;
                            case "request body":
                                match = requestBody.contains(filterText);
                                break;
                            case "response body":
                                match = responseBody.contains(filterText);
                                break;
                            default:
                                break;
                        }

                        if (match) {
                            isMatched[0] = true;
                            break;
                        }
                    }
                }
            });

            if (isMatched[0]) {
                filteredLog.add(entry);
            }
        }
        fireTableDataChanged();
    }

    public void deleteByHost(String filterText) {
        filteredLog.clear();
        List<Integer> rowsToRemove = new ArrayList<>();
        for (int i = 0; i < log.size(); i++) {
            LogEntry entry = log.get(i);
            String host = entry.getUrl().getHost();
            if (StringHelper.matchFromEnd(host, filterText) || filterText.contains("*")) {
                rowsToRemove.add(i);
            }
        }

        for (int i = rowsToRemove.size() - 1; i >= 0; i--) {
            int row = rowsToRemove.get(i);
            log.remove(row);
        }

        if (!rowsToRemove.isEmpty()) {
            int[] rows = rowsToRemove.stream().mapToInt(Integer::intValue).toArray();
            fireTableRowsDeleted(rows[0], rows[rows.length - 1]);
        }
    }

    @Override
    public byte[] getRequest()
    {
        return currentlyDisplayedItem.getRequest();
    }

    @Override
    public byte[] getResponse()
    {
        return currentlyDisplayedItem.getResponse();
    }

    @Override
    public IHttpService getHttpService()
    {
        return currentlyDisplayedItem.getHttpService();
    }

    public void add(IHttpRequestResponse messageInfo, String comment, String length, String color) {
        synchronized(log)
        {
            LogEntry logEntry = new LogEntry(callbacks.saveBuffersToTempFiles(messageInfo), helpers.analyzeRequest(messageInfo).getMethod(),
                    helpers.analyzeRequest(messageInfo).getUrl(), comment, length, color);
            log.add(logEntry);
        }
    }

    public class Table extends JTable {
        LogEntry logEntry;
        private SwingWorker<Void, Void> currentWorker;

        public Table(TableModel tableModel) {
            super(tableModel);
        }

        @Override
        public void changeSelection(int row, int col, boolean toggle, boolean extend) {
            logEntry = filteredLog.get(convertRowIndexToModel(row));
            requestViewer.setMessage("Loading...".getBytes(), true);
            responseViewer.setMessage("Loading...".getBytes(), false);
            currentlyDisplayedItem = logEntry.getRequestResponse();

            // 取消之前的后台任务
            if (currentWorker != null && !currentWorker.isDone()) {
                currentWorker.cancel(true);
            }
            // 在后台线程中执行耗时操作
            SwingWorker<Void, Void> worker = new SwingWorker<Void, Void>() {
                @Override
                protected Void doInBackground() throws Exception {
                    refreshMessage();
                    return null;
                }
            };
            // 设置当前后台任务
            currentWorker = worker;
            // 启动后台线程
            worker.execute();
            super.changeSelection(row, col, toggle, extend);
        }

        private void refreshMessage() {
            SwingUtilities.invokeLater(() -> {
                requestViewer.setMessage(logEntry.getRequestResponse().getRequest(), true);
                responseViewer.setMessage(logEntry.getRequestResponse().getResponse(), false);
            });
        }
    }

}
