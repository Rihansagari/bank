package bank;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Executors;

public class BankServer {
    private static final Path DATA = Paths.get("data");
    private static final Path ACCOUNTS_FILE = DATA.resolve("accounts.csv");
    private static final Path TX_FILE = DATA.resolve("transactions.csv");
    private static final Map<Integer, Account> accounts = new LinkedHashMap<>();
    private static final List<Tx> transactions = new ArrayList<>();
    private static int nextId = 1, nextTx = 1;

    static class Account {
        int id;
        String name, number, type;
        double balance;
        Account(int id, String name, String number, String type, double balance) {
            this.id=id; this.name=name; this.number=number; this.type=type; this.balance=balance;
        }
    }
    static class Tx {
        int id, accountId;
        String type, details, date;
        double amount;
        Tx(int id, int accountId, String type, double amount, String details) {
            this.id=id; this.accountId=accountId; this.type=type; this.amount=amount;
            this.details=details;
            this.date=LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        }
        Tx(int id, int accountId, String type, double amount, String details, String date) {
            this.id=id; this.accountId=accountId; this.type=type; this.amount=amount; this.details=details; this.date=date;
        }
    }

    public static void main(String[] args) throws Exception {
        Files.createDirectories(DATA);
        loadData();
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/", BankServer::handle);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.println("Bank Management System running at http://localhost:8080");
        System.out.println("Press Ctrl+C to stop.");
    }

    private static void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            if (path.equals("/api/accounts") && method.equals("GET")) {
                json(ex, 200, accountsJson());
            } else if (path.equals("/api/transactions") && method.equals("GET")) {
                json(ex, 200, transactionsJson());
            } else if (path.equals("/api/dashboard") && method.equals("GET")) {
                json(ex, 200, dashboardJson());
            } else if (path.equals("/api/create") && method.equals("POST")) {
                Map<String,String> p = params(ex);
                String name = p.getOrDefault("name", "").trim();
                String type = p.getOrDefault("type", "Savings");
                double opening = amount(p.getOrDefault("amount", "0"));
                if (name.length() < 2) throw new IllegalArgumentException("Enter a name with at least 2 characters.");
                if (opening < 0) throw new IllegalArgumentException("Opening balance cannot be negative.");
                int id = nextId++;
                String number = "SB" + (100000 + new Random().nextInt(900000)) + id;
                Account a = new Account(id, name, number, type, opening);
                accounts.put(id, a);
                addTx(id, "Account Opened", opening, "New " + type + " account");
                saveData();
                json(ex, 200, "{\"message\":\"Account created successfully\",\"accountNumber\":\""+esc(number)+"\"}");
            } else if (path.equals("/api/deposit") && method.equals("POST")) {
                Map<String,String> p = params(ex);
                Account a = getAccount(p.get("id"));
                double value = amount(p.get("amount"));
                if (value <= 0) throw new IllegalArgumentException("Amount must be greater than zero.");
                a.balance = round(a.balance + value);
                addTx(a.id, "Deposit", value, "Money deposited");
                saveData();
                json(ex, 200, "{\"message\":\"Deposit successful\"}");
            } else if (path.equals("/api/withdraw") && method.equals("POST")) {
                Map<String,String> p = params(ex);
                Account a = getAccount(p.get("id"));
                double value = amount(p.get("amount"));
                if (value <= 0) throw new IllegalArgumentException("Amount must be greater than zero.");
                if (a.balance < value) throw new IllegalArgumentException("Insufficient balance.");
                a.balance = round(a.balance - value);
                addTx(a.id, "Withdrawal", value, "Money withdrawn");
                saveData();
                json(ex, 200, "{\"message\":\"Withdrawal successful\"}");
            } else if (path.equals("/api/transfer") && method.equals("POST")) {
                Map<String,String> p = params(ex);
                Account from = getAccount(p.get("from"));
                Account to = getAccount(p.get("to"));
                double value = amount(p.get("amount"));
                if (from.id == to.id) throw new IllegalArgumentException("Choose two different accounts.");
                if (value <= 0) throw new IllegalArgumentException("Amount must be greater than zero.");
                if (from.balance < value) throw new IllegalArgumentException("Insufficient balance.");
                from.balance = round(from.balance - value);
                to.balance = round(to.balance + value);
                addTx(from.id, "Transfer Sent", value, "Sent to " + to.number);
                addTx(to.id, "Transfer Received", value, "Received from " + from.number);
                saveData();
                json(ex, 200, "{\"message\":\"Transfer successful\"}");
            } else {
                serveStatic(ex, path);
            }
        } catch (IllegalArgumentException e) {
            json(ex, 400, "{\"error\":\"" + esc(e.getMessage()) + "\"}");
        } catch (Exception e) {
            e.printStackTrace();
            json(ex, 500, "{\"error\":\"Server error. Check the VS Code terminal.\"}");
        } finally {
            ex.close();
        }
    }

    private static synchronized Account getAccount(String id) {
        try {
            Account a = accounts.get(Integer.parseInt(id));
            if (a == null) throw new IllegalArgumentException("Account not found.");
            return a;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Select a valid account.");
        }
    }
    private static double amount(String s) {
        try {
            double d = Double.parseDouble(s);
            if (!Double.isFinite(d) || Math.abs(d) > 1_000_000_000_000d) throw new Exception();
            return round(d);
        } catch (Exception e) { throw new IllegalArgumentException("Enter a valid amount."); }
    }
    private static double round(double d) { return Math.round(d * 100.0) / 100.0; }
    private static void addTx(int id, String type, double amount, String details) {
        transactions.add(0, new Tx(nextTx++, id, type, amount, details));
    }
    private static Map<String,String> params(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String,String> result = new HashMap<>();
        for (String pair : body.split("&")) {
            if (pair.isEmpty()) continue;
            String[] kv = pair.split("=", 2);
            result.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
        }
        return result;
    }
    private static void json(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
    }
    private static void serveStatic(HttpExchange ex, String path) throws IOException {
        if (path.equals("/")) path = "/index.html";
        Path base = Paths.get("public").toAbsolutePath().normalize();
        Path file = base.resolve(path.substring(1)).normalize();
        if (!file.startsWith(base) || !Files.exists(file) || Files.isDirectory(file)) {
            byte[] b = "Not found".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(404,b.length); ex.getResponseBody().write(b); return;
        }
        String type = file.toString().endsWith(".css") ? "text/css; charset=UTF-8" :
                      file.toString().endsWith(".js") ? "application/javascript; charset=UTF-8" : "text/html; charset=UTF-8";
        byte[] b = Files.readAllBytes(file);
        ex.getResponseHeaders().set("Content-Type",type);
        ex.sendResponseHeaders(200,b.length); ex.getResponseBody().write(b);
    }
    private static String accountsJson() {
        StringBuilder s = new StringBuilder("["); boolean first=true;
        for (Account a: accounts.values()) {
            if (!first) s.append(","); first=false;
            s.append("{\"id\":").append(a.id).append(",\"name\":\"").append(esc(a.name))
             .append("\",\"number\":\"").append(esc(a.number)).append("\",\"type\":\"").append(esc(a.type))
             .append("\",\"balance\":").append(a.balance).append("}");
        }
        return s.append("]").toString();
    }
    private static String transactionsJson() {
        StringBuilder s = new StringBuilder("["); boolean first=true;
        for (Tx t: transactions) {
            if (!first) s.append(","); first=false;
            s.append("{\"id\":").append(t.id).append(",\"accountId\":").append(t.accountId)
             .append(",\"type\":\"").append(esc(t.type)).append("\",\"amount\":").append(t.amount)
             .append(",\"details\":\"").append(esc(t.details)).append("\",\"date\":\"").append(esc(t.date)).append("\"}");
        }
        return s.append("]").toString();
    }
    private static String dashboardJson() {
        double total = accounts.values().stream().mapToDouble(a -> a.balance).sum();
        return "{\"accountCount\":" + accounts.size() + ",\"totalBalance\":" + round(total) + "}";
    }
    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\","\\\\").replace("\"","\\\"").replace("\n"," ").replace("\r"," ").replace("<","\\u003c").replace(">","\\u003e").replace("&","\\u0026");
    }
    private static void loadData() throws IOException {
        if (Files.exists(ACCOUNTS_FILE)) {
            for (String line : Files.readAllLines(ACCOUNTS_FILE)) {
                String[] v = line.split(",", -1);
                if (v.length != 5) continue;
                try {
                    Account a = new Account(Integer.parseInt(v[0]), decode(v[1]), v[2], decode(v[3]), Double.parseDouble(v[4]));
                    accounts.put(a.id,a); nextId=Math.max(nextId,a.id+1);
                } catch (Exception ignored) {}
            }
        }
        if (Files.exists(TX_FILE)) {
            for (String line : Files.readAllLines(TX_FILE)) {
                String[] v = line.split(",", -1);
                if (v.length != 6) continue;
                try {
                    Tx t = new Tx(Integer.parseInt(v[0]),Integer.parseInt(v[1]),decode(v[2]),Double.parseDouble(v[3]),decode(v[4]),decode(v[5]));
                    transactions.add(t); nextTx=Math.max(nextTx,t.id+1);
                } catch (Exception ignored) {}
            }
            transactions.sort((a,b)->Integer.compare(b.id,a.id));
        }
    }
    private static String encode(String s) { return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8)); }
    private static String decode(String s) { return new String(Base64.getDecoder().decode(s),StandardCharsets.UTF_8); }
    private static synchronized void saveData() throws IOException {
        List<String> aLines = new ArrayList<>();
        for (Account a:accounts.values()) aLines.add(a.id+","+encode(a.name)+","+a.number+","+encode(a.type)+","+a.balance);
        Files.write(ACCOUNTS_FILE,aLines,StandardCharsets.UTF_8);
        List<String> tLines = new ArrayList<>();
        for (Tx t:transactions) tLines.add(t.id+","+t.accountId+","+encode(t.type)+","+t.amount+","+encode(t.details)+","+encode(t.date));
        Files.write(TX_FILE,tLines,StandardCharsets.UTF_8);
    }
}
